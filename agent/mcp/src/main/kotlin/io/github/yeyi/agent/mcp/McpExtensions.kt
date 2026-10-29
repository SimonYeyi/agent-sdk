package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.toolset.toolsets

/**
 * 将 MCP 服务注册表挂载到 Agent Builder。
 *
 * 与 [io.github.yeyi.agent.toolset.toolsets] **可共存** —— 本 DSL 内部把
 * [McpRegistry] 持有的 toolset 注册表并入共享的 toolset 插件,两个 DSL 谁先谁后
 * 都只会安装一份 `load_toolset` + `member_tool_delegate`,普通 Toolset 与 MCP
 * 服务同时挂在其下。
 *
 * 示例:
 * ```kotlin
 * val mcpRegistry = McpRegistry(ClientInfo("my-agent", "1.0")).apply {
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
    toolsets(registry.toolsetRegistry)
}

/**
 * 便捷扩展 —— 跟随 `tools/list` 的 `nextCursor` 分页拉取该 MCP 服务的全部工具列表。
 *
 * 本扩展不做缓存，每次调用都会发起完整的分页请求。返回结果的 `nextCursor` 始终为 null。
 */
internal suspend fun McpClient.toolsList(): ListToolsResult {
    val allTools = mutableListOf<ToolDef>()
    var cursor: String? = null
    do {
        val result = listTools(cursor)
        allTools.addAll(result.tools)
        cursor = result.nextCursor
    } while (cursor != null)
    return ListToolsResult(tools = allTools)
}
