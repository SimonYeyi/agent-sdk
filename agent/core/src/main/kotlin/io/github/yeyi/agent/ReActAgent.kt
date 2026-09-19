package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.log.log
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.memory.ReadOnlyMemory
import io.github.yeyi.agent.memory.RepairedMemory
import io.github.yeyi.agent.memory.RoundsBoundedMemory
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.memory.addToMemory
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.withContext
import io.github.yeyi.agent.modality.ModalityAdapter
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

public class ReActAgent internal constructor(
    private val persona: Persona,
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry,
    memory: Memory,
    private val modalityAdapter: ModalityAdapter,
    private val maxRounds: Int,
    private val maxIterations: Int,
    private val hook: AgentHook = NoOpAgentHook,
) : Agent, Streamable, Steerable {
    private val memory = RoundsBoundedMemory(RepairedMemory(memory), maxRounds, llmProvider)
    private val steerInbox = SteerInbox()

    override fun run(query: AgentQuery): Flow<AgentEvent> = flow {
        loop(query, { req -> llmProvider.chat(req) }, { emit(it) })
    }

    override fun runStream(query: AgentQuery): Flow<AgentEvent> = flow {
        val runner = StreamingRunner(llmProvider)
        loop(
            query = query,
            llmCall = { req -> runner.run(req) { emit(AgentEvent.TextDelta(it)) } },
            emit = { emit(it) }
        )
    }

    /**
     * [SteerInbox.lock] 序列化 [SteerInbox.deliver] 与终局 isEmpty 裁决+终态发射+关门，堵死
     * "deliver 返回 true 但消息困死 buffer 无人消费"的窗口。
     *
     * 完结束契约：返回 false ⟺ 无活跃 run ⟺ 终态事件(Final)已发射完毕。
     * 终局持锁期间阻塞等待，正是语义正确的"等待裁决"——既不能 true
     * （消息无人消费）也不能 false（Final 未发完，此时开新 run 会与旧 run 的事件混流）。
     *
     * 纪律：锁内允许且仅允许 onRunCompleted 与 emit(Final) 两个已知挂起点，
     * 严禁其他 suspend——持锁挂起会阻塞其他线程的 deliver，编译器不拦，只能靠约定。
     * 死锁硬约束：collector / AgentHook 回调内严禁同步调用 steer()。
     */
    override fun steer(query: AgentQuery): Boolean = steerInbox.deliver(query)

    private suspend fun loop(
        query: AgentQuery,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        steerInbox.create()

        val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()
        var iterations = 0

        try {
            emit(AgentEvent.Initial(query))

            memory.attachHook(MemoryCompressProxyHook(hook, emit), buildContext(0))
            modalityAdapter.archive(ChatMessage.User(query.parts)).addToMemory(memory)

            while (iterations < maxIterations) {
                loopOnce(++iterations, toolCalls, llmCall, emit)?.let { return }
            }

            throw AgentException.MaxIterations(maxIterations)
        } catch (cause: Throwable) {
            if (cause is kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) {
                    RepairedMemory.repairOrphans(memory, RepairedMemory.CANCELLED)
                }
                throw cause
            }
            // 失败路径携带原始 Throwable,不再包成 AgentException —— 让上层自由判型。
            hook.safeInvoke { onRunFailed(buildContext(iterations), cause) }
            emit(AgentEvent.Failed(cause))
        } finally {
            // 兜底销毁（幂等，正常 Final 路径已在检查点②销毁过）。
            steerInbox.destroy()
        }
    }

    private suspend fun loopOnce(
        iterations: Int,
        toolCalls: MutableList<AgentResult.ToolCallRecord>,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
    ): AgentResult? {
        // 检查点①：迭代头注入。Instruct 在此进入 memory，本轮 buildRequest 即生效
        if (!steerInbox.isEmpty()) consumeSteering(steerInbox)

        val context = buildContext(iterations)
        val request = buildRequest()

        val finalRequest = hook.safeInvoke { beforeLlmCall(context, request) } ?: request
        val response = llmCallWithContextOverflowHandle(finalRequest, llmCall)
        hook.safeInvoke { afterLlmResponse(context, response) }

        response.message.addToMemory(memory)

        if (response.message.toolCalls.isEmpty()) {
            // 检查点②：Final 抢占 + 终局。与 steer() 锁下互斥。
            steerInbox.lock.lock()
            if (!steerInbox.isEmpty()) {
                steerInbox.lock.unlock()
                // 指令已注入 memory，抢占 Final，强制再跑一轮让 LLM 带着新指令重新作答
                consumeSteering(steerInbox)
                return null
            }
            val result = AgentResult(
                message = response.message,
                iterations = iterations,
                toolCalls = toolCalls.toList(),
                usage = response.usage,
            )
            hook.safeInvoke { onRunCompleted(context, result) }
            try {
                emit(AgentEvent.Final(result))
                // 终局：终态发射 → 关门，全程持锁。
                // unlock 之后 steer() 拿到的 false 才意味着"可安全开新 run"——
                // 若把 emit(Final) 放锁外，缝隙里 steer()==false 触发的 fallback run
                // 会先发 Initial，旧 run 的 Final 后到，混流。
                // 先发射后关门：emit 抛异常时此处 destroy 不执行，由 loop 的 finally
                // 兜底清理——保证 false ⟹ Final 已发射的严格性（发射失败即失败路径，
                // 退化为 best-effort）。
                steerInbox.destroy()
            } finally {
                steerInbox.lock.unlock()
            }
            return result
        }

        emit(
            AgentEvent.ToolCallExplanation(
                response.message.content?.takeIf { it != "" },
                response.message.toolCalls)
        )

        for (call in response.message.toolCalls) {
            emit(AgentEvent.ToolCallStart(call))
            val synthetic = hook.safeInvoke { beforeToolCall(context, call) }
            val startMs = System.currentTimeMillis()
            val raw = synthetic?.copy(isError = true) ?: toolRegistry.dispatch(
                call.name,
                call.arguments,
                ToolExecutionContext(call.id, context)
            )
            val durMs = System.currentTimeMillis() - startMs
            val final = hook.safeInvoke {
                afterToolCall(context, call, raw, synthetic != null, durMs)
            } ?: raw

            toolCalls += AgentResult.ToolCallRecord(
                callId = call.id,
                toolName = call.name,
                arguments = call.arguments,
                result = final,
                timestamp = java.time.Instant.now(),
            )

            modalityAdapter.archive(
                ChatMessage.ToolResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    parts = final.parts,
                    isError = final.isError,
                )
            ).addToMemory(memory)

            emit(AgentEvent.ToolCallEnd(call, final))
        }
        return null
    }

    private suspend fun consumeSteering(steerInbox: SteerInbox) {
        steerInbox.drain().forEach { query ->
            modalityAdapter.archive(ChatMessage.User(query.parts))
                .addToMemory(memory, tags = setOf("steering"))
        }
    }

    private suspend fun llmCallWithContextOverflowHandle(
        request: ChatRequest,
        llmCall: suspend (ChatRequest) -> ChatResponse
    ): ChatResponse {
        return try {
            llmCall(request)
        } catch (e: Throwable) {
            e.takeIf { it.isContextOverflow() }
                ?.let { log.warn(it) }
                ?.let { memory.handleContextOverflow() }
                ?.let { llmCallWithContextOverflowHandle(buildRequest(), llmCall) }
                ?: throw e
        }
    }

    private suspend fun buildRequest(): ChatRequest {
        val messages = modalityAdapter.resolve(memory.history().map { it.message })
        return ChatRequest(
            messages = buildList {
                add(ChatMessage.System(persona.toString()))
                addAll(messages)
            },
            tools = toolRegistry.all().map(Tool::toDefinition)
        )
    }

    private fun buildContext(currentIteration: Int) = AgentContext(
        persona = persona,
        maxIterations = maxIterations,
        currentIteration = currentIteration,
        memory = ReadOnlyMemory(memory),
        llmProvider = llmProvider,
        tools = toolRegistry.all(),
        maxRounds = maxRounds,
    )

    private class MemoryCompressProxyHook(
        private val hook: AgentHook,
        private val emit: suspend (AgentEvent) -> Unit
    ) : AgentHook by hook {

        override suspend fun beforeMemoryCompress(context: AgentContext, summaries: List<Summary>) {
            hook.safeInvoke { beforeMemoryCompress(context, summaries) }
            emit(AgentEvent.MemoryCompressing(summaries))
        }

        override suspend fun afterMemoryCompress(context: AgentContext, summaries: List<Summary>) {
            hook.safeInvoke { afterMemoryCompress(context, summaries) }
            emit(AgentEvent.MemoryCompressed(summaries))
        }
    }

    private class StreamingRunner(private val llmProvider: LlmProvider) {
        suspend fun run(request: ChatRequest, onDelta: suspend (String) -> Unit): ChatResponse {
            val accumulatedText = StringBuilder()
            val callOrder: LinkedHashSet<String> = linkedSetOf()
            val callNames: MutableMap<String, String> = mutableMapOf()
            val argumentsBuffers: MutableMap<String, StringBuilder> = mutableMapOf()
            var finishReason: FinishReason? = null
            var usage: Usage? = null

            llmProvider.chatStream(request).collect { event ->
                when (event) {
                    is ChatResponseEvent.ContentDelta -> {
                        accumulatedText.append(event.text)
                        onDelta(event.text)
                    }

                    is ChatResponseEvent.ToolCallStart -> {
                        callOrder.add(event.id)
                        callNames[event.id] = event.name
                        argumentsBuffers.getOrPut(event.id) { StringBuilder() }
                    }

                    is ChatResponseEvent.ToolCallDelta -> {
                        argumentsBuffers[event.id!!]?.append(event.argumentsDelta)
                    }

                    is ChatResponseEvent.Done -> {
                        finishReason = event.finishReason
                        usage = event.usage
                    }

                    is ChatResponseEvent.Error -> throw event.cause
                }
            }

            val toolCalls: List<ToolCall> = callOrder.map { id ->
                val arguments = argumentsBuffers[id]?.toString()
                    ?.let { Json.parseToJsonElement(it) }
                    ?: JsonNull
                ToolCall(id = id, name = callNames[id]!!, arguments = arguments)
            }
            return ChatResponse(
                message = ChatMessage.Assistant(
                    content = accumulatedText.toString(),
                    toolCalls = toolCalls,
                ),
                usage = usage,
                finishReason = finishReason!!,
            )
        }
    }

    private class SteerInbox {
        private val channelRef = AtomicReference<Channel<AgentQuery>?>(null)

        val lock = ReentrantLock()

        fun create() {
            if (!channelRef.compareAndSet(null, Channel(Channel.UNLIMITED))) {
                error("Steer inbox already created")
            }
        }

        fun destroy() = channelRef.getAndSet(null)?.close()

        fun deliver(query: AgentQuery): Boolean = lock.withLock {
            channelRef.get()?.trySend(query)?.isSuccess ?: false
        }

        fun drain(): List<AgentQuery> = lock.withLock {
            buildList {
                while (true) {
                    val query = channelRef.get()?.tryReceive()?.getOrNull() ?: return@buildList
                    add(query)
                }
            }
        }

        @OptIn(ExperimentalCoroutinesApi::class)
        fun isEmpty(): Boolean = channelRef.get()?.isEmpty ?: true
    }
}
