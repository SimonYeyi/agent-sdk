package io.github.yeyi.agent.mcp.fixture

import io.github.yeyi.agent.mcp.CallToolParams
import io.github.yeyi.agent.mcp.InitializeResult
import io.github.yeyi.agent.mcp.JsonRpcNotification
import io.github.yeyi.agent.mcp.ListToolsResult
import io.github.yeyi.agent.mcp.McpServer
import io.github.yeyi.agent.mcp.ServerCapabilities
import io.github.yeyi.agent.mcp.ServerInfo
import kotlinx.serialization.json.JsonElement

/**
 * Variant of [FixtureMcpServer] that exits the JVM a short time after
 * [initialize] returns, so integration tests can exercise the
 * `StdioTransport` dead-process recovery path (the next [send][send] call
 * must throw `IllegalStateException` per
 * [io.github.yeyi.agent.mcp.StdioTransport]'s contract).
 *
 * Kept separate from [FixtureMcpServer] so the basic mock stays a pure
 * protocol implementation with no test-only hooks.
 */
class SelfExitFixtureMcpServer : McpServer {

    /**
     * Covariant return of [McpServer.transport].
     */
    override val transport: StdioServerTransport = StdioServerTransport(this)

    override suspend fun initialize(): InitializeResult {
        val result = InitializeResult(
            protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
            serverInfo = ServerInfo(SERVER_NAME, SERVER_VERSION),
            capabilities = ServerCapabilities(),
        )
        // Give the response time a time to be flushed, then exit cleanly so
        // the client side sees a graceful EOF on stdout.
        val watchdog = Thread {
            try {
                Thread.sleep(SELF_EXIT_DELAY_MS)
            } catch (_: InterruptedException) {
                return@Thread
            }
            kotlin.system.exitProcess(0)
        }
        watchdog.isDaemon = true
        watchdog.start()
        return result
    }

    override suspend fun listTools(cursor: String?): ListToolsResult =
        error("listTools not yet implemented in fixture")

    override suspend fun callTool(params: CallToolParams): JsonElement =
        error("callTool not yet implemented in fixture")

    override suspend fun ping(): Boolean = true

    override suspend fun close() {}

    companion object {
        const val SERVER_NAME = "self-exit-fixture-stdio"
        const val SERVER_VERSION = "test-0.0.1"
        const val SELF_EXIT_DELAY_MS = 100L
    }
}