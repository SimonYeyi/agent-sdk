package io.github.yeyi.agent.config

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.Agent.Shutdownable
import io.github.yeyi.agent.Agent.Steerable
import io.github.yeyi.agent.Agent.Streamable
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.ReActAgent
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.mcp.McpRegistry
import io.github.yeyi.agent.mcp.mcps
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.subagents
import io.github.yeyi.agent.tool.lazy_loading.lazyTools
import io.github.yeyi.agent.toolset.toolsets

/**
 * 从 AgentDefinition 和 AgentScope 构建 Agent 实例。
 *
 * @param scope 运行时上下文
 * @return [SpawnedAgent]，可通过 [Shutdownable.shutdown] 永久关闭并回收资源
 */
public fun AgentDefinition.spawn(scope: AgentScope): SpawnedAgent {
    val resolveInfo = scope.resolver().resolve(this@spawn)
    val delegate = agent {
        persona(AgentPersona(persona ?: "").apply { instructions?.let { instructions(it) } })
        llmProvider(resolveInfo.llmProvider)
        memory(scope.memory, maxMemoryRounds)
        maxIterations(maxIterations)
        resolveInfo.toolRegistry?.let { tools(it) }
        resolveInfo.lazyToolRegistry?.let { lazyTools(it) }
        resolveInfo.toolsetRegistry?.let { toolsets(it) }
        resolveInfo.skillRegistry?.let { skills(it) }
        resolveInfo.subagentRegistry?.let { subagents(it) }
        resolveInfo.mcpRegistry?.let { mcps(it) }
        scope.hook?.let { hook(it) }
        scope.modalityAdapter?.let { modalityAdapter(it) }
    } as ReActAgent
    return SpawnedAgent(delegate, resolveInfo.mcpRegistry)
}

/**
 * spawn 产物：实现 [Agent] 的委托包装，并实现 [Shutdownable] 提供整体关闭。
 *
 * 本类只承担 shutdown 的额外职责（反注册 MCP 子进程资源），run / runStream /
 * steer / cancel 通过 `by` 委托底层 [ReActAgent]，行为与其原生语义完全一致：
 * shutdown 后 [run] / [runStream] 因 delegate 的 runTurn 守卫抛 [IllegalStateException]，
 * [steer] 返回 false、[cancel] 返回 null。
 *
 * [shutdown] 是终态操作：回收底层资源（MCP 子进程等）、进行中回合以 [AgentEvent.Canceled]
 * 收尾并标记已关闭，之后调用 [run] 抛出 [IllegalStateException]；如需继续使用必须重新 spawn。
 * 幂等性由 delegate.shutdown()（scope.cancel 幂等）与 [McpRegistry.unregisterAll]
 * （一次性守卫）共同保证。
 *
 * [cancel] 是可恢复的中断：只打断当前回合，SpawnedAgent 本身仍可继续使用；
 * 在途回合以 [AgentEvent.Canceled] 收尾——收集方按事件分支判断，不再 catch 取消异常。
 */
public class SpawnedAgent internal constructor(
    private val delegate: ReActAgent,
    private val mcpRegistry: McpRegistry? = null
) : Agent by delegate, Streamable by delegate, Steerable by delegate, Agent.Cancelable by delegate,
    Shutdownable {

    /** 永久关闭 Agent 并回收资源（MCP 子进程等）。幂等，可重复调用。 */
    override fun shutdown() {
        // delegate.shutdown()：scope.cancel() 是投递语义 —— 立即返回，活跃回合在 agent
        // scope 里异步收尾（NonCancellable 发完 Canceled）。这里不等它，紧跟的
        // unregisterAll() 是安全的：它只同步从 registry 删名（纯 Map 操作），在途工具
        // 调用手里已持有 client 引用，不受影响；真正的连接关闭在后台协程里执行且会跑完
        // （runCatching 吞异常、子进程被 destroy），无资源泄漏。且 scope.cancel 之后
        // 下游挂起点立即抛取消，在途调用基本走不到「检查连接已关」那一步。
        // 若将来 MCP 客户端改成「拆卸时同步中断在途请求」，此处的先后顺序需要重新审视。
        delegate.shutdown()
        mcpRegistry?.unregisterAll()
    }
}
