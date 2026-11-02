package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.subagent.subagents
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.lazyTools
import io.github.yeyi.agent.toolset.ToolsetRegistry
import io.github.yeyi.agent.toolset.toolsets
import kotlinx.coroutines.flow.Flow

/**
 * 专精 Worker Agent（内部抽象）——一次性 agent：每次 dispatch 新建，任务终局（含取消）即作废。
 *
 * 与 [BossAgent] 正交：Boss 负责编排，Beast 负责执行单个任务。实现上按 selection 组装独立的
 * 内部 ReActAgent 并委托（[run] 纯透传，不附加隐藏行为——保持 Agent 契约「取消订阅不终止回合」）。
 *
 * 生命周期钉死在订阅处：[Pasture.dispatch] 在收集结束的 finally 中调用 [shutdown]
 * （覆盖完成/异常/取消三条路径）。**不提供 [Agent.Cancelable]** —— Beast 的用途就是
 * 一回合消亡，整体作废即全部语义。运行中的回合是 agent 私有域里的协程：不 shutdown，
 * 在途回合会在无观察者的情况下继续烧 token 到自然终局。
 */
internal interface Beast : Agent, Agent.Shutdownable

internal class Ox internal constructor(
    private val llmProvider: LlmProvider,
    private val persona: AgentPersona,
    private val toolRegistry: ToolRegistry?,
    private val lazyToolRegistry: LazyToolRegistry?,
    private val toolsetRegistry: ToolsetRegistry?,
    private val skillRegistry: SkillRegistry?,
    private val subagentRegistry: SubagentRegistry?,
    private val maxIterations: Int,
    private val maxRounds: Int,
) : Beast {
    private val inner: Agent = agent {
        persona(this@Ox.persona)
        llmProvider(llmProvider)
        memory(InMemoryMemory(), maxRounds)
        toolRegistry?.let { tools(it) }
        lazyToolRegistry?.let { lazyTools(it) }
        toolsetRegistry?.let { toolsets(it) }
        skillRegistry?.let { skills(it) }
        subagentRegistry?.let { subagents(it) }
        maxIterations(maxIterations)
    }

    override fun run(query: AgentQuery): Flow<AgentEvent> = inner.run(query)

    override fun shutdown() = (inner as? Agent.Shutdownable)?.shutdown() ?: Unit
}

internal class Horse internal constructor(
    private val llmProvider: LlmProvider,
    private val persona: AgentPersona,
    private val toolRegistry: ToolRegistry? = null,
    private val lazyToolRegistry: LazyToolRegistry? = null,
    private val toolsetRegistry: ToolsetRegistry? = null,
    private val maxIterations: Int,
    private val maxRounds: Int,
) : Beast {
    private val inner: Agent = agent {
        persona(this@Horse.persona)
        llmProvider(llmProvider)
        memory(InMemoryMemory(), maxRounds)
        toolRegistry?.let { tools(it) }
        lazyToolRegistry?.let { lazyTools(it) }
        toolsetRegistry?.let { toolsets(it) }
        maxIterations(maxIterations)
    }

    override fun run(query: AgentQuery): Flow<AgentEvent> = inner.run(query)

    override fun shutdown() = (inner as? Agent.Shutdownable)?.shutdown() ?: Unit
}
