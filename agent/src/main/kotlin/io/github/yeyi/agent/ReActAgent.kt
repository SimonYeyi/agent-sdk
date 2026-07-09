package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.log.log
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.memory.ReadOnlyMemory
import io.github.yeyi.agent.memory.RoundsBoundedMemory
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

public class ReActAgent internal constructor(
    private val persona: Persona,
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry,
    memory: Memory,
    private val maxRounds: Int,
    private val maxIterations: Int,
    private val hook: AgentHook = NoOpAgentHook,
) : Agent {
    private val memory = RoundsBoundedMemory(memory, maxRounds, llmProvider)

    override fun run(input: String): Flow<AgentEvent> = flow {
        loop(input, { req -> llmProvider.chat(req) }, { emit(it) })
    }

    override fun runStream(input: String): Flow<AgentEvent> = flow {
        val llmCall: suspend (ChatRequest) -> ChatResponse = { req ->
            val accumulatedText = StringBuilder()
            val callOrder: LinkedHashSet<String> = linkedSetOf()
            val callNames: MutableMap<String, String> = mutableMapOf()
            val argumentsBuffers: MutableMap<String, StringBuilder> = mutableMapOf()
            var finishReason: FinishReason? = null
            var usage: Usage? = null

            llmProvider.chatStream(req).collect { event ->
                when (event) {
                    is StreamEvent.ContentDelta -> {
                        accumulatedText.append(event.text)
                        emit(AgentEvent.TextDelta(event.text))
                    }

                    is StreamEvent.ToolCallStart -> {
                        callOrder.add(event.id) // LinkedHashSet: idempotent + preserves first-seen order
                        callNames[event.id] = event.name
                        argumentsBuffers.getOrPut(event.id) { StringBuilder() }
                    }

                    is StreamEvent.ToolCallDelta -> {
                        // LlmProvider 契约:Delta.id 必非空(continuation chunk 由 provider 填充)。
                        // 若违反,静默丢弃会导致 arguments JSON 损坏,fail-fast 更安全。
                        argumentsBuffers[event.id!!]?.append(event.argumentsDelta)
                    }

                    is StreamEvent.Done -> {
                        finishReason = event.finishReason
                        usage = event.usage
                    }

                    is StreamEvent.Error -> throw event.cause
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
        loop(input = input, llmCall = llmCall, emit = { emit(it) })
    }

    private suspend fun loop(
        input: String,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit
    ) {
        val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()
        var iterations = 0

        try {
            emit(AgentEvent.Initial(input))

            memory.attachHook(ProxyHook(hook, emit), buildContext(0))
            memory.add(ChatMessage.User(input))

            while (iterations < maxIterations) {
                loopOnce(++iterations, toolCalls, llmCall, emit)?.let { return }
            }

            throw AgentException.MaxIterations(maxIterations)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // 边界处统一抬升为 AgentException:对外只暴露领域异常家族。
            // wrap() 对已是 AgentException 的返回同一实例,避免重复包装。
            val cause = t.toAgentException()
            hook.safeInvoke { onRunFailed(buildContext(iterations), cause) }
            emit(AgentEvent.Failed(cause))
        }
    }

    private suspend fun loopOnce(
        iterations: Int,
        toolCalls: MutableList<AgentResult.ToolCallRecord>,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit
    ): AgentResult? {
        val context = buildContext(iterations)
        val request = buildRequest()

        hook.safeInvoke { beforeLlmCall(context) }
        val response = llmCallWithContextOverflowHandle(request, llmCall)
        hook.safeInvoke { afterLlmResponse(context, response) }

        memory.add(response.message)

        if (response.message.toolCalls.isEmpty()) {
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

        emit(AgentEvent.ToolCallExplanation(response.message.content?.takeIf { it != "" }))

        for (call in response.message.toolCalls) {
            emit(AgentEvent.ToolCallStart(call.id, call.name))
            val synthetic = hook.safeInvoke { beforeToolCall(context, call) }
            val startMs = System.currentTimeMillis()
            val raw = synthetic?.copy(isError = true) ?: toolRegistry.dispatch(
                call.name,
                call.arguments,
                ToolContext(call.id, context)
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
                ChatMessage.ToolResult(
                    toolCallId = call.id, toolName = call.name,
                    content = final.content, isError = final.isError,
                )
            )

            emit(AgentEvent.ToolCallEnd(call.id, final))
        }
        return null
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

    private suspend fun buildRequest(): ChatRequest = ChatRequest(
        messages = buildList {
            add(ChatMessage.System(persona.toString()))
            addAll(memory.history())
        },
        tools = toolRegistry.definitions()
    )

    private fun buildContext(currentIteration: Int) = AgentContext(
        persona = persona,
        maxIterations = maxIterations,
        currentIteration = currentIteration,
        memory = ReadOnlyMemory(memory),
        llmProvider = llmProvider,
        tools = toolRegistry.all(),
        maxRounds = maxRounds,
    )

    private class ProxyHook(
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
