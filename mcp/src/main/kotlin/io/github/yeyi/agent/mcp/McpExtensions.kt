package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.AgentBuilder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement

/**
 * Register MCP servers with this builder by providing a [McpServerRegistry].
 *
 * This adds two tools to the agent:
 * - [LoadMcpTool] (`load_mcp`): for discovering available MCP tools
 * - [CallMcpTool] (`call_mcp`): for calling MCP tools
 *
 * Example:
 * ```kotlin
 * val registry = McpServerRegistry().apply {
 *     register(GenericMcpServer("filesystem", "Local filesystem", StdioTransport(listOf("npx", "mcp-server-fs"))))
 * }
 *
 * val agent = agent {
 *     llmProvider(OpenAiProvider(...))
 *     mcp(registry)
 * }.build()
 * ```
 */
public fun AgentBuilder.mcp(registry: McpServerRegistry) {
    tool(LoadMcpTool(registry))
    tool(CallMcpTool(registry))
}

/**
 * SDK convenience — fetch every tool the server exposes by following
 * `tools/list` pagination until `nextCursor` is absent.
 *
 * Default implementation walks the cursor chain without caching. Concrete
 * servers may override as a member function for caching or invalidation
 * (member functions take precedence over this extension); see
 * [io.github.yeyi.agent.mcp.GenericMcpServer.listAllTools] for the
 * cached version.
 */
public suspend fun McpServer.listAllTools(): ListToolsResult {
    val allTools = mutableListOf<JsonElement>()
    var cursor: String? = null
    do {
        val result = listTools(cursor)
        allTools.addAll(result.tools)
        cursor = result.nextCursor
    } while (cursor != null)
    return ListToolsResult(tools = JsonArray(allTools))
}
