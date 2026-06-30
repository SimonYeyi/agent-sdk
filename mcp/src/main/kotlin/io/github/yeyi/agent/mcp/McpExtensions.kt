package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityAdapter
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * 将 MCP 能力注册到 Agent Builder。
 *
 * 调用后会向 Agent 添加：
 * - 复用能力框架生成的 `load_mcp` 工具（[CapabilityAdapter] + [McpContextFactory]），
 *   用于发现指定 MCP 服务下的可用工具
 * - [CallMcpTool]（`call_mcp_tool`）：调用指定 MCP 服务上的工具。
 *   该工具的代理调用不在 [io.github.yeyi.agent.capability.Capability] 框架管辖范围内，所以单独注册。
 *
 * 示例：
 * ```kotlin
 * val registry = McpRegistry(ClientInfo("my-agent", "1.0")).apply {
 *     register(LiveScoreMcp(httpClient))
 *     register(CalculatorMcp())
 * }
 *
 * val agent = agent {
 *     llmProvider(OpenAiProvider(...))
 *     mcp(registry)
 * }.build()
 * ```
 */
public fun AgentBuilder.mcp(registry: McpRegistry) {
    CapabilityAdapter.of(
        registry,
        McpContextFactory(),
        null,
        true,
    ).installOn(this)

    // MCP 工具的代理调用不在 Capability 框架管辖范围,这里单独注册。
    tool(CallMcpTool(registry))
}

/**
 * 便捷扩展 —— 跟随 `tools/list` 的 `nextCursor` 分页拉取该 MCP 服务的全部工具列表。
 *
 * 本扩展不做缓存，每次调用都会发起完整的分页请求。返回结果的 `nextCursor` 始终为 null。
 */
public suspend fun McpClient.toolsList(): ListToolsResult {
    val allTools = mutableListOf<JsonElement>()
    var cursor: String? = null
    do {
        val result = listTools(cursor)
        allTools.addAll(result.tools)
        cursor = result.nextCursor
    } while (cursor != null)
    return ListToolsResult(tools = JsonArray(allTools))
}
