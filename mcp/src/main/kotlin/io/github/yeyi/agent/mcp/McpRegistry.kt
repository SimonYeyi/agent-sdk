package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.capability.DefaultCapabilityRegistry
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolDispatcher
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonElement

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

    override fun unregisterAll() {
        runBlocking {
            all().forEach { runCatching { it.client.close() } }
            delegate.unregisterAll()
        }
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
