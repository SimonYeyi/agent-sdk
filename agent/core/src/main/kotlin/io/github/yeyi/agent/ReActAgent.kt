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

public class ReActAgent internal constructor(
    private val persona: Persona,
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry,
    memory: Memory,
    private val modalityAdapter: ModalityAdapter,
    private val maxRounds: Int,
    private val maxIterations: Int,
    private val hook: AgentHook = NoOpAgentHook,
) : Agent, Steerable {
    private val memory = RoundsBoundedMemory(RepairedMemory(memory), maxRounds, llmProvider)

    /** 当前活跃 run 的 steer 信箱。CAS null→channel 守卫并发 run，null 表示无活跃 run。 */
    private val steerInboxRef = AtomicReference<Channel<AgentQuery>?>(null)

    override fun run(query: AgentQuery): Flow<AgentEvent> = flow {
        loop(query, { req -> llmProvider.chat(req) }, { emit(it) })
    }

    override fun steer(query: AgentQuery): Boolean {
        val inbox = steerInboxRef.get() ?: return false
        return inbox.trySend(query).isSuccess
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
        val steerInbox = Channel<AgentQuery>(Channel.UNLIMITED)
        if (!steerInboxRef.compareAndSet(null, steerInbox)) {
            error("Concurrent run not supported: another run is active")
        }

        val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()
        var iterations = 0

        try {
            emit(AgentEvent.Initial(query))

            memory.attachHook(MemoryCompressProxyHook(hook, emit), buildContext(0))
            memory.add(modalityAdapter.archive(ChatMessage.User(query.parts)))

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
            steerInboxRef.compareAndSet(steerInbox, null)
            steerInbox.close()
        }
    }

    private suspend fun loopOnce(
        iterations: Int,
        toolCalls: MutableList<AgentResult.ToolCallRecord>,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
        steerInbox: Channel<AgentQuery>,
    ): AgentResult? {
        // 检查点①：迭代头注入。Instruct 在此进入 memory，本轮 buildRequest 即生效
        consumeSteering(steerInbox)

        val context = buildContext(iterations)
        val request = buildRequest()

        val finalRequest = hook.safeInvoke { beforeLlmCall(context, request) } ?: request
        val response = llmCallWithContextOverflowHandle(finalRequest, llmCall)
        hook.safeInvoke { afterLlmResponse(context, response) }

        memory.add(response.message)

        if (response.message.toolCalls.isEmpty()) {
            // 检查点②：Final 抢占。用户此刻的纠正不应被一个没看到纠正的回答吞掉
            if (consumeSteering(steerInbox)) {
                // 指令已注入 memory，抢占 Final，强制再跑一轮让 LLM 带着新指令重新作答
                return null
            }
            val result = AgentResult(
                message = response.message,
                iterations = iterations,
                toolCalls = toolCalls.toList(),
                usage = response.usage,
            )
            hook.safeInvoke { onRunCompleted(context, result) }
            emit(AgentEvent.Final(result))
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
                modalityAdapter.archive(
                    ChatMessage.ToolResult(
                        toolCallId = call.id,
                        toolName = call.name,
                        parts = final.parts,
                        isError = final.isError,
                    )
                )
            )

            emit(AgentEvent.ToolCallEnd(call.id, final))
        }
        return null
    }

    /**
     * 消费 steerInbox 中的在途指令，注入为 User 消息。
     * 注入点在迭代头——批次完整点，保证 tool_call/tool_result 配对不被插入消息破坏。
     * @return 是否注入了至少一条指令
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun consumeSteering(steerInbox: Channel<AgentQuery>): Boolean {
        if (steerInbox.isEmpty) return false
        var consumed = false
        while (true) {
            val query = steerInbox.tryReceive().getOrNull() ?: return consumed
            memory.add(modalityAdapter.archive(ChatMessage.User(query.parts)))
            consumed = true
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
        val messages = modalityAdapter.resolve(memory.history())
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
