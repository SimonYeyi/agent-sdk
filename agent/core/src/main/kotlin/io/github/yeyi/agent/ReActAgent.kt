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
import io.github.yeyi.agent.memory.MemoryEntry
import io.github.yeyi.agent.memory.RepairReason
import io.github.yeyi.agent.memory.ReadOnlyMemory
import io.github.yeyi.agent.memory.RepairedMemory
import io.github.yeyi.agent.memory.RoundsBoundedMemory
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.memory.repairOrphans
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

    /** 当前活跃 run 的 steer 信箱。CAS null→channel 守卫并发 run，null 表示无活跃 run。 */
    private val steerInboxRef = AtomicReference<Channel<AgentQuery>?>(null)

    /**
     * 序列化 steer() 的 [get+trySend] 与终局 [isEmpty 裁决 + 终态发射 + 关门]，堵死
     * "steer 返回 true 但消息困死 buffer 无人消费"的窗口。
     *
     * 完结束契约：steer() 返回 false ⟺ 无活跃 run ⟺ 终态事件(Final)已发射完毕。
     * 终局持锁期间 steer() 阻塞等待，正是语义正确的"等待裁决"——既不能 true
     * （消息无人消费）也不能 false（Final 未发完，此时开新 run 会与旧 run 的事件混流）。
     *
     * 纪律：锁内允许且仅允许 onRunCompleted 与 emit(Final) 两个已知挂起点，
     * 严禁其他 suspend——持锁挂起会阻塞其他线程的 steer，编译器不拦，只能靠约定。
     * 死锁硬约束：collector / AgentHook 回调内严禁同步调用 steer()。
     */
    private val steerLock = ReentrantLock()

    override fun run(query: AgentQuery): Flow<AgentEvent> = flow {
        loop(query, { req -> llmProvider.chat(req) }, { emit(it) })
    }

    override fun steer(query: AgentQuery): Boolean = steerLock.withLock {
        steerInboxRef.get()?.trySend(query)?.isSuccess ?: false
    }

    override fun runStream(query: AgentQuery): Flow<AgentEvent> = flow {
        val llmCall: suspend (ChatRequest) -> ChatResponse = { req ->
            val accumulatedText = StringBuilder()
            val callOrder: LinkedHashSet<String> = linkedSetOf()
            val callNames: MutableMap<String, String> = mutableMapOf()
            val argumentsBuffers: MutableMap<String, StringBuilder> = mutableMapOf()
            var finishReason: FinishReason? = null
            var usage: Usage? = null

            llmProvider.chatStream(req).collect { event ->
                when (event) {
                    is ChatResponseEvent.ContentDelta -> {
                        accumulatedText.append(event.text)
                        emit(AgentEvent.TextDelta(event.text))
                    }

                    is ChatResponseEvent.ToolCallStart -> {
                        callOrder.add(event.id) // LinkedHashSet: idempotent + preserves first-seen order
                        callNames[event.id] = event.name
                        argumentsBuffers.getOrPut(event.id) { StringBuilder() }
                    }

                    is ChatResponseEvent.ToolCallDelta -> {
                        // LlmProvider 契约:Delta.id 必非空(continuation chunk 由 provider 填充)。
                        // 若违反,静默丢弃会导致 arguments JSON 损坏,fail-fast 更安全。
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
            ChatResponse(
                message = ChatMessage.Assistant(
                    content = accumulatedText.toString(),
                    toolCalls = toolCalls,
                ),
                usage = usage,
                finishReason = finishReason!!
            )
        }
        loop(query = query, llmCall = llmCall, emit = { emit(it) })
    }

    private suspend fun loop(
        query: AgentQuery,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        val steerInbox = Channel<AgentQuery>(Channel.UNLIMITED).also {
            if (!steerInboxRef.compareAndSet(null, it)) {
                error("Concurrent run not supported: another run is active")
            }
        }

        val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()
        var iterations = 0

        try {
            emit(AgentEvent.Initial(query))

            memory.attachHook(MemoryCompressProxyHook(hook, emit), buildContext(0))
            memory.add(MemoryEntry(modalityAdapter.archive(ChatMessage.User(query.parts))))

            while (iterations < maxIterations) {
                loopOnce(++iterations, toolCalls, llmCall, emit, steerInbox)?.let { return }
            }

            throw AgentException.MaxIterations(maxIterations)
        } catch (cause: Throwable) {
            if (cause is kotlinx.coroutines.CancellationException) {
                withContext(NonCancellable) { memory.repairOrphans(RepairReason.CANCELLED) }
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun loopOnce(
        iterations: Int,
        toolCalls: MutableList<AgentResult.ToolCallRecord>,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
        steerInbox: Channel<AgentQuery>,
    ): AgentResult? {
        // 检查点①：迭代头注入。Instruct 在此进入 memory，本轮 buildRequest 即生效
        if (steerInbox.isEmpty.not()) consumeSteering(steerInbox)

        val context = buildContext(iterations)
        val request = buildRequest()

        val finalRequest = hook.safeInvoke { beforeLlmCall(context, request) } ?: request
        val response = llmCallWithContextOverflowHandle(finalRequest, llmCall)
        hook.safeInvoke { afterLlmResponse(context, response) }

        memory.add(MemoryEntry(response.message))

        if (response.message.toolCalls.isEmpty()) {
            // 检查点②：Final 抢占 + 终局。与 steer() 锁下互斥。
            steerLock.lock()
            if (steerInbox.isEmpty.not()) {
                steerLock.unlock()
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
                // 先发射后关门：emit 抛异常时此处 CAS+close 不执行，由 loop 的 finally
                // 兜底清理——保证 false ⟹ Final 已发射的严格性（发射失败即失败路径，
                // 退化为 best-effort）。
                steerInbox.destroy()
            } finally {
                steerLock.unlock()
            }
            return result
        }

        emit(
            AgentEvent.ToolCallExplanation(
                response.message.content?.takeIf { it != "" },
                response.message.toolCalls.map { it.name })
        )

        for (call in response.message.toolCalls) {
            emit(AgentEvent.ToolCallStart(call.id, call.name))
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

            memory.add(
                MemoryEntry(
                    modalityAdapter.archive(
                        ChatMessage.ToolResult(
                            toolCallId = call.id,
                            toolName = call.name,
                            parts = final.parts,
                            isError = final.isError,
                        )
                    )
                )
            )

            emit(AgentEvent.ToolCallEnd(call.id, final))
        }
        return null
    }

    /**
     * 消费 steerInbox 中的在途指令，注入为 User 消息。
     * 注入点在批次完整点，保证 tool_call/tool_result 配对不被插入消息破坏。
     * 必须在锁外调用：memory.add 可能触发压缩 LLM 调用（秒级），不能挡 steer。
     */
    private suspend fun consumeSteering(steerInbox: Channel<AgentQuery>) {
        while (true) {
            val query = steerInbox.tryReceive().getOrNull() ?: return
            memory.add(
                MemoryEntry(
                    message = modalityAdapter.archive(ChatMessage.User(query.parts)),
                    tags = setOf("steering")
                )
            )
        }
    }

    private fun Channel<AgentQuery>.destroy() {
        // 顺序契约：CAS(null) 必须先于 close()，杜绝 "ref 非 null 但 channel 已关闭" 的中间态——
        //   ref 非 null ⟹ channel 未关闭 ⟹ steer() 的 trySend 必成功 ⟹ 返回 true
        //   ref null   ⟹ steer() 返回 false ⟹ 新 run() 的 CAS(null→new) 必成功，不会抛 IllegalStateException
        // 若颠倒顺序（先 close 后 CAS），会引入 steer false 但 run() CAS 失败的反例。
        steerInboxRef.compareAndSet(this, null)
        this.close()
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
}
