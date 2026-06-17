package io.github.yeyi.agent.mcp

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * Transport abstraction for MCP protocol communication.
 *
 * Different MCP servers may use different transport mechanisms:
 * - [StdioTransport]: For local subprocess servers (communicates via stdin/stdout)
 * - [SseTransport]: For remote servers using Server-Sent Events over HTTP
 */
public sealed interface McpTransport {
    /**
     * Send a JSON-RPC request and wait for response.
     */
    public suspend fun send(request: JsonRpcRequest): JsonRpcResponse

    /**
     * Release all resources held by this transport.
     */
    public suspend fun close()
}

/**
 * JSON-RPC 2.0 request message.
 */
@Serializable
public data class JsonRpcRequest(
    val id: Int,
    val method: String,
    val params: JsonElement? = null,
)

/**
 * JSON-RPC 2.0 response message.
 */
@Serializable
public data class JsonRpcResponse(
    val id: Int,
    val result: JsonElement? = null,
    val error: JsonElement? = null,
)