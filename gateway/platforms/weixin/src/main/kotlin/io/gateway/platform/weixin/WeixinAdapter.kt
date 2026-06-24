package io.gateway.platform.weixin

import io.gateway.api.PlatformAdapter
import io.gateway.model.ConnectionState
import io.gateway.model.ConnectResult
import io.gateway.model.IncomingMessage
import io.gateway.model.OutgoingMessage
import io.gateway.model.PlatformError
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import io.gateway.util.MessageDeduplicator
import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

public class WeixinAdapter(
    private val config: WeixinConfig,
    private val coroutineScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
) : PlatformAdapter {

    override val platformId: PlatformId = PlatformId.WEIXIN
    override val name: String = "Weixin"

    override val connectionState: ConnectionState
        get() = _connectionState

    private var _connectionState: ConnectionState = ConnectionState.DISCONNECTED

    private var messageHandler: ((IncomingMessage) -> Unit)? = null
    private var stateHandler: ((ConnectionState) -> Unit)? = null
    private var errorHandler: ((PlatformError) -> Unit)? = null

    private val httpClient = HttpClient(OkHttp) {
        install(ContentNegotiation) {
            json(Json {
                ignoreUnknownKeys = true
                isLenient = true
            })
        }
        engine {
            config {
                retryOnConnectionFailure(true)
            }
        }
    }

    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
    }

    private lateinit var poller: WeixinPoller
    private lateinit var messageParser: WeixinMessageParser
    private lateinit var messageSender: WeixinMessageSender
    private lateinit var deduplicator: MessageDeduplicator

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
        messageParser = WeixinMessageParser(config, json)
        messageSender = WeixinMessageSender(config, httpClient, json)
        deduplicator = MessageDeduplicator(maxSize = 5000)

        poller = WeixinPoller(
            config = config,
            httpClient = httpClient,
            json = json,
            messageListener = { msg -> handleMessage(msg) },
            stateListener = { state ->
                _connectionState = state
                stateHandler?.invoke(state)
            },
            errorListener = { error ->
                errorHandler?.invoke(error)
            }
        )

        poller.start()
        return ConnectResult.Success(PlatformId.WEIXIN)
    }

    override suspend fun disconnect() {
        poller.stop()
        httpClient.close()
        _connectionState = ConnectionState.DISCONNECTED
        stateHandler?.invoke(ConnectionState.DISCONNECTED)
    }

    override suspend fun sendMessage(message: OutgoingMessage): SendResult {
        return messageSender.sendMessage(message)
    }

    override suspend fun sendTypingIndicator(chatId: String) {
        runCatching { messageSender.sendTypingIndicator(chatId) }
    }

    override suspend fun editMessage(chatId: String, messageId: String, newText: String): SendResult {
        return messageSender.editMessage(chatId, messageId, newText)
    }

    override suspend fun deleteMessage(chatId: String, messageId: String): Boolean {
        return messageSender.deleteMessage(chatId, messageId)
    }

    @Suppress("UNCHECKED_CAST")
    private fun handleMessage(jsonMap: Map<String, Any>) {
        coroutineScope.launch {
            val message = messageParser.parseMessage(jsonMap) ?: return@launch

            if (deduplicator.isDuplicate(message.id.value)) {
                return@launch
            }

            if (!messageParser.isAllowed(message)) {
                return@launch
            }

            messageHandler?.invoke(message)
        }
    }
}
