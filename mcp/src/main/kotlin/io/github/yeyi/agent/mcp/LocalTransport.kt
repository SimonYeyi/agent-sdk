package io.github.yeyi.agent.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.serializer

/**
 * In-process [McpTransport] that delegates to a local [McpServer] implementation.
 *
 * This allows registering pure-Kotlin MCP servers without any network or subprocess
 * overhead. The transport bridges the JSON-RPC wire protocol used by [GenericMcpServer]
 * to the typed method calls of [McpServer].
 *
 * Example:
 * ```kotlin
 * val localServer = object : McpServer {
 *     override val name = "local"
 *     override val description = "Local calculator"
 *     override val transport: McpTransport = LocalTransport(this)
 *     // ... implement McpServer methods
 * }
 *
 * val registry = McpServerRegistry(clientInfo).apply {
 *     register(GenericMcpServer("local", "Local calculator", LocalTransport(localServer)))
 * }
 * ```
 */
public class LocalTransport(
    private val localServer: McpServer,
) : McpTransport {
    private val initializeMutex = Mutex()
    private var cachedInitResult: InitializeResult? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    override val notifications: Flow<JsonRpcNotification<JsonElement>> =
        localServer.transport.notifications

    override suspend fun initialize() {
        // No external connection to establish for in-process communication.
    }

    override suspend fun <T> send(request: JsonRpcRequest<T>): JsonRpcResponse<JsonElement> {
        val id = request.id
        val result: JsonElement = when (val method = request.method) {
            McpMethods.INITIALIZE -> {
                val initResult: InitializeResult = initializeMutex.withLock {
                    cachedInitResult ?: localServer.initialize().also { cachedInitResult = it }
                }
                json.encodeToString(serializer<InitializeResult>(), initResult)
                    .let { json.parseToJsonElement(it) }
            }

            McpMethods.TOOLS_LIST -> {
                // Extract cursor from request.params without generic type assumptions.
                val cursor = extractCursor(request)
                val listResult = localServer.listTools(cursor)
                json.encodeToString(serializer<ListToolsResult>(), listResult)
                    .let { json.parseToJsonElement(it) }
            }

            McpMethods.TOOLS_CALL -> {
                // Extract raw JsonElement params and forward directly.
                val paramsElement = extractParamsElement(request)
                    ?: return errorResponse(id, "Missing params for tools/call")
                val callResult = localServer.callTool(paramsElement)
                json.encodeToString(
                    serializer<CallToolResult>(),
                    CallToolResult(content = callResult),
                ).let { json.parseToJsonElement(it) }
            }

            McpMethods.PING -> {
                localServer.ping()
                json.parseToJsonElement("{}")
            }

            else -> return errorResponse(id, "Unknown method: $method")
        }

        return JsonRpcResponse(
            jsonrpc = "2.0",
            id = id,
            result = result,
        )
    }

    override suspend fun <T> sendNotification(request: JsonRpcRequest<T>) {
        // Local in-process: notifications/initialized and notifications/cancelled
        // require no special server action. Server→client notifications are
        // delivered via the `notifications` flow on localServer.transport.
    }

    override suspend fun close() {
        cachedInitResult = null
        localServer.close()
    }

    /** Extract cursor from the generic request params by JSON round-trip. */
    private fun <T> extractCursor(request: JsonRpcRequest<T>): String? {
        return extractParamsElement(request)?.run {
            Json.decodeFromJsonElement(ListToolsParams.serializer(), this)
        }?.cursor
    }

    /** Extract raw params JsonElement from the generic request by JSON round-trip. */
    private fun <T> extractParamsElement(request: JsonRpcRequest<T>): JsonElement? {
        val body = json.encodeToString(request)
        return Json.decodeFromString<JsonRpcRequest<JsonElement>>(body).params
    }

    private fun errorResponse(id: Int, message: String): JsonRpcResponse<JsonElement> {
        val errorElement = json.encodeToString(
            serializer<JsonRpcError>(),
            JsonRpcError(code = -32601, message = message),
        ).let { json.parseToJsonElement(it) }

        return JsonRpcResponse(
            jsonrpc = "2.0",
            id = id,
            result = null,
            error = errorElement,
        )
    }
}

@Serializable
private data class JsonRpcError(
    val code: Int,
    val message: String,
)
