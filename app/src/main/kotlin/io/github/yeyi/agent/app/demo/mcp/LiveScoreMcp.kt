package io.github.yeyi.agent.app.demo.mcp

import io.github.yeyi.agent.mcp.Mcp
import io.github.yeyi.agent.mcp.McpClient
import io.github.yeyi.agent.mcp.SseTransport
import io.ktor.client.HttpClient

class LiveScoreMcp(httpClient: HttpClient) : Mcp {
    override val name: String = "live_score"
    override val description: String = "足球赛事/比分查询服务"
    override val client: McpClient =
        McpClient(SseTransport("https://livescoremcp.com/message", httpClient = httpClient))
}
