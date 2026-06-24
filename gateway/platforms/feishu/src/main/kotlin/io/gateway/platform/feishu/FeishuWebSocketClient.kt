package io.gateway.platform.feishu

import io.gateway.model.ConnectionState
import io.gateway.model.PlatformError
import io.gateway.model.PlatformId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

internal class FeishuWebSocketClient(
    private val config: FeishuConfig,
    private val httpClient: HttpClient,
    private val tokenProvider: suspend () -> String?,
    private val json: Json,
    private val messageListener: (String) -> Unit,
    private val stateListener: (ConnectionState) -> Unit,
    private val errorListener: (PlatformError) -> Unit
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var webSocketSession: WebSocketSession? = null
    private var connectJob: Job? = null
    private var reconnectAttempts = 0
    private val maxReconnectDelay = 60_000L

    @Volatile
    private var running = false

    fun connect() {
        if (running) return
        running = true
        connectJob = scope.launch {
            doConnect()
        }
    }

    suspend fun disconnect() {
        running = false
        webSocketSession?.close()
        webSocketSession = null
        connectJob?.cancel()
        connectJob = null
        stateListener(ConnectionState.DISCONNECTED)
    }

    private suspend fun doConnect() {
        while (running) {
            stateListener(ConnectionState.CONNECTING)

            val token = tokenProvider()
            if (token == null) {
                errorListener(
                    PlatformError(
                        platform = PlatformId.FEISHU,
                        error = "Failed to get access token"
                    )
                )
                scheduleReconnect()
                continue
            }

            val wsUrl = getWebSocketUrl(token)
            if (wsUrl == null) {
                errorListener(
                    PlatformError(
                        platform = PlatformId.FEISHU,
                        error = "Failed to get WebSocket URL"
                    )
                )
                scheduleReconnect()
                continue
            }

            try {
                val session = httpClient.webSocketSession(wsUrl) {
                    header("Authorization", "Bearer $token")
                }
                webSocketSession = session
                reconnectAttempts = 0
                stateListener(ConnectionState.CONNECTED)

                for (frame in session.incoming) {
                    when (frame) {
                        is Frame.Text -> {
                            handleMessage(frame.readText())
                        }
                        is Frame.Close -> {
                            webSocketSession = null
                            if (running) {
                                stateListener(ConnectionState.RECONNECTING)
                            }
                            break
                        }
                        else -> { /* ignore */ }
                    }
                }
            } catch (e: Exception) {
                webSocketSession = null
                errorListener(
                    PlatformError(
                        platform = PlatformId.FEISHU,
                        error = "WebSocket error: ${e.message}",
                        details = e.javaClass.name
                    )
                )
                if (running) {
                    stateListener(ConnectionState.RECONNECTING)
                    scheduleReconnect()
                }
            }
        }
    }

    private suspend fun scheduleReconnect() {
        reconnectAttempts++
        val delayMs = minOf(
            (1L shl reconnectAttempts.coerceAtMost(6)) * 1000,
            maxReconnectDelay
        )
        delay(delayMs)
    }

    private suspend fun getWebSocketUrl(token: String): String? {
        return runCatching {
            // 飞书 WebSocket 网关地址: GET /open-apis/gateway/v1/connect
            val response = httpClient.get("${config.domain}/open-apis/gateway/v1/connect") {
                header("Authorization", "Bearer $token")
            }

            val body = response.bodyAsText()
            val jsonMap = json.decodeFromString<Map<String, Any>>(body)

            val code = (jsonMap["code"] as? Number)?.toInt() ?: -1
            if (code == 0) {
                (jsonMap["data"] as? Map<String, Any>)?.get("ws_url") as? String
            } else {
                null
            }
        }.getOrNull()
    }

    private suspend fun handleMessage(text: String) {
        runCatching {
            val jsonMap = json.decodeFromString<Map<String, Any>>(text)
            val type = jsonMap["type"] as? String

            when (type) {
                "event" -> {
                    // 飞书 WebSocket 消息格式:
                    // { type: "event", event: { header: {...}, event: {...} } }
                    // 其中 inner event 包含 sender, message 等
                    val outerEvent = jsonMap["event"] as? Map<String, Any> ?: return
                    val header = outerEvent["header"] as? Map<String, Any>
                    val innerEvent = outerEvent["event"] as? Map<String, Any> ?: outerEvent

                    // 检查事件类型
                    val eventType = header?.get("event_type") as? String
                    if (eventType == "im.message.receive_v1") {
                        // 构建 Parser 期望的格式: { header: {...}, event: {...} }
                        val fullMessage = mapOf(
                            "header" to header,
                            "event" to innerEvent
                        )
                        messageListener(json.encodeToString(fullMessage))
                    }
                }
                "ping" -> {
                    // 回复心跳
                    webSocketSession?.send(Frame.Text("{\"type\":\"pong\"}"))
                }
                "pong" -> {
                    // 心跳响应
                }
                "connect" -> {
                    // 连接建立确认
                }
                "disconnect" -> {
                    // 服务端断开连接
                    webSocketSession = null
                    if (running) {
                        stateListener(ConnectionState.RECONNECTING)
                    }
                }
                else -> {
                    // 未知消息类型，忽略
                }
            }
        }
    }

    fun send(text: String): Boolean {
        val session = webSocketSession ?: return false
        return try {
            scope.launch {
                session.send(Frame.Text(text))
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
