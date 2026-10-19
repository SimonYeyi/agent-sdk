package io.github.yeyi.agent.config

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.Shutdownable
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.mcp.McpRegistry
import io.github.yeyi.agent.mcp.mcps
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.subagents
import io.github.yeyi.agent.tool.lazy_loading.lazyTools
import io.github.yeyi.agent.toolset.toolsets
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 从 AgentDefinition 和 AgentScope 构建 Agent 实例。
 *
 * @param scope 运行时上下文
 * @return [SpawnedAgent]，可通过 [Shutdownable.shutdown] 永久关闭并回收资源
 */
public fun AgentDefinition.spawn(scope: AgentScope): SpawnedAgent {
    val resolveInfo = scope.resolver().resolve(this@spawn)
    val delegate = agent {
        persona(Persona(persona))
        llmProvider(resolveInfo.llmProvider)
        memory(scope.memory, maxMemoryRounds)
        maxIterations(maxIterations)
        resolveInfo.toolRegistry?.let { tools(it) }
        resolveInfo.lazyToolRegistry?.let { lazyTools(it) }
        // toolsets 与 mcps 的互斥已在 AgentResolver.resolve() 中处理：mcps 非空时 toolsetRegistry 置 null，此处调用天然互斥
        resolveInfo.toolsetRegistry?.let { toolsets(it) }
        resolveInfo.skillRegistry?.let { skills(it) }
        resolveInfo.subagentRegistry?.let { subagents(it) }
        resolveInfo.mcpRegistry?.let { mcps(it) }
        scope.hook?.let { hook(it) }
        scope.modalityAdapter?.let { modalityAdapter(it) }
    }
    return SpawnedAgent(delegate, resolveInfo.mcpRegistry)
}

/**
 * spawn 产物：实现 [Agent] 的委托包装，并实现 [Shutdownable] 提供整体关闭。
 *
 * [shutdown] 是终态操作：回收底层资源（MCP 子进程等）并标记已关闭，
 * 之后 [run] 返回 [AgentEvent.Failed]；如需继续使用必须重新 spawn。
 */
public class SpawnedAgent internal constructor(
    private val delegate: Agent,
    private val mcpRegistry: McpRegistry? = null
) : Agent by delegate, Shutdownable {
    private val closed = AtomicBoolean(false)

    override fun run(query: AgentQuery): Flow<AgentEvent> {
        if (closed.get()) {
            return flow { emit(AgentEvent.Failed(IllegalStateException("Agent is shut down"))) }
        }
        return delegate.run(query)
    }

    /** 永久关闭 Agent 并回收资源（MCP 子进程等）。幂等，可重复调用。 */
    override fun shutdown() {
        if (!closed.compareAndSet(false, true)) return
        mcpRegistry?.unregisterAll()
    }
}
