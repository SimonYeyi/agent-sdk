package io.github.yeyi.agent.mcp

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement

/**
 * Generic implementation of [McpServer] that delegates to a [McpTransport].
 *
 * This class handles the JSON-RPC protocol details. Users provide a transport
 * implementation appropriate for their server (e.g., [StdioTransport] for local
 * subprocess servers, [SseTransport] for remote servers).
 *
 * On first use, the server is lazily initialized per the MCP 2025-06-18
 * lifecycle: an `initialize` request is sent, the returned [InitializeResult]
 * is cached, and a `notifications/initialized` notification is dispatched
 * before any tool call goes through. Initialization is guarded by
 * [initializeMutex] so concurrent first-use callers see a single handshake.
 *
 * Tool discovery ([listAllTools] extension) follows `nextCursor` pagination
 * to assemble the full tool list; the server itself does not cache.
 */
public class GenericMcpServer(
    override val name: String,
    override val description: String,
    override val transport: McpTransport,
) : McpServer {
    private val initializeMutex = Mutex()
    private var initResult: InitializeResult? = null
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    internal var clientInfo: ClientInfo? = null

    override suspend fun initialize(): InitializeResult = initializeMutex.withLock {
        initResult ?: run {
            transport.initialize()
            doInitialize()
        }.also { initResult = it }
    }

    override suspend fun listTools(cursor: String?): ListToolsResult {
        initialize()

        val params = cursor?.let { ListToolsParams(it) }
        val request = JsonRpcRequest(
            id = nextId.getAndIncrement(),
            method = McpMethods.TOOLS_LIST,
            params = params,
        )
        val response = transport.send(request)
        if (response.error != null) {
            throw MCPServerException(response.error.toString())
        }
        val resultElement = response.result
            ?: throw MCPServerException("MCP listTools response missing result")
        return json.decodeFromJsonElement<ListToolsResult>(resultElement)
    }

    override suspend fun callTool(params: JsonElement): JsonElement {
        initialize()
        val rpcRequest = JsonRpcRequest(
            id = nextId.getAndIncrement(),
            method = McpMethods.TOOLS_CALL,
            params = params
        )

        val response = transport.send(rpcRequest)

        if (response.error != null) {
            throw MCPServerException(response.error.toString())
        }

        val resultElement = response.result
            ?: throw MCPServerException("MCP callTool response missing result")

        val result = json.decodeFromJsonElement<CallToolResult>(resultElement)
        if (result.isError) {
            throw MCPServerException(result.content?.toString() ?: "MCP tool call returned error")
        }

        return result.content ?: JsonObject(emptyMap()) as JsonElement
    }

    override suspend fun ping(): Boolean {
        initialize()
        val request = JsonRpcRequest(
            id = nextId.getAndIncrement(),
            method = McpMethods.PING,
            params = EmptyParams,
        )
        val response = transport.send(request)
        return response.error == null
    }

    override suspend fun close() {
        transport.close()
    }

    private suspend fun doInitialize(): InitializeResult {
        val params = InitializeParams(
            protocolVersion = McpServer.SUPPORTED_PROTOCOL_VERSION,
            capabilities = ClientCapabilities(
                roots = RootsObject(listChanged = true),
                sampling = SamplingObject,
            ),
            clientInfo = clientInfo ?: DEFAULT_CLIENT_INFO,
        )
        val request = JsonRpcRequest(
            id = nextId.getAndIncrement(),
            method = McpMethods.INITIALIZE,
            params = params,
        )
        val response = transport.send(request)
        if (response.error != null) {
            throw MCPServerException(response.error.toString())
        }
        val resultElement = response.result
            ?: throw MCPServerException("MCP initialize response missing result")
        val result = json.decodeFromJsonElement<InitializeResult>(resultElement)

        if (result.protocolVersion.isNotEmpty() &&
            result.protocolVersion != McpServer.SUPPORTED_PROTOCOL_VERSION
        ) {
            throw MCPServerException(
                "MCP server protocol version '${result.protocolVersion}' is not supported " +
                        "(client supports $McpServer.SUPPORTED_PROTOCOL_VERSION)"
            )
        }

        // Send the initialized notification as the final handshake step.
        val notification = JsonRpcRequest(
            id = 0,
            method = McpMethods.NOTIFICATIONS_INITIALIZED,
            params = EmptyParams,
        )
        transport.sendNotification(notification)

        return result
    }

    private companion object {
        val DEFAULT_CLIENT_INFO = ClientInfo("agent-sdk", "0.1.0")
    }
}

private class MCPServerException(message: String) :
    RuntimeException("MCP Server Exception: $message")
