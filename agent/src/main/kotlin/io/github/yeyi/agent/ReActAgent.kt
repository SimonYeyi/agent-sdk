package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmClient
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.coroutineContext

public class ReActAgent internal constructor(
    internal val systemPrompt: String,
    internal val llmClient: LlmClient,
    internal val toolRegistry: ToolRegistry,
    internal val memory: Memory,
    internal val maxIterations: Int,
    internal val hook: AgentHook = NoOpAgentHook,
) : Agent {

    override fun run(input: String): Flow<AgentEvent> = flow {
        loop(
            input = input,
            llmCall = { req -> llmClient.chat(req) },
            emit = { emit(it) },
        )
    }

    override fun runStream(input: String): Flow<AgentEvent> = flow {
        loop(
            input = input,
            llmCall = { req ->
                val accumulatedText = StringBuilder()
                val callOrder: LinkedHashSet<String> = linkedSetOf()
                val callNames: MutableMap<String, String> = mutableMapOf()
                val argumentsBuffers: MutableMap<String, StringBuilder> = mutableMapOf()
                var finishReason: FinishReason? = null
                var usage: Usage? = null

                llmClient.chatStream(req).collect { event ->
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
                            val id = event.id ?: return@collect
                            argumentsBuffers[id]?.append(event.argumentsDelta)
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
                    ToolCall(
                        id = id,
                        name = callNames[id]!!,
                        arguments = arguments
                    )
                }
                ChatResponse(
                    message = ChatMessage.Assistant(
                        content = accumulatedText.toString(),
                        toolCalls = toolCalls,
                    ),
                    usage = usage,
                    finishReason = finishReason!!
                )
            },
            emit = { emit(it) },
        )
    }

    private suspend fun loop(
        input: String,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        memory.add(ChatMessage.User(input))
        val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()
        var iterations = 0

        try {
            while (iterations < maxIterations) {
                iterations++
                coroutineContext.ensureActive()

                val request = buildRequest()
                hook.safeInvoke { beforeLlmCall(iterations, request.messages) }
                val response = llmCall(request)
                hook.safeInvoke { afterLlmResponse(iterations, response) }
                memory.add(response.message)

                if (response.message.toolCalls.isEmpty()) {
                    val result = AgentResult(
                        message = response.message,
                        iterations = iterations,
                        toolCalls = toolCalls.toList(),
                        usage = response.usage,
                    )
                    hook.safeInvoke { onRunFinished(result) }
                    emit(AgentEvent.Final(result))
                    return
                }

                for (call in response.message.toolCalls) {
                    val synthetic = hook.safeInvoke { beforeToolCall(call) }
                    if (synthetic != null) {
                        // 工具被 hook 短路:跳过实际执行,synthetic result 写进 memory,
                        // **不** emit ToolCallStarted / ToolCallFinished(工具压根没被调用)。
                        recordToMemory(call, synthetic.copy(isError = true), toolCalls)
                    } else {
                        emit(AgentEvent.ToolCallStarted(call.id, call.name))
                        val startMs = System.currentTimeMillis()
                        val raw = toolRegistry.execute(call, ToolContext())
                        val durMs = System.currentTimeMillis() - startMs
                        val final = hook.safeInvoke { afterToolCall(call, raw, durMs) } ?: raw
                        recordToMemory(call, final, toolCalls)
                        emit(AgentEvent.ToolCallFinished(call.id, final))
                    }
                }
            }
            throw AgentException.MaxIterations(maxIterations)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            hook.safeInvoke { onError(iterations, t) }
            emit(AgentEvent.Failed(t))
        }
    }

    private suspend fun recordToMemory(
        call: ToolCall,
        callResult: ToolExecutionResult,
        toolCalls: MutableList<AgentResult.ToolCallRecord>,
    ) {
        toolCalls += AgentResult.ToolCallRecord(
            callId = call.id,
            toolName = call.name,
            arguments = call.arguments,
            result = callResult,
            timestamp = java.time.Instant.now(),
        )
        memory.add(
            ChatMessage.ToolResult(
                toolCallId = call.id, toolName = call.name,
                content = callResult.content, isError = callResult.isError,
            )
        )
    }

    private suspend fun buildRequest(): ChatRequest = ChatRequest(
        messages = buildList {
            if (systemPrompt.isNotBlank()) add(ChatMessage.System(systemPrompt))
            addAll(memory.history())
        },
        tools = toolRegistry.definitions()
    )
}