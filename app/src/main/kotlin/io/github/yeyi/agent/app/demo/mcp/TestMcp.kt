package io.github.yeyi.agent.app.demo.mcp

import io.github.yeyi.agent.mcp.Mcp
import io.github.yeyi.agent.mcp.McpClient
import io.github.yeyi.agent.mcp.SseTransport
import io.ktor.client.HttpClient

class TestMcp(httpClient: HttpClient) : Mcp {
    override val name: String = "test"
    override val description: String = "测试 MCP"
    override val client: McpClient =
        McpClient(SseTransport("https://mcptest.xyz/sse", httpClient = httpClient))
}
