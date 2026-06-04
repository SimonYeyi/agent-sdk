package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.error.AgentException
import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatRequest
import io.github.yeyi.agent.core.llm.ToolCall
import io.github.yeyi.agent.core.llm.ToolDefinition
import io.github.yeyi.agent.core.memory.Memory
import io.github.yeyi.agent.core.tool.ToolContext
import io.github.yeyi.agent.core.tool.ToolExecutionResult
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlin.coroutines.coroutineContext

public class ReActAgent internal constructor(
    override val config: AgentConfig
) : Agent {

    override suspend fun run(input: String): AgentResult = run(input, config.memoryFactory())

    override suspend fun run(input: String, memory: Memory): AgentResult {
        memory.add(ChatMessage.User(input))
        val toolCallRecords: MutableList<ToolCallRecord> = mutableListOf()
        var iterations = 0

        while (iterations < config.maxIterations) {
            iterations++
            coroutineContext.ensureActive()

            val request = buildRequest(memory)
            val response = config.llmClient.chat(request)
            memory.add(response.message)

            if (response.message.toolCalls.isEmpty()) {
                return AgentResult(
                    finalMessage = response.message,
                    memory = memory,
                    iterations = iterations,
                    toolCalls = toolCallRecords.toList()
                )
            }
            for (call in response.message.toolCalls) {
                val result = invokeTool(call)
                toolCallRecords += ToolCallRecord(
                    callId = call.id,
                    toolName = call.name,
                    arguments = call.arguments,
                    result = result,
                    timestamp = java.time.Instant.now()
                )
                memory.add(ChatMessage.ToolResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    content = result.content,
                    isError = result.isError
                ))
            }
        }
        throw AgentException.MaxIterations(config.maxIterations)
    }

    override fun runStream(input: String, memory: Memory): Flow<AgentEvent> = flow {
        error("runStream not implemented yet (Task 3.5)")
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
}
