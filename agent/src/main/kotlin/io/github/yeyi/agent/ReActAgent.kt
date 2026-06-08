package io.github.yeyi.agent

import io.github.yeyi.agent.error.AgentException
import io.github.yeyi.agent.internal.Logging
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.ToolDefinition
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlin.coroutines.coroutineContext

public class ReActAgent internal constructor(
    override val config: AgentConfig
) : Agent {

    override fun run(input: String): Flow<AgentEvent> = run(input, InMemoryMemory())

    override fun run(input: String, memory: Memory): Flow<AgentEvent> = flow {
        runAlgorithm(
            input = input,
            memory = memory,
            llmCall = { req -> config.llmClient.chat(req) },
            emitTextDeltas = false,
            hooks = config.hooks,
            emit = { emit(it) },
        )
    }

    override fun runStream(input: String, memory: Memory): Flow<AgentEvent> = flow {
        runAlgorithm(
            input = input,
            memory = memory,
            llmCall = { req ->
                val accumulatedText = StringBuilder()
                val callOrder: LinkedHashSet<String> = linkedSetOf()
                val callNames: MutableMap<String, String> = mutableMapOf()
                val argumentsBuffers: MutableMap<String, StringBuilder> = mutableMapOf()
                var finishReason: FinishReason? = null

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
                        is StreamEvent.Done -> finishReason = event.finishReason
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
                        name = callNames[id]!!,
                        arguments = parsed
                    )
                }
                ChatResponse(
                    message = ChatMessage.Assistant(
                        content = accumulatedText.toString(),
                        toolCalls = finalCalls,
                    ),
                    usage = null,
                    finishReason = finishReason
                        ?: if (finalCalls.isNotEmpty()) FinishReason.ToolCalls else FinishReason.Stop,
                )
            },
            emitTextDeltas = true,
            hooks = config.hooks,
            emit = { emit(it) },
        )
    }

    private suspend fun runAlgorithm(
        input: String,
        memory: Memory,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        @Suppress("UNUSED_PARAMETER") emitTextDeltas: Boolean,
        hooks: List<AgentHook>,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        memory.add(ChatMessage.User(input))
        val toolCallRecords: MutableList<ToolCallRecord> = mutableListOf()
        var iterations = 0

        try {
            while (iterations < config.maxIterations) {
                iterations++
                coroutineContext.ensureActive()

                val request = buildRequest(memory)
                invokeHooks(hooks) { beforeLlmCall(iterations, request.messages) }
                val response = llmCall(request)
                invokeHooks(hooks) { afterLlmResponse(iterations, response) }
                memory.add(response.message)

                if (response.message.toolCalls.isEmpty()) {
                    val records = toolCallRecords.toList()
                    val result = AgentResult(
                        finalMessage = response.message,
                        iterations = iterations,
                        toolCalls = records
                    )
                    invokeHooks(hooks) { onRunFinished(result) }
                    emit(AgentEvent.Final(response.message, iterations, records))
                    return
                }

                for (call in response.message.toolCalls) {
                    invokeHooks(hooks) { beforeToolCall(call) }
                    emit(AgentEvent.ToolCallStarted(call.id, call.name))
                    val startMs = System.currentTimeMillis()
                    val callResult = invokeTool(call)
                    val durMs = System.currentTimeMillis() - startMs
                    invokeHooks(hooks) { afterToolCall(call, callResult, durMs) }

                    val record = ToolCallRecord(
                        callId = call.id,
                        toolName = call.name,
                        arguments = call.arguments,
                        result = callResult,
                        timestamp = java.time.Instant.now()
                    )
                    toolCallRecords += record
                    memory.add(ChatMessage.ToolResult(
                        toolCallId = call.id, toolName = call.name,
                        content = callResult.content, isError = callResult.isError
                    ))
                    emit(AgentEvent.ToolCallFinished(call.id, callResult))
                    emit(AgentEvent.ToolCallRecorded(record))
                }
            }
            throw AgentException.MaxIterations(config.maxIterations)
        } catch (t: kotlinx.coroutines.CancellationException) {
            throw t
        } catch (t: Throwable) {
            invokeHooks(hooks) { onError(iterations, t) }
            emit(AgentEvent.Failed(t))
        }
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
