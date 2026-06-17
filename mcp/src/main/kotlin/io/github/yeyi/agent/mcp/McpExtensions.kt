package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.AgentBuilder

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