package io.gateway.platform.feishu

import io.gateway.api.PlatformAdapter
import io.gateway.model.ConnectionState
import io.gateway.model.ConnectResult
import io.gateway.model.IncomingMessage
import io.gateway.model.OutgoingMessage
import io.gateway.model.PlatformError
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import io.gateway.util.MessageDeduplicator
import io.gateway.util.gatewayLog
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

public class FeishuAdapter(
    private val config: FeishuConfig,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PlatformAdapter {

    private val log = gatewayLog("FeishuAdapter")

    override val platformId: PlatformId = PlatformId.FEISHU
    override val name: String = "Feishu"

    override val connectionState: ConnectionState
        get() = _connectionState

    private var _connectionState: ConnectionState = ConnectionState.DISCONNECTED

    private var messageHandler: ((IncomingMessage) -> Unit)? = null
    private var stateHandler: ((ConnectionState) -> Unit)? = null
    private var errorHandler: ((PlatformError) -> Unit)? = null

    private val httpClient = HttpClient(io.ktor.client.engine.okhttp.OkHttp) {
        expectSuccess = true
        install(WebSockets)
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        engine {
            config {
                followRedirects(true)
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private var accessToken: String? = null
    private var tokenExpiresAt: Long = 0

    private lateinit var webSocketClient: FeishuWebSocketClient
    private lateinit var messageParser: FeishuMessageParser
    private lateinit var messageSender: FeishuMessageSender
    private lateinit var deduplicator: MessageDeduplicator

    private var botOpenId: String? = null

    override fun onMessageReceived(handler: (IncomingMessage) -> Unit) {
        this.messageHandler = handler
    }

    override fun onConnectionStateChanged(handler: (ConnectionState) -> Unit) {
        this.stateHandler = handler
    }

    override fun onError(handler: (PlatformError) -> Unit) {
        this.errorHandler = handler
    }

    override suspend fun connect(): ConnectResult {
        messageParser = FeishuMessageParser(config, json)
        messageSender = FeishuMessageSender(config, httpClient, json) { accessToken }
        deduplicator = MessageDeduplicator(maxSize = 5000)

        webSocketClient = FeishuWebSocketClient(
            config = config,
            httpClient = httpClient,
            json = json,
            tokenProvider = { refreshToken() },
            messageListener = { jsonString -> handleIncomingEvent(jsonString) },
            stateListener = { state ->
                _connectionState = state
                stateHandler?.invoke(state)
            },
            errorListener = { error ->
                errorHandler?.invoke(error)
            }
        )

        try {
            refreshToken()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            return ConnectResult.Failure(
                "Failed to get access token",
                retryable = true
            )
        }

        resolveBotIdentity()
        webSocketClient.connect()

        return ConnectResult.Success(PlatformId.FEISHU)
    }

    override suspend fun disconnect() {
        webSocketClient.disconnect()
        httpClient.close()
        _connectionState = ConnectionState.DISCONNECTED
        stateHandler?.invoke(ConnectionState.DISCONNECTED)
    }

    override suspend fun sendMessage(message: OutgoingMessage): SendResult {
        return messageSender.sendMessage(message)
    }

    override suspend fun sendTypingIndicator(chatId: String) {
        if (config.sendAckReaction) {
            // 飞书没有标准的 typing 接口，这里用 ACK 表情代替
        }
    }

    override suspend fun editMessage(
        chatId: String,
        messageId: String,
        newText: String
    ): SendResult {
        return messageSender.editMessage(chatId, messageId, newText)
    }

    override suspend fun deleteMessage(chatId: String, messageId: String): Boolean {
        return messageSender.deleteMessage(chatId, messageId)
    }

    public suspend fun addReaction(messageId: String, emoji: String): Boolean {
        return try {
            messageSender.addReaction(messageId, emoji)
        } catch (e: Exception) {
            log.warn("Failed to add reaction $emoji to message $messageId", e)
            false
        }
    }

    private suspend fun refreshToken(): String {
        val now = System.currentTimeMillis()
        if (accessToken != null && now < tokenExpiresAt - 60000) {
            return accessToken!!
        }

        val response =
            httpClient.post("${config.domain}/open-apis/auth/v3/tenant_access_token/internal") {
                header("Content-Type", "application/json")
                setBody(buildJsonBody {
                    put("app_id", kotlinx.serialization.json.JsonPrimitive(config.appId))
                    put(
                        "app_secret",
                        kotlinx.serialization.json.JsonPrimitive(config.appSecret)
                    )
                })
            }

        val responseBody = response.bodyAsText()
        val responseJson: JsonObject = json.decodeFromString(responseBody)

        val code = responseJson["code"]?.jsonPrimitive?.content?.toIntOrNull()
        accessToken = responseJson["tenant_access_token"]?.jsonPrimitive?.content
        val expire = responseJson["expire"]?.jsonPrimitive?.content?.toIntOrNull() ?: 7200
        tokenExpiresAt = now + expire * 1000L
        return accessToken ?: throw IllegalStateException("Failed to refresh token, code=$code")
    }

    private suspend fun resolveBotIdentity() {
        val token = accessToken ?: return
        try {
            val response = httpClient.get("${config.domain}/open-apis/bot/v3/info") {
                header("Authorization", "Bearer $token")
            }

            val body = response.bodyAsText()
            val responseJson: JsonObject = json.decodeFromString(body)
            val code = responseJson["code"]?.jsonPrimitive?.content?.toIntOrNull()
            if (code == 0) {
                val bot = responseJson["bot"]?.jsonObject
                botOpenId = bot?.get("open_id")?.jsonPrimitive?.content
            } else {
                log.warn("Failed to resolve bot identity, code=$code")
            }
        } catch (e: Exception) {
            log.warn("Exception while resolving bot identity", e)
        }
    }

    private fun buildJsonBody(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): kotlinx.serialization.json.JsonObject {
        return buildJsonObject(block)
    }

    private fun handleIncomingEvent(jsonString: String) {
        coroutineScope.launch {
            val message = messageParser.parseMessageEvent(jsonString) ?: return@launch

            if (deduplicator.isDuplicate(message.id.value)) {
                return@launch
            }

            if (!messageParser.isAllowed(message)) {
                return@launch
            }

            if (config.sendAckReaction) {
                launch {
                    try {
                        messageSender.addReaction(message.id.value, config.ackEmoji)
                    } catch (e: Exception) {
                        log.debug("Failed to send ACK reaction for message ${message.id.value}")
                    }
                }
            }

            messageHandler?.invoke(message)
        }
    }

    public fun downloadFile(fileKey: String): ByteArray? {
        return try {
            kotlinx.coroutines.runBlocking {
                messageSender.downloadFile(fileKey)
            }
        } catch (e: Exception) {
            log.warn("Failed to download file $fileKey", e)
            null
        }
    }

    public fun getBotOpenId(): String? = botOpenId

    public companion object {
        private fun buildJsonObject(block: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit) =
            kotlinx.serialization.json.buildJsonObject(block)
    }
}
