package io.github.yeyi.agent.app.demo.mcp

import io.github.yeyi.agent.app.BuildConfig
import io.github.yeyi.agent.mcp.Mcp
import io.github.yeyi.agent.mcp.McpClient
import io.github.yeyi.agent.mcp.SseTransport
import io.github.yeyi.agent.mcp.ToolDef
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.compression.CompressTool
import io.ktor.client.HttpClient

class IotHaMcp(httpClient: HttpClient) : Mcp() {
    override val name: String = "Iot Ha"
    override val description: String = "智能家居控制 MCP 服务"
    override val client: McpClient = McpClient(
        SseTransport(
            "https://iot-ha-test.meizu.com/api/mcp",
            mapOf("Authorization" to "Bearer ${BuildConfig.IOT_HA_TOKEN}"),
            httpClient = httpClient
        )
    )

    override fun adaptTool(client: McpClient, toolDef: ToolDef): Tool {
        return CompressTool(super.adaptTool(client, toolDef))
    }
}
