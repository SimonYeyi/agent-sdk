package io.github.yeyi.agent.mcp

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.serializer

/**
 * 进程内 [McpTransport] 实现 —— 将 JSON-RPC 请求直接委派给本地 [McpServer] 实现。
 *
 * 用于在同一进程内运行纯 Kotlin 实现的 MCP 服务，无需网络或子进程开销。
 * 传输层将 [McpClient] 使用的 JSON-RPC 有线协议桥接到 [McpServer] 的类型化方法调用。
 *
 * 典型使用场景：
 * - 测试：无需启动真实 MCP 服务进程
 * - 简单的本地工具：不想用子进程方式封装成本太高
 *
 * 使用方式：
 * ```kotlin
 * class MyLocalServer : McpServer {
 *     override val transport: McpTransport = LocalTransport.forServer()
 *     // ... 实现 McpServer 接口方法
 * }
 *
 * val registry = McpRegistry(clientInfo).apply {
 *     register(object : Mcp {
 *         override val name = "my-local"
 *         override val description = "本地 MCP 服务"
 *         override val client = McpClient(LocalTransport(MyLocalServer()))
 *     })
 * }
 * ```
 *
 * 注意：[LocalTransport.forServer] 返回的 transport 的 `send()` 方法永远不会被调用，
 * 仅用于满足 [McpServer.transport] 属性要求的契约。
 */
public class LocalTransport(private val localServer: McpServer) : McpTransport {
    private val initializeMutex = Mutex()
    private var cachedInitResult: InitializeResult? = null

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = false
    }

    override val notifications: Flow<JsonRpcNotification<JsonElement>> =
        localServer.transport.notifications

    override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> {
        val id = request.id
        val result: JsonElement = when (val method = request.method) {
            McpMethods.INITIALIZE -> {
                val initParams: InitializeParams = request.params
                    ?.let { json.decodeFromJsonElement(serializer<InitializeParams>(), it) }
                    ?: return errorResponse(id, "Missing params for initialize")
                val initResult: InitializeResult = initializeMutex.withLock {
                    cachedInitResult ?: localServer.initialize(initParams)
                        .also { cachedInitResult = it }
                }
                json.encodeToString(serializer<InitializeResult>(), initResult)
                    .let { json.parseToJsonElement(it) }
            }

            McpMethods.TOOLS_LIST -> {
                val cursor = request.params
                    ?.let { json.decodeFromJsonElement(ListToolsParams.serializer(), it) }
                    ?.cursor
                val listResult = localServer.listTools(cursor)
                json.encodeToString(serializer<ListToolsResult>(), listResult)
                    .let { json.parseToJsonElement(it) }
            }

            McpMethods.TOOLS_CALL -> {
                val callToolParams = request.params
                    ?.let { json.decodeFromJsonElement(CallToolParams.serializer(), it) }
                    ?: return errorResponse(id, "Missing params for tools/call")

                val callResult = try {
                    val result = localServer.callTool(callToolParams)
                    CallToolResult(content = result)
                } catch (e: Exception) {
                    CallToolResult(isError = true, content = buildJsonArray {
                        add(buildJsonObject {
                            put("type", "text")
                            put("text", e.message)
                        })
                    })
                }

                json.encodeToString(serializer<CallToolResult>(), callResult)
                    .let { json.parseToJsonElement(it) }
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

    override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) {
        localServer.transport.sendNotification(notification)
    }

    override suspend fun close() {
        cachedInitResult = null
        localServer.close()
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

    public companion object {
        /**
         * 为进程内 [McpServer] 创建满足 [McpServer.transport] 契约的 dummy transport。
         *
         * 本 transport 的 [send][McpTransport.send] 方法永远抛出 [UnsupportedOperationException]——
         * 进程内 server 不需要走传输层，直接调用方法即可。
         *
         * @param notifications 服务端通知流，用于向客户端发送通知，默认空流
         * @param sendNotification 发送通知的回调，用于服务端接收客户端的通知，默认空操作
         */
        public fun forServer(
            notifications: Flow<JsonRpcNotification<JsonElement>> = flow { },
            sendNotification: (notification: JsonRpcNotification<JsonElement>) -> Unit = {}
        ): McpTransport {
            return object : McpTransport {
                override val notifications: Flow<JsonRpcNotification<JsonElement>> = notifications

                override suspend fun send(request: JsonRpcRequest<JsonElement>): JsonRpcResponse<JsonElement> =
                    throw UnsupportedOperationException()

                override suspend fun sendNotification(notification: JsonRpcNotification<JsonElement>) =
                    sendNotification.invoke(notification)

                override suspend fun close() {}
            }
        }
    }
}

@Serializable
private data class JsonRpcError(
    val code: Int,
    val message: String,
)
