package io.github.yeyi.agent.mcp.fixture

import io.github.yeyi.agent.mcp.CallToolParams
import io.github.yeyi.agent.mcp.InitializeParams
import io.github.yeyi.agent.mcp.InitializeResult
import io.github.yeyi.agent.mcp.JsonRpcNotification
import io.github.yeyi.agent.mcp.ListToolsResult
import io.github.yeyi.agent.mcp.McpServer
import io.github.yeyi.agent.mcp.ServerCapabilities
import io.github.yeyi.agent.mcp.ServerInfo
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.serialization.json.JsonElement

/**
 * Variant of [FixtureMcpServer] whose sole purpose is to drive the
 * [io.github.yeyi.agent.mcp.StdioServerTransport.sendNotification]
 * callback path: when a client→server notification arrives, this fixture
 * echoes it back onto the outgoing [McpTransport.notifications] flow so
 * the test can assert the callback was invoked.
 *
 * Kept separate from [FixtureMcpServer] so the basic mock stays a pure
 * protocol implementation with no test-only hooks.
 */
class CallbackFixtureMcpServer : McpServer {

    /**
     * Covariant return of [McpServer.transport]. The [StdioServerTransport]
     * is constructed here with a callback that mirrors any incoming
     * notification back onto the outgoing flow — letting the test side
     * observe when the callback fires.
     */
    override val transport: StdioServerTransport = StdioServerTransport(
        server = this,
        onClientNotification = { notification ->
            @Suppress("UNCHECKED_CAST")
            (transport.notifications as MutableSharedFlow<JsonRpcNotification<JsonElement>>)
                .tryEmit(notification)
        },
    )

    override suspend fun initialize(params: InitializeParams): InitializeResult = InitializeResult(
        protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
        serverInfo = ServerInfo(SERVER_NAME, SERVER_VERSION),
        capabilities = ServerCapabilities(),
    )

    override suspend fun listTools(cursor: String?): ListToolsResult =
        error("listTools not yet implemented in fixture")

    override suspend fun callTool(params: CallToolParams): JsonElement =
        error("callTool not yet implemented in fixture")

    override suspend fun ping(): Boolean = true

    override suspend fun close() {}

    companion object {
        const val SERVER_NAME = "callback-fixture-stdio"
        const val SERVER_VERSION = "test-0.0.1"
    }
}