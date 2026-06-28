package io.gateway.platform.feishu

import com.google.gson.Gson
import com.lark.oapi.core.enums.BaseUrlEnum
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.service.im.v1.model.P2MessageReactionCreatedV1
import com.lark.oapi.ws.Client
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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

    private var deduplicator: MessageDeduplicator? = null
    private var feishuApi: FeishuApiClient? = null
    private var wsClient: Client? = null

    private val gson = Gson()

    private lateinit var messageParser: FeishuMessageParser
    private lateinit var messageFormatter: FeishuMessageFormatter
    private lateinit var messageFilter: FeishuMessageFilter

    // 机器人身份
    private var botOpenId: String = ""
    private var botName: String = ""

    // 待处理反应的消息ID映射
    private val pendingReactions = ConcurrentHashMap<String, String>()

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
        deduplicator = MessageDeduplicator(maxSize = 5000)

        try {
            // 初始化组件
            messageParser = FeishuMessageParser(gson)
            messageFormatter = FeishuMessageFormatter(gson)
            messageFilter = FeishuMessageFilter(config, deduplicator!!)

            // 创建 API 客户端
            feishuApi = FeishuApiClient(config.appId, config.appSecret, gson)

            // 创建事件分发器
            val eventDispatcher = EventDispatcher.newBuilder(
                config.verificationToken ?: "",
                config.encryptKey ?: ""
            )
                .onP2MessageReceiveV1(object : ImService.P2MessageReceiveV1Handler() {
                    @Throws(Exception::class)
                    override fun handle(event: P2MessageReceiveV1) {
                        handleMessageEvent(event)
                    }
                })
                .onP2MessageReactionCreatedV1(object :
                    ImService.P2MessageReactionCreatedV1Handler() {
                    @Throws(Exception::class)
                    override fun handle(event: P2MessageReactionCreatedV1) {
                        handleReactionEvent(event)
                    }
                })
                .build()

            stateHandler?.invoke(ConnectionState.CONNECTING)

            // 创建并启动 WebSocket 客户端
            wsClient = Client.Builder(config.appId, config.appSecret)
                .domain(BaseUrlEnum.FeiShu.url)
                .eventHandler(eventDispatcher)
                .build()

            stateHandler?.invoke(ConnectionState.CONNECTED)

            // 在协程中启动 WebSocket 客户端
            coroutineScope.launch(Dispatchers.IO) {
                try {
                    wsClient!!.start()
                } catch (e: Exception) {
                    log.error("WebSocket disconnected", e)
                    disconnect()
                }
            }

            // 获取机器人身份
            resolveBotIdentity()

            return ConnectResult.Success(PlatformId.FEISHU)
        } catch (e: Exception) {
            log.error("Failed to connect", e)
            return ConnectResult.Failure(
                error = "Failed to connect: ${e.message}",
                retryable = true
            )
        }
    }

    override suspend fun disconnect() {
        // 清理资源
        deduplicator = null
        wsClient?.close()
        wsClient = null
        feishuApi = null
        pendingReactions.clear()

        _connectionState = ConnectionState.DISCONNECTED
        stateHandler?.invoke(ConnectionState.DISCONNECTED)
    }

    override suspend fun sendMessage(message: OutgoingMessage): SendResult {
        return try {
            val textContent = messageFormatter.extractTextFromContent(message.content)
            val chunks = messageFormatter.splitMessage(textContent)
            var lastMessageId: String? = null

            for (chunk in chunks) {
                val result = sendSingleMessage(message.chatId, chunk, message.replyToMessageId)
                when (result) {
                    is SendResult.Success -> {
                        lastMessageId = result.messageId
                    }

                    is SendResult.Failure -> {
                        if (message.replyToMessageId != null) {
                            val retryResult = sendSingleMessage(message.chatId, chunk, null)
                            when (retryResult) {
                                is SendResult.Success -> lastMessageId = retryResult.messageId
                                is SendResult.Failure -> return retryResult
                            }
                        } else {
                            return result
                        }
                    }
                }
            }

            SendResult.Success(
                messageId = lastMessageId ?: "",
                platform = PlatformId.FEISHU
            )
        } catch (e: Exception) {
            log.warn("Failed to send message", e)
            SendResult.Failure(
                error = "Send exception: ${e.message}",
                retryable = true,
                exception = e.javaClass.name
            )
        }
    }

    private suspend fun sendSingleMessage(
        chatId: String,
        content: String,
        replyTo: String?
    ): SendResult {
        val (msgType, contentJson) = messageFormatter.formatOutgoingMessage(content)
        return feishuApi!!.sendMessage(chatId, msgType, contentJson, replyTo)
    }

    override suspend fun sendTypingIndicator(chatId: String) {
        // 飞书没有标准的 typing 接口
    }

    override suspend fun editMessage(
        chatId: String,
        messageId: String,
        newText: String
    ): SendResult {
        return feishuApi!!.updateMessage(messageId, newText)
    }

    override suspend fun deleteMessage(chatId: String, messageId: String): Boolean {
        return feishuApi!!.deleteMessage(messageId)
    }

    override suspend fun onProcessingStart(messageId: String) {
        if (config.sendAckReaction) {
            sendAckReaction(messageId)
        }
    }

    override suspend fun onProcessingComplete(messageId: String, success: Boolean) {
        val reactionId = pendingReactions.remove(messageId) ?: return
        coroutineScope.launch { feishuApi?.removeReaction(messageId, reactionId) }
        coroutineScope.launch {
            val reactionType =
                if (success) FeishuApiClient.ReactionType.DONE
                else FeishuApiClient.ReactionType.FAILURE
            feishuApi?.addReaction(messageId, reactionType)
        }
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    private fun handleMessageEvent(event: P2MessageReceiveV1) {
        coroutineScope.launch {
            try {
                val message = messageParser.parseSdkEvent(event) ?: return@launch

                if (!messageFilter.shouldProcess(message)) {
                    return@launch
                }

                messageHandler?.invoke(message)
            } catch (e: Exception) {
                log.warn("Error handling message event", e)
            }
        }
    }

    private fun handleReactionEvent(event: P2MessageReactionCreatedV1) {
        coroutineScope.launch {
            try {
                val eventData = event.event ?: return@launch
                val emojiType = eventData.reactionType?.emojiType ?: return@launch
                val messageId = eventData.messageId ?: return@launch
                val operatorId = eventData.userId?.openId ?: return@launch

                if (operatorId == botOpenId) return@launch

                val commandMessage = messageParser.buildReactionCommand(
                    emojiType = emojiType,
                    messageId = messageId,
                    operatorId = operatorId
                )

                messageHandler?.invoke(commandMessage)
            } catch (e: Exception) {
                log.warn("Error handling reaction event", e)
            }
        }
    }

    private fun sendAckReaction(messageId: String) {
        if (messageId.isEmpty()) return
        if (pendingReactions.containsKey(messageId)) return

        val result = feishuApi?.addReaction(messageId, FeishuApiClient.ReactionType.ACK)
        if (result?.success == true && result.reactionId != null) {
            pendingReactions[messageId] = result.reactionId
            log.debug("ACK reaction sent for message: $messageId")
        }
    }

    private fun resolveBotIdentity() {
        val botInfo = feishuApi?.getBotInfo()
        if (botInfo != null) {
            botOpenId = botInfo.openId
            botName = botInfo.name
            log.info("Bot identity: $botName ($botOpenId)")
        }
    }

    public fun getBotOpenId(): String = botOpenId
    public fun getBotName(): String = botName
}