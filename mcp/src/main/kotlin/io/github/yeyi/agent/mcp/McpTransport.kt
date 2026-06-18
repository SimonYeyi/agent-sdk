package io.github.yeyi.agent.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.serialization.json.JsonElement

/**
 * Transport abstraction for MCP protocol communication.
 *
 * Different MCP servers may use different transport mechanisms:
 * - [StdioTransport]: For local subprocess servers (communicates via stdin/stdout)
 * - [SseTransport]: For remote servers using Streamable HTTP (MCP 2025-06-18)
 *
 * The JSON-RPC message types [JsonRpcRequest] / [JsonRpcResponse] are
 * declared in `McpServer.kt` alongside the rest of the protocol contract.
 */
public interface McpTransport {
    /**
     * Send a JSON-RPC request and wait for response. The transport carries
     * the `result` payload as [JsonElement] — callers decode to the typed
     * structure they need (e.g. [InitializeResult] for the `initialize`
     * handshake) via [kotlinx.serialization.json.decodeFromJsonElement].
     */
    public suspend fun send(request: JsonRpcRequest): JsonRpcResponse<JsonElement>

    /**
     * Send a JSON-RPC notification (no `id`, no response expected).
     * Used for one-way messages such as `notifications/initialized`,
     * `notifications/cancelled`, and `notifications/tools/list_changed`.
     */
    public suspend fun sendNotification(request: JsonRpcRequest)

    /**
     * Server-to-client notification stream: out-of-band messages that arrive
     * outside of a request/response pair, such as
     * `notifications/tools/list_changed`. The default implementation returns
     * an empty flow; transports with a real notification channel override
     * this.
     */
    public val notifications: Flow<JsonRpcNotification<JsonElement>>

    /**
     * Release all resources held by this transport.
     */
    public suspend fun close()
}