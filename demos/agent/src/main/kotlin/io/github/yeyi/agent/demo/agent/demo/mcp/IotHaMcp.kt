package io.github.yeyi.agent.demo.agent.demo.mcp

import io.github.yeyi.agent.demo.agent.BuildConfig
import io.github.yeyi.agent.mcp.Mcp
import io.github.yeyi.agent.mcp.McpClient
import io.github.yeyi.agent.mcp.SseTransport
import io.github.yeyi.agent.mcp.ToolDef
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.compression.CompressTool
import io.ktor.client.HttpClientConfig

class IotHaMcp(httpConfigure: (HttpClientConfig<*>.() -> Unit)?) : Mcp() {
    override val name: String = "Iot Ha"
    override val description: String = "智能家居控制 MCP 服务"
    override val client: McpClient = McpClient(
        SseTransport(
            BuildConfig.IOT_HA_ENDPOINT,
            mapOf("Authorization" to "Bearer ${BuildConfig.IOT_HA_TOKEN}"),
            httpConfigure = httpConfigure
        )
    )

    override fun adaptTool(client: McpClient, toolDef: ToolDef): Tool {
        return CompressTool(super.adaptTool(client, toolDef))
    }
}
