package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * MCP 服务注册表。
 *
 * 内部把 [Mcp] 作为一种特殊的 [io.github.yeyi.agent.toolset.Toolset] 复用 toolset
 * 框架的发现与代理调用能力。本类对外只暴露类型收窄的 [register] / [unregisterAll],
 * MCP 实现细节封装在模块内 —— 调用方不感知 toolset。
 *
 * 与 `toolsets()` DSL 可共存: [mcps][io.github.yeyi.agent.mcp.mcps] 内部把本注册表
 * 并入共享的 toolset 插件,自动合并。
 *
 * `register` 时把 [clientInfo] 注入到每个 [Mcp] 实例的 [McpClient];
 * `unregisterAll` 时关闭所有 MCP 连接,然后清空注册表。
 *
 * @param clientInfo 注入到每个 MCP 客户端的 [ClientInfo]。
 */
public class McpRegistry(private val clientInfo: ClientInfo) {
    internal val toolsetRegistry = ToolsetRegistry()

    /** 注册单个 [Mcp] 服务,自动注入 [clientInfo]。 */
    public fun register(mcp: Mcp) {
        mcp.client.clientInfo = clientInfo
        toolsetRegistry.register(mcp)
    }

    /** 批量注册 [Mcp] 服务。 */
    public fun register(mcps: Iterable<Mcp>) {
        mcps.forEach(::register)
    }

    /** 返回已注册的所有 [Mcp] 服务（只读快照）。 */
    public fun all(): List<Mcp> = toolsetRegistry.all().filterIsInstance<Mcp>()

    /** 关闭所有 MCP 连接,然后清空注册表。返回关闭协程的 [Job],调用方可 `join()` 等待。若共享 registry 是外部 ToolsetRegistry,其中已并入的 Mcp 条目不随 `unregisterAll()` 移除,但 shutdown 后 `run()` 抛 ISE,残留引用无害。 */
    @OptIn(DelicateCoroutinesApi::class)
    public fun unregisterAll(): Job {
        // 先快照 Mcp 列表,再清空注册表;否则协程跑起来时 all() 已空。
        return all().let { mcps ->
            GlobalScope.launch {
                mcps.forEach { runCatching { it.client.close() } }
            }
        }.also {
            toolsetRegistry.unregisterAll()
        }
    }
}
