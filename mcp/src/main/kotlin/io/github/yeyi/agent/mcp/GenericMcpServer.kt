package io.github.yeyi.agent.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Generic implementation of [McpServer] that delegates to a [McpTransport].
 *
 * This class handles the JSON-RPC protocol details. Users provide a transport
 * implementation appropriate for their server (e.g., [StdioTransport] for local
 * subprocess servers, [SseTransport] for remote servers).
 */
public class GenericMcpServer(
    override val name: String,
    override val description: String,
    override val transport: McpTransport,
) : McpServer {
    private val mutex = Mutex()
    private var toolsCache: JsonElement? = null
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1)

    override suspend fun listTools(): JsonElement = mutex.withLock {
        toolsCache?.let { return@withLock it }

        val request = JsonRpcRequest(id = nextId.getAndIncrement(), method = "tools/list")
        val response = transport.send(request)

        if (response.error != null) {
            throw MCPServerException(response.error)
        }

        response.result?.let { result ->
            toolsCache = result
            return@withLock result
        } ?: JsonObject(emptyMap())
    }

    override suspend fun callTool(params: JsonElement): JsonElement = mutex.withLock {
        val rpcRequest = JsonRpcRequest(
            id = nextId.getAndIncrement(),
            method = "tools/call",
            params = params
        )

        val response = transport.send(rpcRequest)

        if (response.error != null) {
            throw MCPServerException(response.error)
        }

        response.result ?: JsonObject(emptyMap())
    }

    override suspend fun close() {
        mutex.withLock {
            toolsCache = null
            transport.close()
        }
    }
}

internal class MCPServerException(jsonElement: JsonElement) :
    RuntimeException("MCP Server Exception: $jsonElement")