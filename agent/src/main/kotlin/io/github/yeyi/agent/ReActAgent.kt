package io.github.yeyi.agent

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.internal.Logging
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.tool.ToolContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.coroutineContext

public class ReActAgent internal constructor(
    override val config: AgentConfig
) : Agent {

    override fun run(input: String): Flow<AgentEvent> = flow {
        loop(
            input = input,
            llmCall = { req -> config.llmClient.chat(req) },
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

                config.llmClient.chatStream(req).collect { event ->
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

                val finalCalls: List<ToolCall> = callOrder.map { id ->
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
                        toolCalls = finalCalls,
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
        config.memory.add(ChatMessage.User(input))
        val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()
        var iterations = 0

        try {
            while (iterations < config.maxIterations) {
                iterations++
                coroutineContext.ensureActive()

                val request = buildRequest()
                invokeHooks(config.hooks) { beforeLlmCall(iterations, request.messages) }
                val response = llmCall(request)
                invokeHooks(config.hooks) { afterLlmResponse(iterations, response) }
                config.memory.add(response.message)

                if (response.message.toolCalls.isEmpty()) {
                    val result = AgentResult(
                        message = response.message,
                        iterations = iterations,
                        toolCalls = toolCalls.toList(),
                        usage = response.usage,
                    )
                    invokeHooks(config.hooks) { onRunFinished(result) }
                    emit(AgentEvent.Final(result))
                    return
                }

                for (call in response.message.toolCalls) {
                    invokeHooks(config.hooks) { beforeToolCall(call) }
                    emit(AgentEvent.ToolCallStarted(call.id, call.name))
                    val startMs = System.currentTimeMillis()
                    val callResult = config.tools.execute(call, ToolContext())
                    val durMs = System.currentTimeMillis() - startMs
                    invokeHooks(config.hooks) { afterToolCall(call, callResult, durMs) }

                    val record = AgentResult.ToolCallRecord(
                        callId = call.id,
                        toolName = call.name,
                        arguments = call.arguments,
                        result = callResult,
                        timestamp = java.time.Instant.now()
                    )
                    toolCalls += record
                    config.memory.add(
                        ChatMessage.ToolResult(
                            toolCallId = call.id, toolName = call.name,
                            content = callResult.content, isError = callResult.isError
                        )
                    )
                    emit(AgentEvent.ToolCallFinished(call.id, callResult))
                }
            }
            throw AgentException.MaxIterations(config.maxIterations)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            invokeHooks(config.hooks) { onError(iterations, t) }
            emit(AgentEvent.Failed(t))
        }
    }

    private suspend fun buildRequest(): ChatRequest = ChatRequest(
        messages = buildList {
            if (config.systemPrompt.isNotBlank()) add(ChatMessage.System(config.systemPrompt))
            addAll(config.memory.history())
        },
        tools = config.tools.definitions()
    )

    private suspend inline fun invokeHooks(
        hooks: List<AgentHook>,
        crossinline action: suspend AgentHook.() -> Unit
    ) {
        for (h in hooks) {
            try {
                h.action()
            } catch (t: kotlinx.coroutines.CancellationException) {
                throw t
            } catch (t: Throwable) {
                Logging.warn("AgentHook", "Hook ${h::class.simpleName} threw: ${t.message}")
            }
        }
    }
}
