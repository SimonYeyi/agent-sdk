package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.capability.DefaultCapabilityRegistry
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolDispatcher
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.DelicateCoroutinesApi
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement

/**
 * MCP 服务注册中心，同时实现 [ToolDispatcher]（用于 [CallMcpTool] 的代理调用）
 * 和 [CapabilityRegistry]（用于 [io.github.yeyi.agent.capability.CapabilityAdapter] 挂载到 Agent）。
 *
 * `register` 时自动将 [clientInfo] 注入每个注册的 [Mcp] 实例的 [Mcp.client]；
 * `unregisterAll` 时关闭所有 MCP 连接。
 */
public class McpRegistry(
    private val clientInfo: ClientInfo,
    private val delegate: DefaultCapabilityRegistry<McpContext, Mcp, Unit> = DefaultCapabilityRegistry(
        Mcp.CAPABILITY_NAME
    )
) : ToolDispatcher, CapabilityRegistry<McpContext, Mcp, Unit> by delegate {

    override fun register(capability: Mcp) {
        capability.client.clientInfo = this.clientInfo
        delegate.register(capability)
    }

    @OptIn(DelicateCoroutinesApi::class)
    override fun unregisterAll() {
        GlobalScope.launch { all().forEach { runCatching { it.client.close() } } }
        delegate.unregisterAll()
    }

    override suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val content = toolsCall(name, arguments)
        return ToolExecutionResult.success(content)
    }

    internal suspend fun toolsCall(mcpName: String, params: JsonElement): String {
        return get(mcpName).client.callTool(params).toString()
    }
}
