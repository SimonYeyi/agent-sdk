package io.github.yeyi.agent.mcp.fixture

import io.github.yeyi.agent.mcp.CallToolParams
import io.github.yeyi.agent.mcp.InitializeResult
import io.github.yeyi.agent.mcp.ListToolsResult
import io.github.yeyi.agent.mcp.McpServer
import io.github.yeyi.agent.mcp.ServerCapabilities
import io.github.yeyi.agent.mcp.ServerInfo

/**
 * Minimal [McpServer] for exercising [StdioServerTransport] in subprocess
 * integration tests. Constructs its own server-side transport in the
 * field initializer, passing `this` so the transport dispatches back to
 * this server instance.
 *
 * Stays a pure protocol mock: no test-only hooks (system properties,
 * behavior switches, or notification emission) — see
 * [NotifyingFixtureMcpServer] for a sibling that emits one notification
 * during [initialize] when the outgoing notification path needs coverage.
 */
class FixtureMcpServer : McpServer {

    /**
     * Covariant return of [McpServer.transport]. The [StdioServerTransport]
     * is constructed here, in the server, rather than externally — the
     * server owns its own server-side wire protocol.
     */
    override val transport: StdioServerTransport = StdioServerTransport(this)

    override suspend fun initialize(): InitializeResult =
        InitializeResult(
            protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
            serverInfo = ServerInfo(SERVER_NAME, SERVER_VERSION),
            capabilities = ServerCapabilities(),
        )

    override suspend fun listTools(cursor: String?): ListToolsResult =
        error("listTools not yet implemented in fixture")

    override suspend fun callTool(params: CallToolParams): kotlinx.serialization.json.JsonElement =
        error("callTool not yet implemented in fixture")

    override suspend fun ping(): Boolean = true

    override suspend fun close() {}

    companion object {
        const val SERVER_NAME = "fixture-stdio"
        const val SERVER_VERSION = "test-0.0.1"
    }
}