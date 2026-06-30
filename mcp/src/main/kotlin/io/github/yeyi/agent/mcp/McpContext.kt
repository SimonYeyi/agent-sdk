package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.ToolContext

/**
 * MCP 能力的 [CapabilityContext]。
 *
 * MCP 的发现（[Mcp.activate]）只依赖 [Mcp.client] 自身，不需要透传 agent 上下文，
 * 所以这里只是一个空标记类。
 */
public class McpContext : CapabilityContext

/**
 * [McpContext] 的工厂实现，供 [io.github.yeyi.agent.capability.CapabilityAdapter] 使用。
 */
internal class McpContextFactory : CapabilityContextFactory<McpContext> {
    override fun create(context: ToolContext): McpContext = McpContext()
}
