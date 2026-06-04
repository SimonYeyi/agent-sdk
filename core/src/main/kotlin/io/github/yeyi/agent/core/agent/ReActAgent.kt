package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.error.AgentException
import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatRequest
import io.github.yeyi.agent.core.llm.ToolDefinition
import io.github.yeyi.agent.core.memory.Memory
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
            error("Tool calls not implemented yet (Task 3.3)")
        }
        throw AgentException.MaxIterations(config.maxIterations)
    }

    override fun runStream(input: String, memory: Memory): Flow<AgentEvent> = flow {
        error("runStream not implemented yet (Task 3.5)")
    }

    private suspend fun buildRequest(memory: Memory): ChatRequest = ChatRequest(
        messages = buildList {
            if (config.systemPrompt.isNotBlank()) add(ChatMessage.System(config.systemPrompt))
            addAll(memory.history())
        },
        tools = config.tools.map { ToolDefinition(it.name, it.description, it.parametersSchema) }
    )
}
