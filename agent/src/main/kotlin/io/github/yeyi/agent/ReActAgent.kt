package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

public class ReActAgent internal constructor(
    private val systemPrompt: String,
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry,
    private val memory: Memory,
    private val maxIterations: Int,
    private val hook: AgentHook = NoOpAgentHook,
) : Agent {

    override fun run(input: String): Flow<AgentEvent> = flow {
        loop(
            input = input,
            llmCall = { req -> llmProvider.chat(req) },
            emit = { emit(it) },
        )
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
        }
        loop(
            input = input,
            llmCall = llmCall,
            emit = { emit(it) },
        )
    }

    private suspend fun loop(
        input: String,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        try {
            memory.add(ChatMessage.User(input))
            emit(AgentEvent.Initial(input))

            val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()
            var iterations = 0

            while (iterations < maxIterations) {
                iterations++

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

                response.message.content?.takeIf { it.isNotBlank() }?.let {
                    emit(AgentEvent.Reasoning(it))
                }

                for (call in response.message.toolCalls) {
                    val synthetic = hook.safeInvoke { beforeToolCall(call) }
                    if (synthetic != null) {
                        // 工具被 hook 短路:跳过实际执行,synthetic result 写进 memory,
                        // **不** emit ToolCallStarted / ToolCallFinished(工具压根没被调用)。
                        recordToMemory(call, synthetic.copy(isError = true), toolCalls)
                    } else {
                        emit(AgentEvent.ToolCallStart(call.id, call.name))
                        val startMs = System.currentTimeMillis()
                        val raw = toolRegistry.execute(call, ToolContext())
                        val durMs = System.currentTimeMillis() - startMs
                        val final = hook.safeInvoke { afterToolCall(call, raw, durMs) } ?: raw
                        recordToMemory(call, final, toolCalls)
                        emit(AgentEvent.ToolCallEnd(call.id, final))
                    }
                }
            }
            throw AgentException.MaxIterations(maxIterations)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // 边界处统一抬升为 AgentException:对外只暴露领域异常家族。
            // wrap() 对已是 AgentException 的返回同一实例,避免重复包装。
            val cause = t.toAgentException()
            hook.safeInvoke { onError(cause) }
            emit(AgentEvent.Failed(cause))
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