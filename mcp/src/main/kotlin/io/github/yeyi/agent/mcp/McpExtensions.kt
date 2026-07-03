package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.toolset.ToolsetRegistry
import io.github.yeyi.agent.toolset.ToolsetsInstallException
import io.github.yeyi.agent.toolset.toolsets
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * 将 MCP 服务注册表挂载到 Agent Builder。
 *
 * 内部通过 [io.github.yeyi.agent.toolset.toolsets] 把 [McpRegistry] 持有的
 * [io.github.yeyi.agent.toolset.ToolsetRegistry] 接入能力框架,生成:
 * - `load_toolset` 工具:发现指定 MCP 服务下的可用子工具
 * - `sub_tool_delegate` 工具:把子工具调用转发到对应 [Mcp] 的 dispatch 方法
 *
 * **与 [io.github.yeyi.agent.toolset.toolsets] 互斥** —— 因为本 DSL 内部调用了
 * `toolsets()`,两者都会安装 `load_toolset` / `sub_tool_delegate`,同一 Agent
 * 只能由一个 capability DSL 安装。若先调 `toolsets()` 再调本 DSL,内部抛出的
 * [ToolsetsInstallException] 会被本 DSL 重新包装为带 `mcps` 关键字的
 * [IllegalStateException] 提示;若先调本 DSL 再调 `toolsets()`,则由
 * `toolsets()` 直接抛 [ToolsetsInstallException]。
 *
 * 若需要混入普通 Toolset,在构造 [McpRegistry] 时通过第一个参数传入一个
 * 已经注册好它们的 [io.github.yeyi.agent.toolset.ToolsetRegistry] 即可。
 *
 * 示例:
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
public fun AgentBuilder.mcps(registry: McpRegistry) {
    try {
        toolsets(registry.toolsetRegistry)
    } catch (e: ToolsetsInstallException) {
        throw IllegalStateException(
            "mcps() and toolsets() cannot both be used on the same Agent — " +
                    "load_toolset / sub_tool_delegate would conflict. Register " +
                    "additional Toolsets via McpRegistry.toolsetRegistry instead.",
            e,
        )
    }
}

/**
 * 便捷扩展 —— 跟随 `tools/list` 的 `nextCursor` 分页拉取该 MCP 服务的全部工具列表。
 *
 * 本扩展不做缓存，每次调用都会发起完整的分页请求。返回结果的 `nextCursor` 始终为 null。
 */
internal suspend fun McpClient.toolsList(): ListToolsResult {
    val allTools = mutableListOf<JsonElement>()
    var cursor: String? = null
    do {
        val result = listTools(cursor)
        allTools.addAll(result.tools)
        cursor = result.nextCursor
    } while (cursor != null)
    return ListToolsResult(tools = JsonArray(allTools))
}
