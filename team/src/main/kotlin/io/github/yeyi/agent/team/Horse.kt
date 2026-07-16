package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.Tool

internal class Horse internal constructor(
    private val llmProvider: LlmProvider,
    private val persona: Persona,
    private val tools: List<Tool> = emptyList(),
    private val maxIterations: Int,
    private val maxRounds: Int,
) : Beast {
    override suspend fun run(task: String, onEvent: suspend (AgentEvent) -> Unit) {
        val p = persona
        val inner = agent {
            persona(p)
            llmProvider(llmProvider)
            memory(InMemoryMemory(), maxRounds)
            tools(tools)
            maxIterations(maxIterations)
        }
        inner.run(task).collect { onEvent(it) }
    }
}