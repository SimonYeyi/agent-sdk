package io.github.yeyi.agent.mcp

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.serializer

/**
 * MCP 客户端实现 —— 通过 [McpTransport] 与 MCP 服务进行 JSON-RPC 协议通信。
 *
 * 本类实现了 [McpServer] 接口，作为 MCP 服务的客户端代理：将方法调用转换为
 * JSON-RPC 请求发给传输层，再将响应解析回结构化类型。传输层可以是
 * [StdioTransport]（本地子进程）、[SseTransport]（远程 HTTP）或
 * [LocalTransport]（进程内本地服务）。
 *
 * 首次使用时懒初始化，遵循 MCP 2025-06-18 生命周期：发送 `initialize` 请求，
 * 缓存返回的 [InitializeResult]，并在首次工具调用前发送
 * `notifications/initialized` 通知。初始化由 [initializeMutex] 保护，
 * 并发首次调用只会触发一次握手。
 *
 * 工具发现通过扩展函数 [toolsList] 跟随 `nextCursor` 分页拉取完整工具列表，
 * 本类本身不做缓存。
 */
public class McpClient(override val transport: McpTransport) : McpServer {
    private val initializeMutex = Mutex()
    private var initResult: InitializeResult? = null
    private val nextId = java.util.concurrent.atomic.AtomicInteger(1)

    // Set by [close] before tearing down the transport. Used by
    // [notifyCancelledIfOpen] to suppress notifications/cancelled once the
    // client has stopped using the wire — the server will discover the
    // disconnection anyway, and firing one more notification would either
    // race with close() or, in stdio's case, resurrect a dead subprocess.
    @Volatile
    private var closed = false

    // Detached scope so the fire-and-forget notifications/cancelled can be
    // flushed even after the caller's coroutine has been cancelled.
    private val cancellationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    internal var clientInfo: ClientInfo? = null

    override suspend fun initialize(): InitializeResult = initializeMutex.withLock {
        initResult ?: run { doInitialize() }.also { initResult = it }
    }

    override suspend fun listTools(cursor: String?): ListToolsResult {
        initialize()

        val params = cursor?.let { ListToolsParams(it) }
        val paramsElement = params?.let { json.encodeToJsonElement(serializer(), it) }
        val request = JsonRpcRequest<JsonElement>(
            id = nextId.getAndIncrement(),
            method = McpMethods.TOOLS_LIST,
            params = paramsElement,
        )
        val response = sendWithCancelNotification(request)
        if (response.error != null) {
            throw McpException(response.error.toString())
        }
        val resultElement = response.result
            ?: throw McpException("MCP listTools response missing result")
        return json.decodeFromJsonElement<ListToolsResult>(resultElement)
    }

    override suspend fun callTool(params: CallToolParams): JsonElement {
        initialize()
        val paramsElement = json.encodeToJsonElement(serializer<CallToolParams>(), params)
        val rpcRequest = JsonRpcRequest<JsonElement>(
            id = nextId.getAndIncrement(),
            method = McpMethods.TOOLS_CALL,
            params = paramsElement,
        )

        val response = sendWithCancelNotification(rpcRequest)

        if (response.error != null) {
            throw McpException(response.error.toString())
        }

        val resultElement = response.result
            ?: throw McpException("MCP callTool response missing result")

        val result = json.decodeFromJsonElement<CallToolResult>(resultElement)
        if (result.isError) {
            throw McpException(result.content?.toString() ?: "MCP tool call returned error")
        }

        return result.content ?: JsonObject(emptyMap()) as JsonElement
    }

    override suspend fun ping(): Boolean {
        initialize()
        val paramsElement = json.encodeToJsonElement(serializer<EmptyParams>(), EmptyParams)
        val request = JsonRpcRequest<JsonElement>(
            id = nextId.getAndIncrement(),
            method = McpMethods.PING,
            params = paramsElement,
        )
        val response = sendWithCancelNotification(request)
        return response.error == null
    }

    override suspend fun close() {
        closed = true
        cancellationScope.cancel()
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
        val paramsElement = json.encodeToJsonElement(serializer<InitializeParams>(), params)
        val request = JsonRpcRequest<JsonElement>(
            id = nextId.getAndIncrement(),
            method = McpMethods.INITIALIZE,
            params = paramsElement,
        )
        val response = sendWithCancelNotification(request)
        if (response.error != null) {
            throw McpException(response.error.toString())
        }
        val resultElement = response.result
            ?: throw McpException("MCP initialize response missing result")
        val result = json.decodeFromJsonElement<InitializeResult>(resultElement)

        if (result.protocolVersion.isNotEmpty() &&
            result.protocolVersion != McpServer.SUPPORTED_PROTOCOL_VERSION
        ) {
            throw McpException(
                "MCP server protocol version '${result.protocolVersion}' is not supported " +
                        "(client supports $McpServer.SUPPORTED_PROTOCOL_VERSION)"
            )
        }

        // Send the initialized notification as the final handshake step.
        val notificationParams = json.encodeToJsonElement(serializer<EmptyParams>(), EmptyParams)
        val notification = JsonRpcNotification(
            method = McpMethods.NOTIFICATIONS_INITIALIZED,
            params = notificationParams,
        )
        transport.sendNotification(notification)

        return result
    }

    private suspend fun sendWithCancelNotification(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> {
        try {
            return transport.send(request)
        } catch (e: CancellationException) {
            notifyCancelledIfOpen(request.id)
            throw e
        }
    }

    private fun notifyCancelledIfOpen(requestId: Int) {
        if (closed) return
        cancellationScope.launch {
            runCatching {
                transport.sendNotification(
                    JsonRpcNotification(
                        method = McpMethods.NOTIFICATIONS_CANCELLED,
                        params = json.encodeToJsonElement(CancelledNotificationParams(requestId)),
                    )
                )
            }
        }
    }

    private companion object {
        val DEFAULT_CLIENT_INFO = ClientInfo("agent-sdk", "0.1.0")
    }
}

/**
 * MCP 操作异常 —— 在 MCP 协议交互过程中发生的错误。
 *
 * 可能的原因包括：
 * - 远端 MCP 服务返回 JSON-RPC 错误
 * - 工具调用返回 `isError: true`
 * - 响应消息格式错误或缺失必要字段
 * - 协议版本不兼容
 */
public class McpException(message: String) : RuntimeException("MCP Exception: $message")
