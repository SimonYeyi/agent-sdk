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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow

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
 * [shutdown] 是终态操作：回收底层资源（MCP 子进程等）、终止进行中的 [run] flow（收集以 [CancellationException]
 * 结束）并标记已关闭，之后调用 [run] 抛出 [IllegalStateException]；如需继续使用必须重新 spawn。
 *
 * [cancel] 是可恢复的中断：只打断当前回合，SpawnedAgent 本身仍可继续使用；
 * 在途回合的收集以 [CancellationException] 结束——沿用 collect 外围 try/catch 取消异常的既有用法。
 */
public class SpawnedAgent internal constructor(
    private val delegate: ReActAgent,
    private val mcpRegistry: McpRegistry? = null
) : Agent, Streamable, Steerable, Agent.Cancelable, Shutdownable {
    // 关闭信号，兼作关闭状态：isCompleted 即"已关闭"，complete 原子且幂等。
    private val shutdownSignal = CompletableDeferred<Unit>()

    private fun checkShutdown() = check(!shutdownSignal.isCompleted) { "Agent is shut down" }

    override fun run(query: AgentQuery): Flow<AgentEvent> = runDelegate { delegate.run(query) }

    override fun runStream(query: AgentQuery): Flow<AgentEvent> =
        runDelegate { delegate.runStream(query) }

    private fun runDelegate(createDelegateFlow: () -> Flow<AgentEvent>): Flow<AgentEvent> {
        // 已关闭的 Agent 不可再运行——直接抛非法状态异常，而非返回 Failed 事件。
        checkShutdown()
        // 入口即创建 delegate flow：delegate.run()/runStream() 内部的 "run 即占槽"
        // 急切占据活跃 run 槽位，与 ReActAgent "run 即占槽" 语义对齐——run 返回后尚未收集的
        // 间隙里 steer() 不得误判空闲（否则调用方会按 KDoc 模式触发并发 fallback run）。
        val delegateFlow = createDelegateFlow()
        // channelFlow：delegate 在独立子协程中收集转发，send 可跨协程（flow 的 emit 违反不变式）。
        return channelFlow {
            // 守卫：shutdown 已在 collect 前发生——不再启动 delegate（避免踩到已回收的 MCP 注册表）。
            // 以取消异常收尾，而非"零事件 + 正常结束"的静默出口。
            runCatching { checkShutdown() }.getOrElse { throw CancellationException(it.message) }
            // delegate 被 cancel()/shutdown 打断时，取消异常在此分流：
            // 关闭（终态）→ 以取消异常收尾，收集方能明确知道这一轮被终止；
            // 打断（可恢复）→ 取消异常原样上抛，沿用 collect 外围 try/catch 的既有用法。
            try {
                delegateFlow.collect { send(it) }
            } catch (cause: CancellationException) {
                runCatching { checkShutdown() }.getOrElse { throw CancellationException(it.message) }
                throw cause
            }
        }
    }

    override fun steer(query: AgentQuery): Boolean {
        // 已关闭的 Agent 不可再注入指令——直接抛 ISE，遵循统一终态契约。
        checkShutdown()
        return delegate.steer(query)
    }

    /** 打断当前回合 —— 委托给底层 agent，SpawnedAgent 本身仍可继续使用。 */
    override fun cancel() {
        checkShutdown()
        delegate.cancel()
    }

    /** 永久关闭 Agent 并回收资源（MCP 子进程等）。幂等，可重复调用。 */
    override fun shutdown() {
        if (!shutdownSignal.complete(Unit)) return
        // 打断进行中的回合：delegate 的事件流随之自然收尾。
        delegate.cancel()
        mcpRegistry?.unregisterAll()
    }
}
