package io.github.yeyi.agent.mcp.fixture

import io.github.yeyi.agent.mcp.CallToolParams
import io.github.yeyi.agent.mcp.InitializeResult
import io.github.yeyi.agent.mcp.JsonRpcNotification
import io.github.yeyi.agent.mcp.ListToolsResult
import io.github.yeyi.agent.mcp.McpMethods
import io.github.yeyi.agent.mcp.McpServer
import io.github.yeyi.agent.mcp.ProgressNotificationParams
import io.github.yeyi.agent.mcp.ServerCapabilities
import io.github.yeyi.agent.mcp.ServerInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/**
 * Variant of [FixtureMcpServer] whose sole purpose is to emit one
 * server→client notification during [initialize], so integration tests can
 * verify the outgoing notification path end-to-end. Kept separate from
 * [FixtureMcpServer] so the basic mock stays a pure protocol
 * implementation with no test-only hooks.
 *
 * The notification is pushed onto the outgoing [McpTransport.notifications]
 * flow (declared `Flow`, backing [MutableSharedFlow]) via cast +
 * `tryEmit` — see [io.github.yeyi.agent.mcp.StdioServerTransport] for the
 * direction semantics.
 */
class NotifyingFixtureMcpServer : McpServer {

    /**
     * Covariant return of [McpServer.transport]. The [StdioServerTransport]
     * is constructed here, in the server, rather than externally — the
     * server owns its own server-side wire protocol.
     */
    override val transport: StdioServerTransport = StdioServerTransport(this)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override suspend fun initialize(): InitializeResult {
        val params = json.encodeToJsonElement(
            serializer<ProgressNotificationParams>(),
            ProgressNotificationParams(progressToken = "init", progress = 1.0),
        )
        @Suppress("UNCHECKED_CAST")
        (transport.notifications as MutableSharedFlow<JsonRpcNotification<JsonElement>>)
            .tryEmit(
                JsonRpcNotification(
                    method = McpMethods.NOTIFICATIONS_PROGRESS,
                    params = params,
                ),
            )
        return InitializeResult(
            protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
            serverInfo = ServerInfo(SERVER_NAME, SERVER_VERSION),
            capabilities = ServerCapabilities(),
        )
    }

    override suspend fun listTools(cursor: String?): ListToolsResult =
        error("listTools not yet implemented in fixture")

    override suspend fun callTool(params: CallToolParams): JsonElement =
        error("callTool not yet implemented in fixture")

    override suspend fun ping(): Boolean = true

    override suspend fun close() {}

    companion object {
        const val SERVER_NAME = "notifying-fixture-stdio"
        const val SERVER_VERSION = "test-0.0.1"
    }
}