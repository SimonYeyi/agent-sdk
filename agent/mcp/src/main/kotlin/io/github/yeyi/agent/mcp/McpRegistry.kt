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
 * 框架的发现 (`load_toolset`) 与代理调用 (`member_tool_delegate`) 能力。本类对外只暴露
 * 类型收窄的 [register] / [unregisterAll],MCP 实现细节封装在模块内。
 *
 * `register` 时把 [clientInfo] 注入到每个 [Mcp] 实例的 [McpClient];
 * `unregisterAll` 时关闭所有 MCP 连接,然后清空注册表。
 *
 * @param toolsetRegistry MCP 作为 Toolset 注册的目标 [ToolsetRegistry]。该注册表最终由
 *  `mcps()` DSL 通过 [io.github.yeyi.agent.toolset.toolsets] 安装到 Agent 上,**占用**
 *  toolset 框架的 discovery/delegation 槽位 (`load_toolset` / `member_tool_delegate`) —
 *  因此本参数传入的注册表不能再额外单独走 `toolsets()` DSL;若还想混入其他普通
 *  Toolset,在同一个注册表里 `register` 它们即可。
 * @param clientInfo 注入到每个 MCP 客户端的 [ClientInfo]。
 *
 * 使用方式:
 * ```kotlin
 * val toolsetRegistry = ToolsetRegistry().apply {
 *     register(Toolset("weather", "天气查询").apply { add(GetWeatherTool()) })
 * }
 * val mcpRegistry = McpRegistry(toolsetRegistry, ClientInfo("my-agent", "1.0")).apply {
 *     register(LiveScoreMcp(httpClient))
 *     register(CalculatorMcp())
 * }
 *
 * val agent = agent {
 *     llmProvider(OpenAiProvider(...))
 *     mcps(mcpRegistry)
 * }
 * ```
 */
public class McpRegistry(
    internal val toolsetRegistry: ToolsetRegistry,
    private val clientInfo: ClientInfo
) {
    /** 注册单个 [Mcp] 服务,自动注入 [clientInfo]。 */
    public fun register(mcp: Mcp) {
        mcp.client.clientInfo = clientInfo
        toolsetRegistry.register(mcp)
    }

    /** 批量注册 [Mcp] 服务。 */
    public fun register(mcps: Iterable<Mcp>) {
        mcps.forEach(::register)
    }

    /** 关闭所有 MCP 连接,然后清空注册表。返回关闭协程的 [Job],调用方可 `join()` 等待。 */
    @OptIn(DelicateCoroutinesApi::class)
    public fun unregisterAll(): Job {
        // 先快照 Mcp 列表,再清空注册表;否则协程跑起来时 all() 已空。
        return toolsetRegistry.all().filterIsInstance<Mcp>().let { mcps ->
            GlobalScope.launch {
                mcps.forEach { runCatching { it.client.close() } }
            }
        }.also {
            toolsetRegistry.unregisterAll()
        }
    }
}
