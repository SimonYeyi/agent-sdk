package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.subagent.subagents
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry
import io.github.yeyi.agent.toolset.toolsets

internal interface Beast {
    suspend fun run(task: String, onEvent: suspend (AgentEvent) -> Unit)
}

internal class Ox internal constructor(
    private val llmProvider: LlmProvider,
    private val persona: Persona,
    private val toolRegistry: ToolRegistry?,
    private val skillRegistry: SkillRegistry?,
    private val subagentRegistry: SubagentRegistry?,
    private val toolsetRegistry: ToolsetRegistry?,
    private val maxIterations: Int,
    private val maxRounds: Int,
) : Beast {
    override suspend fun run(task: String, onEvent: suspend (AgentEvent) -> Unit) {
        val inner = agent {
            persona(this@Ox.persona)
            llmProvider(llmProvider)
            memory(InMemoryMemory(), maxRounds)
            toolRegistry?.let { tools(it.all()) }
            toolsetRegistry?.let { toolsets(it) }
            skillRegistry?.let { skills(it) }
            subagentRegistry?.let { subagents(it) }
            maxIterations(maxIterations)
        }
        inner.run(task).collect { onEvent(it) }
    }
}

internal class Horse internal constructor(
    private val llmProvider: LlmProvider,
    private val persona: Persona,
    private val tools: List<Tool> = emptyList(),
    private val maxIterations: Int,
    private val maxRounds: Int,
) : Beast {
    override suspend fun run(task: String, onEvent: suspend (AgentEvent) -> Unit) {
        val inner = agent {
            persona(this@Horse.persona)
            llmProvider(llmProvider)
            memory(InMemoryMemory(), maxRounds)
            tools(tools)
            maxIterations(maxIterations)
        }
        inner.run(task).collect { onEvent(it) }
    }
}
