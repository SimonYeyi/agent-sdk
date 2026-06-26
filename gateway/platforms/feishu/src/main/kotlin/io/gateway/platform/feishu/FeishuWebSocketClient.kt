package io.gateway.platform.feishu

import io.gateway.model.ConnectionState
import io.gateway.model.PlatformError
import io.gateway.model.PlatformId
import io.gateway.util.gatewayLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
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
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class FeishuWebSocketClient(
    private val config: FeishuConfig,
    private val httpClient: HttpClient,
    private val tokenProvider: suspend () -> String,
    private val json: Json,
    private val messageListener: (String) -> Unit,
    private val stateListener: (ConnectionState) -> Unit,
    private val errorListener: (PlatformError) -> Unit
) {
    private val log = gatewayLog("FeishuWebSocketClient")

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

            try {
                val token = tokenProvider()
                val wsUrl = getWebSocketUrl(token)

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

                        else -> { /* ignore */
                        }
                    }
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                log.error("WebSocket error: ${e.message}", e)
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

    private suspend fun getWebSocketUrl(token: String): String {
        // 飞书 WebSocket 长连接需要通过 API 获取连接地址
        // POST {domain}/callback/ws/endpoint
        // 请求体: { AppID, AppSecret }
        // 返回: { code, data: { URL, ClientConfig } }
        val response = httpClient.post("${config.domain}/callback/ws/endpoint") {
            header("Content-Type", "application/json")
            setBody(
                buildJsonObject {
                    put("AppID", JsonPrimitive(config.appId))
                    put("AppSecret", JsonPrimitive(config.appSecret))
                }.toString()
            )
        }

        val body = response.bodyAsText()
        val responseJson: JsonObject = json.decodeFromString(body)

        val code = responseJson["code"]?.jsonPrimitive?.content?.toIntOrNull()
        val wsUrl = responseJson["data"]?.jsonObject?.get("URL")?.jsonPrimitive?.content
        return wsUrl ?: throw IllegalStateException("Failed to get WebSocket URL, code=$code")
    }

    private suspend fun handleMessage(text: String) {
        try {
            val jsonObject = json.decodeFromString<JsonObject>(text)
            val type = jsonObject["type"]?.jsonPrimitive?.content

            when (type) {
                "event" -> {
                    val outerEvent = jsonObject["event"]?.jsonObject ?: return
                    val header = outerEvent["header"]?.jsonObject
                    val innerEvent = outerEvent["event"]?.jsonObject ?: outerEvent

                    val eventType = header?.get("event_type")?.jsonPrimitive?.content
                    if (eventType == "im.message.receive_v1") {
                        val fullMessage = buildJsonObject {
                            header?.let { put("header", it) }
                            put("event", innerEvent)
                        }
                        messageListener(fullMessage.toString())
                    }
                }

                "ping" -> {
                    webSocketSession?.send(Frame.Text("{\"type\":\"pong\"}"))
                }

                "pong" -> {
                }

                "connect" -> {
                }

                "disconnect" -> {
                    webSocketSession = null
                    if (running) {
                        stateListener(ConnectionState.RECONNECTING)
                    }
                }

                else -> {
                }
            }
        } catch (e: Exception) {
            log.warn("Failed to handle WebSocket message", e)
        }
    }

    suspend fun send(text: String): Boolean {
        val session = webSocketSession ?: return false
        return try {
            session.send(Frame.Text(text))
            true
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }
}
