package io.github.yeyi.agent.config

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.Agent.Shutdownable
import io.github.yeyi.agent.Agent.Steerable
import io.github.yeyi.agent.Agent.Streamable
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.ReActAgent
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.mcp.McpRegistry
import io.github.yeyi.agent.mcp.mcps
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.subagents
import io.github.yeyi.agent.tool.lazy_loading.lazyTools
import io.github.yeyi.agent.toolset.toolsets
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow

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
 * [shutdown] 是终态操作：回收底层资源（MCP 子进程等）、进行中回合以 [AgentEvent.Canceled]
 * 收尾并标记已关闭，之后调用 [run] 抛出 [IllegalStateException]；如需继续使用必须重新 spawn。
 *
 * [cancel] 是可恢复的中断：只打断当前回合，SpawnedAgent 本身仍可继续使用；
 * 在途回合以 [AgentEvent.Canceled] 收尾——收集方按事件分支判断，不再 catch 取消异常。
 */
public class SpawnedAgent internal constructor(
    private val delegate: ReActAgent,
    private val mcpRegistry: McpRegistry? = null
) : Agent, Streamable, Steerable, Agent.Cancelable, Shutdownable {
    /** 关闭原因 —— [checkShutdown] 的 ISE 消息；delegate 的 Canceled 事件自带同文案原因。 */
    private val shutdownMessage = "Agent is shut down"

    // 关闭信号，兼作关闭状态：isCompleted 即"已关闭"，complete 原子且幂等。
    private val shutdownSignal = CompletableDeferred<Unit>()

    private fun checkShutdown() = check(!shutdownSignal.isCompleted) { shutdownMessage }

    override fun run(query: AgentQuery): Flow<AgentEvent> {
        // 已关闭的 Agent 不可再运行——直接抛非法状态异常，而非返回 Failed 事件。
        checkShutdown()
        // 直接透传 delegate 的流：delegate.run() 启动即执行（eager 占据活跃槽位），
        // run 返回后尚未订阅的间隙里 steer() 不得误判空闲（否则调用方会按 KDoc
        // 模式触发并发 fallback run）。shutdown 后的拒绝由 delegate 的 shutdownSignal
        // 检查承担。
        return delegate.run(query)
    }

    override fun runStream(query: AgentQuery): Flow<AgentEvent> {
        checkShutdown()
        return delegate.runStream(query)
    }

    override fun steer(query: AgentQuery): Boolean {
        // 已关闭的 Agent 不可再注入指令——直接抛 ISE，遵循统一终态契约。
        checkShutdown()
        return delegate.steer(query)
    }

    /**
     * 打断当前回合 —— 委托给底层 agent，SpawnedAgent 本身仍可继续使用。
     *
     * @param cause 打断原因（诊断用途）—— 原样转交给底层 agent，
     *   在途回合以 [AgentEvent.Canceled] 收尾，原因由事件载荷携带。
     *
     * @return 底层 agent 的收尾句柄 —— 语义与 [Agent.Cancelable.cancel] 一致。
     */
    override fun cancel(cause: String?): Job? {
        checkShutdown()
        return delegate.cancel(cause)
    }

    /** 永久关闭 Agent 并回收资源（MCP 子进程等）。幂等，可重复调用。 */
    override fun shutdown() {
        if (!shutdownSignal.complete(Unit)) return
        // delegate.shutdown()：scope.cancel() 让活跃回合以 NonCancellable 发完
        // Canceled 后退场 —— 先等执行域终止、再拆 MCP，杜绝「在途工具调用拿到
        // 已死 client」的竞态（cancel 只投递、收尾异步，立即 unregisterAll 会踩进窗口）。
        delegate.shutdown()
        mcpRegistry?.unregisterAll()
    }
}
