package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
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
import kotlinx.coroutines.flow.Flow

/**
 * 专精 Worker Agent（内部抽象）。
 *
 * 与 [BossAgent] 正交：Boss 负责编排，Beast 负责执行单个任务。本质契约与
 * [Agent] 相同（run → Flow[AgentEvent]），实现上按 selection 组装独立的
 * 内部 ReActAgent 后透传其事件流。
 */
internal interface Beast : Agent

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
    override fun run(query: AgentQuery): Flow<AgentEvent> {
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
        return inner.run(query)
    }
}

internal class Horse internal constructor(
    private val llmProvider: LlmProvider,
    private val persona: Persona,
    private val tools: List<Tool> = emptyList(),
    private val maxIterations: Int,
    private val maxRounds: Int,
) : Beast {
    override fun run(query: AgentQuery): Flow<AgentEvent> {
        val inner = agent {
            persona(this@Horse.persona)
            llmProvider(llmProvider)
            memory(InMemoryMemory(), maxRounds)
            tools(tools)
            maxIterations(maxIterations)
        }
        return inner.run(query)
    }
}
