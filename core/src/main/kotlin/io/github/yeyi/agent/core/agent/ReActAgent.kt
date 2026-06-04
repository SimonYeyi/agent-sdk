package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.error.AgentException
import io.github.yeyi.agent.core.internal.Logging
import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatRequest
import io.github.yeyi.agent.core.llm.StreamEvent
import io.github.yeyi.agent.core.llm.ToolCall
import io.github.yeyi.agent.core.llm.ToolDefinition
import io.github.yeyi.agent.core.memory.Memory
import io.github.yeyi.agent.core.tool.ToolContext
import io.github.yeyi.agent.core.tool.ToolExecutionResult
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.coroutineContext

public class ReActAgent internal constructor(
    override val config: AgentConfig
) : Agent {

    override suspend fun run(input: String): AgentResult = run(input, config.memoryFactory())

    override suspend fun run(input: String, memory: Memory): AgentResult {
        memory.add(ChatMessage.User(input))
        val toolCallRecords: MutableList<ToolCallRecord> = mutableListOf()
        var iterations = 0

        try {
            while (iterations < config.maxIterations) {
                iterations++
                coroutineContext.ensureActive()

                val request = buildRequest(memory)
                invokeHooks { beforeLlmCall(iterations, request.messages) }
                val response = config.llmClient.chat(request)
                invokeHooks { afterLlmResponse(iterations, response) }
                memory.add(response.message)

                if (response.message.toolCalls.isEmpty()) {
                    val result = AgentResult(
                        finalMessage = response.message,
                        memory = memory,
                        iterations = iterations,
                        toolCalls = toolCallRecords.toList()
                    )
                    invokeHooks { onRunFinished(result) }
                    return result
                }

                for (call in response.message.toolCalls) {
                    invokeHooks { beforeToolCall(call) }
                    val startMs = System.currentTimeMillis()
                    val callResult = invokeTool(call)
                    val durMs = System.currentTimeMillis() - startMs
                    invokeHooks { afterToolCall(call, callResult, durMs) }

                    toolCallRecords += ToolCallRecord(
                        callId = call.id,
                        toolName = call.name,
                        arguments = call.arguments,
                        result = callResult,
                        timestamp = java.time.Instant.now()
                    )
                    memory.add(ChatMessage.ToolResult(
                        toolCallId = call.id, toolName = call.name,
                        content = callResult.content, isError = callResult.isError
                    ))
                }
            }
            val maxEx = AgentException.MaxIterations(config.maxIterations)
            throw maxEx
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            invokeHooks { onError(iterations, t) }
            throw t
        }
    }

    override fun runStream(input: String, memory: Memory): Flow<AgentEvent> = flow {
        memory.add(ChatMessage.User(input))
        var iterations = 0

        while (iterations < config.maxIterations) {
            iterations++
            coroutineContext.ensureActive()

            val request = buildRequest(memory)
            val accumulatedText = StringBuilder()
            val callOrder: LinkedHashSet<String> = linkedSetOf()
            val callNames: MutableMap<String, String> = mutableMapOf()
            val argumentsBuffers: MutableMap<String, StringBuilder> = mutableMapOf()

            config.llmClient.chatStream(request).collect { event ->
                when (event) {
                    is StreamEvent.ContentDelta -> {
                        accumulatedText.append(event.text)
                        emit(AgentEvent.TextDelta(event.text))
                    }
                    is StreamEvent.ToolCallDelta -> {
                        val id = event.id
                        if (id != null) {
                            callOrder.add(id) // LinkedHashSet: idempotent + preserves first-seen order
                            if (event.name != null) callNames[id] = event.name
                            argumentsBuffers.getOrPut(id) { StringBuilder() }.append(event.argumentsDelta)
                        }
                    }
                    is StreamEvent.Done -> Unit
                    is StreamEvent.Error -> throw event.cause
                }
            }

            val finalCalls: List<ToolCall> = callOrder.map { id ->
                val argsText = argumentsBuffers[id]?.toString().orEmpty()
                val parsed = if (argsText.isNotBlank())
                    Json.parseToJsonElement(argsText)
                else JsonNull
                ToolCall(
                    id = id,
                    name = callNames[id] ?: error("ToolCallDelta lacked a name for id=$id"),
                    arguments = parsed
                )
            }
            val assistantMsg = ChatMessage.Assistant(accumulatedText.toString(), finalCalls)
            memory.add(assistantMsg)

            if (finalCalls.isEmpty()) {
                emit(AgentEvent.Final(assistantMsg))
                return@flow
            }

            for (call in finalCalls) {
                emit(AgentEvent.ToolCallStarted(call.id, call.name))
                val result = invokeTool(call)
                emit(AgentEvent.ToolCallFinished(call.id, result))
                memory.add(ChatMessage.ToolResult(
                    toolCallId = call.id, toolName = call.name,
                    content = result.content, isError = result.isError
                ))
            }
        }
        throw AgentException.MaxIterations(config.maxIterations)
    }

    private suspend fun invokeTool(call: ToolCall): ToolExecutionResult {
        val tool = config.tools.find { it.name == call.name }
            ?: return ToolExecutionResult(
                content = "Tool '${call.name}' not found. Available: ${config.tools.joinToString { it.name }}",
                isError = true
            )
        return try {
            tool.execute(call.arguments, ToolContext())
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            ToolExecutionResult(
                content = "Tool error: ${t.message}",
                isError = true
            )
        }
    }

    private suspend fun buildRequest(memory: Memory): ChatRequest = ChatRequest(
        messages = buildList {
            if (config.systemPrompt.isNotBlank()) add(ChatMessage.System(config.systemPrompt))
            addAll(memory.history())
        },
        tools = config.tools.map { ToolDefinition(it.name, it.description, it.parametersSchema) }
    )

    private suspend inline fun invokeHooks(crossinline action: suspend AgentHook.() -> Unit) {
        for (h in config.hooks) {
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
