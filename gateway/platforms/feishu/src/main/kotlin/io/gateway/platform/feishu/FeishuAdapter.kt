package io.gateway.platform.feishu

import com.google.gson.Gson
import com.lark.oapi.core.token.AccessTokenType
import com.lark.oapi.core.enums.BaseUrlEnum
import com.lark.oapi.event.EventDispatcher
import com.lark.oapi.service.im.ImService
import com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum
import com.lark.oapi.service.im.v1.model.CreateMessageReq
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody
import com.lark.oapi.service.im.v1.model.DeleteMessageReq
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1
import com.lark.oapi.service.im.v1.model.P2MessageReactionCreatedV1
import com.lark.oapi.service.im.v1.model.UpdateMessageReq
import com.lark.oapi.service.im.v1.model.UpdateMessageReqBody
import com.lark.oapi.ws.Client
import io.gateway.api.PlatformAdapter
import io.gateway.model.ChatType
import io.gateway.model.ConnectionState
import io.gateway.model.ConnectResult
import io.gateway.model.IncomingMessage
import io.gateway.model.Mention
import io.gateway.model.MessageContent
import io.gateway.model.MessageId
import io.gateway.model.MessageMetadata
import io.gateway.model.MessageSource
import io.gateway.model.OutgoingContent
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
    private var apiClient: com.lark.oapi.Client? = null
    private var wsClient: Client? = null

    private val gson = Gson()

    // 机器人身份
    private var botOpenId: String = ""
    private var botName: String = ""

    // 待处理反应的消息ID映射
    private val pendingReactions = ConcurrentHashMap<String, String>()

    // 最大消息长度
    private val maxMessageLength = 30000

    // ACK 表情
    private val ackEmoji = config.ackEmoji

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
            // 创建 API 客户端
            apiClient = com.lark.oapi.Client.Builder(config.appId, config.appSecret).build()

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
        apiClient = null
        pendingReactions.clear()

        _connectionState = ConnectionState.DISCONNECTED
        stateHandler?.invoke(ConnectionState.DISCONNECTED)
    }

    override suspend fun sendMessage(message: OutgoingMessage): SendResult {
        return try {
            // 分块长消息
            val textContent = extractTextFromContent(message.content)
            val chunks = splitMessage(textContent, maxMessageLength)
            var lastMessageId: String? = null

            for (chunk in chunks) {
                val result = sendSingleMessage(message.chatId, chunk, message.replyToMessageId)
                when (result) {
                    is SendResult.Success -> {
                        lastMessageId = result.messageId
                    }

                    is SendResult.Failure -> {
                        // 重试一次
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

    private fun extractTextFromContent(content: OutgoingContent): String {
        return when (content) {
            is OutgoingContent.Text -> content.text
            is OutgoingContent.Image -> "[Image]"
            is OutgoingContent.Audio -> "[Audio]"
            is OutgoingContent.Document -> content.fileName
        }
    }

    private suspend fun sendSingleMessage(
        chatId: String,
        content: String,
        replyTo: String?
    ): SendResult {
        return try {
            // 检测是否包含 Markdown 格式
            val isMarkdown = containsMarkdown(content)
            val (msgType, contentJson) = if (isMarkdown) {
                "post" to buildMarkdownPostContent(content)
            } else {
                val escaped = content
                    .replace("\\", "\\\\")
                    .replace("\"", "\\\"")
                    .replace("\n", "\\n")
                    .replace("\r", "\\r")
                "text" to """{"text":"$escaped"}"""
            }

            val request = CreateMessageReq.newBuilder()
                .receiveIdType(ReceiveIdTypeEnum.CHAT_ID.getValue())
                .createMessageReqBody(
                    CreateMessageReqBody.newBuilder()
                        .receiveId(chatId)
                        .msgType(msgType)
                        .content(contentJson)
                        .build()
                )
                .build()

            val response = apiClient!!.im().message().create(request)

            if (response.code == 0) {
                SendResult.Success(
                    messageId = response.data?.messageId ?: "",
                    platform = PlatformId.FEISHU
                )
            } else {
                SendResult.Failure(
                    error = "Send failed: ${response.msg}",
                    retryable = false
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to send single message", e)
            SendResult.Failure(
                error = "Send exception: ${e.message}",
                retryable = true,
                exception = e.javaClass.name
            )
        }
    }

    override suspend fun sendTypingIndicator(chatId: String) {
        // 飞书没有标准的 typing 接口
    }

    override suspend fun editMessage(
        chatId: String,
        messageId: String,
        newText: String
    ): SendResult {
        return try {
            val request = UpdateMessageReq.newBuilder()
                .messageId(messageId)
                .updateMessageReqBody(
                    UpdateMessageReqBody.newBuilder()
                        .content("""{"text":"${newText.replace("\"", "\\\"")}"}""")
                        .build()
                )
                .build()

            val response = apiClient!!.im().message().update(request)

            if (response.code == 0) {
                SendResult.Success(
                    messageId = messageId,
                    platform = PlatformId.FEISHU
                )
            } else {
                SendResult.Failure(
                    error = "Edit failed: ${response.msg}",
                    retryable = false
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to edit message", e)
            SendResult.Failure(
                error = "Edit exception: ${e.message}",
                retryable = true,
                exception = e.javaClass.name
            )
        }
    }

    override suspend fun deleteMessage(chatId: String, messageId: String): Boolean {
        return try {
            val request = DeleteMessageReq.newBuilder()
                .messageId(messageId)
                .build()

            val response = apiClient!!.im().message().delete(request)
            response.code == 0
        } catch (e: Exception) {
            log.warn("Failed to delete message $messageId", e)
            false
        }
    }

    // ========================================================================
    // 内部方法
    // ========================================================================

    /**
     * 处理消息事件
     */
    private fun handleMessageEvent(event: P2MessageReceiveV1) {
        coroutineScope.launch {
            try {
                val message = parseSdkEvent(event) ?: return@launch

                // 去重检查
                if (deduplicator!!.isDuplicate(message.id.value)) {
                    log.info("Duplicate message dropped: ${message.id.value}")
                    return@launch
                }

                // Allowlist 检查
                if (config.allowedUsers.isNotEmpty() && message.source.userId !in config.allowedUsers) {
                    log.info("User not in allowlist (message rejected): ${message.source.userId}")
                    return@launch
                }

                // Allowlist 检查 - 聊天白名单
                if (config.allowedChats.isNotEmpty() && message.source.chatId !in config.allowedChats) {
                    log.info("Chat not in allowlist (message rejected): ${message.source.chatId}")
                    return@launch
                }

                messageHandler?.invoke(message)
            } catch (e: Exception) {
                log.warn("Error handling message event", e)
            }
        }
    }

    /**
     * 处理反应事件
     */
    private fun handleReactionEvent(event: P2MessageReactionCreatedV1) {
        coroutineScope.launch {
            try {
                val eventData = event.event ?: return@launch
                val emojiType = eventData.reactionType?.emojiType ?: return@launch
                val messageId = eventData.messageId ?: return@launch
                val operatorId = eventData.userId?.openId ?: return@launch

                // 忽略机器人自己的反应
                if (operatorId == botOpenId) return@launch

                // 路由为合成命令事件
                val source = MessageSource(
                    platform = PlatformId.FEISHU,
                    chatId = "",
                    chatType = ChatType.GROUP,
                    userId = operatorId,
                    chatName = null,
                    userName = null,
                    threadId = null
                )

                val content = MessageContent.Command(
                    command = "/reaction",
                    args = listOf(emojiType, messageId)
                )

                val incomingMessage = IncomingMessage(
                    id = MessageId(messageId),
                    source = source,
                    content = content,
                    metadata = MessageMetadata()
                )

                messageHandler?.invoke(incomingMessage)
            } catch (e: Exception) {
                log.warn("Error handling reaction event", e)
            }
        }
    }

    /**
     * 发送 ACK 表情反应
     */
    private fun sendAckReaction(messageId: String) {
        if (messageId.isEmpty()) return
        if (pendingReactions.containsKey(messageId)) return

        coroutineScope.launch {
            try {
                val payload = mapOf(
                    "reaction_type" to mapOf("emoji_type" to config.ackEmoji)
                )
                val response = apiClient!!.post(
                    "/open-apis/im/v1/messages/$messageId/reactions",
                    payload,
                    AccessTokenType.App
                )
                val json = Gson().fromJson(String(response.body), Map::class.java)
                val code = (json as? Map<String, Any>)?.get("code") as? Double ?: -1.0
                if (code == 0.0) {
                    val reactionId = ((json as? Map<String, Any>)?.get("data") as? Map<String, Any>)
                        ?.get("reaction_id") as? String ?: ""
                    if (reactionId.isNotEmpty()) {
                        pendingReactions[messageId] = reactionId
                        log.debug("ACK reaction sent for message: $messageId")
                    }
                } else {
                    log.warn("ACK reaction rejected: code=$code")
                }
            } catch (e: Exception) {
                log.debug("ACK reaction error: ${e.message}")
            }
        }
    }

    private fun removeAckReaction(messageId: String, reactionId: String) {
        coroutineScope.launch {
            try {
                val response = apiClient!!.delete(
                    "/open-apis/im/v1/messages/$messageId/reactions/$reactionId",
                    null,
                    AccessTokenType.App
                )
                val json = Gson().fromJson(String(response.body), Map::class.java)
                val code = (json["code"] as? Number)?.toInt() ?: -1
                if (code == 0) {
                    log.debug("ACK reaction removed for message: $messageId")
                } else {
                    log.warn("Failed to remove ACK reaction: code=$code")
                }
            } catch (e: Exception) {
                log.debug("Failed to remove ACK reaction: ${e.message}")
            }
        }
    }

    /**
     * 获取机器人身份
     */
    private fun resolveBotIdentity() {
        try {
            val response = apiClient!!.get(
                "/open-apis/bot/v3/info",
                null,
                AccessTokenType.App
            )
            val json = Gson().fromJson(String(response.body), Map::class.java)
            val data = (json as? Map<String, Any>)?.get("bot") as? Map<String, Any>
            if (data != null) {
                botOpenId = data["open_id"] as? String ?: ""
                botName = data["app_name"] as? String ?: "Bot"
                log.info("Bot identity: $botName ($botOpenId)")
            }
        } catch (e: Exception) {
            log.warn("Failed to resolve bot identity", e)
        }
    }

    /**
     * 处理消息开始
     */
    override suspend fun onProcessingStart(messageId: String) {
        // 发送 ACK 表情
        if (config.sendAckReaction) {
            sendAckReaction(messageId)
        }
    }

    /**
     * 处理消息处理完成
     */
    override suspend fun onProcessingComplete(messageId: String, success: Boolean) {
        val reactionId = pendingReactions.remove(messageId) ?: return
        removeAckReaction(messageId, reactionId)
    }

    private fun parseSdkEvent(event: P2MessageReceiveV1): IncomingMessage? {
        try {
            val sdkMessage = event.event?.message ?: return null

            val messageId = sdkMessage.messageId ?: return null
            val chatId = sdkMessage.chatId ?: return null
            val chatType = when (sdkMessage.chatType) {
                "p2p" -> ChatType.DIRECT_MESSAGE
                "group" -> ChatType.GROUP
                else -> ChatType.GROUP
            }

            val messageType = sdkMessage.messageType ?: "text"
            val contentStr = sdkMessage.content ?: "{}"
            val content = parseContent(messageType, contentStr)

            val mentions = parseMentions(contentStr)

            // 从 sender 获取用户信息 - 使用反射获取 sender_id
            val userId = event.event?.sender?.senderId?.openId
                ?: event.event?.sender?.senderId?.userId
                ?: event.event?.sender?.senderId?.unionId
                ?: "unknown"

            val source = MessageSource(
                platform = PlatformId.FEISHU,
                chatId = chatId,
                chatType = chatType,
                userId = userId,
                chatName = null,
                userName = userId,
                threadId = sdkMessage.threadId?.takeIf { it.isNotBlank() }
            )

            val metadata = MessageMetadata(mentions = mentions)

            return IncomingMessage(
                id = MessageId(messageId),
                source = source,
                content = content,
                metadata = metadata
            )
        } catch (e: Exception) {
            log.warn("Failed to parse SDK event", e)
            return null
        }
    }

    /**
     * 从 SDK 消息对象中提取发送者 ID
     */
    private fun extractSenderId(message: Any): String {
        return try {
            // 使用反射获取 sender_id 字段
            val senderField = message.javaClass.getDeclaredField("senderId")
            senderField.isAccessible = true
            val senderId = senderField.get(message)
            if (senderId != null) {
                val idField = senderId.javaClass.getDeclaredField("openId")
                idField.isAccessible = true
                (idField.get(senderId) as? String) ?: "unknown"
            } else {
                "unknown"
            }
        } catch (e: Exception) {
            log.debug("Failed to extract sender ID", e)
            "unknown"
        }
    }

    private fun parseContent(messageType: String, contentStr: String): MessageContent {
        return try {
            @Suppress("UNCHECKED_CAST")
            val contentJson = gson.fromJson(contentStr, Map::class.java) as? Map<String, Any>
                ?: return MessageContent.Unknown(rawContent = contentStr)

            when (messageType) {
                "text" -> {
                    val text = contentJson["text"] as? String ?: ""
                    MessageContent.Text(text)
                }

                "image" -> {
                    val imageKey = contentJson["image_key"] as? String ?: ""
                    MessageContent.Image(urls = listOf(imageKey))
                }

                "audio" -> {
                    val fileKey = contentJson["file_key"] as? String ?: ""
                    MessageContent.Audio(url = fileKey)
                }

                "video" -> {
                    val fileKey = contentJson["file_key"] as? String ?: ""
                    MessageContent.Video(url = fileKey)
                }

                "file" -> {
                    val fileKey = contentJson["file_key"] as? String ?: ""
                    val fileName = contentJson["file_name"] as? String ?: ""
                    MessageContent.Document(url = fileKey, fileName = fileName)
                }

                "post" -> {
                    val text = extractPostText(contentJson)
                    MessageContent.Text(text)
                }

                "interactive" -> {
                    val text = extractCardText(contentStr)
                    MessageContent.SystemEvent(
                        eventType = "card_interaction",
                        data = mapOf("raw" to contentStr, "text" to text)
                    )
                }

                "sticker" -> {
                    val fileKey = contentJson["file_key"] as? String ?: ""
                    MessageContent.Image(urls = listOf(fileKey))
                }

                "media" -> {
                    val fileKey = contentJson["file_key"] as? String ?: ""
                    MessageContent.Video(url = fileKey)
                }

                else -> MessageContent.Unknown(rawContent = contentStr)
            }
        } catch (e: Exception) {
            log.debug("Failed to parse content for type $messageType", e)
            MessageContent.Unknown(rawContent = contentStr)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractPostText(contentJson: Map<String, Any>): String {
        val lines = mutableListOf<String>()

        val content = contentJson["content"] as? List<Any> ?: return ""
        for (lineArray in content) {
            val lineParts = mutableListOf<String>()
            val elements = lineArray as? List<Map<String, Any>> ?: continue

            for (element in elements) {
                when (element["tag"] as? String) {
                    "text" -> lineParts.add(element["text"] as? String ?: "")
                    "a" -> lineParts.add(element["text"] as? String ?: "")
                    "at" -> lineParts.add("@${element["user_name"] as? String ?: ""}")
                    "img" -> lineParts.add("[图片]")
                    "media" -> lineParts.add("[媒体]")
                    "emotion" -> lineParts.add(element["emoji_type"] as? String ?: "")
                }
            }
            if (lineParts.isNotEmpty()) {
                lines.add(lineParts.joinToString(""))
            }
        }

        return lines.joinToString("\n")
    }

    private fun extractCardText(contentStr: String): String {
        return try {
            @Suppress("UNCHECKED_CAST")
            val contentJson = gson.fromJson(contentStr, Map::class.java) as? Map<String, Any>
                ?: return ""

            val lines = mutableListOf<String>()
            val elements = contentJson["elements"] as? List<Map<String, Any>> ?: return ""

            for (elem in elements) {
                when (elem["tag"] as? String) {
                    "markdown" -> lines.add(elem["content"] as? String ?: "")
                    "div" -> {
                        val text = elem["text"] as? Map<String, Any>
                        lines.add(text?.get("content") as? String ?: "")
                    }

                    "action" -> {
                        val actions = elem["actions"] as? List<Map<String, Any>>
                        actions?.forEach { action ->
                            val text = action["text"] as? String
                            if (!text.isNullOrBlank()) {
                                lines.add("[$text]")
                            }
                        }
                    }
                }
            }

            lines.joinToString("\n")
        } catch (e: Exception) {
            ""
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseMentions(contentStr: String): List<Mention> {
        return try {
            @Suppress("UNCHECKED_CAST")
            val contentJson = gson.fromJson(contentStr, Map::class.java) as? Map<String, Any>
                ?: return emptyList()
            val mentionsArray =
                contentJson["mentions"] as? List<Map<String, Any>> ?: return emptyList()
            mentionsArray.mapNotNull { mention ->
                val id = mention["id"] as? String
                Mention(
                    userId = id ?: return@mapNotNull null,
                    userName = mention["name"] as? String,
                    key = mention["key"] as? String
                )
            }
        } catch (e: Exception) {
            emptyList()
        }
    }

    /**
     * 检测内容是否包含 Markdown 格式
     */
    private fun containsMarkdown(content: String): Boolean {
        val markdownPatterns = listOf(
            Regex("^#{1,6}\\s", RegexOption.MULTILINE),
            Regex("^\\s*[-*]\\s", RegexOption.MULTILINE),
            Regex("```"),
            Regex("`[^`]+`"),
            Regex("\\*\\*[^*]+"),
            Regex("~~[^~]+"),
            Regex("\\[[^\\]]+\\]\\([^)]+\\)"),
            Regex("^>\\s", RegexOption.MULTILINE)
        )
        return markdownPatterns.any { it.containsMatchIn(content) }
    }

    /**
     * 构建 Markdown 富文本内容
     */
    private fun buildMarkdownPostContent(content: String): String {
        // 飞书 post 格式: content 是二维数组，每行是一个对象数组
        val rows = mutableListOf<List<Map<String, String>>>()
        val lines = content.split("\n")

        for (line in lines) {
            // 检测行内是否包含 Markdown 格式
            val tag = if (containsInlineMarkdown(line)) "md" else "text"
            rows.add(listOf(mapOf("tag" to tag, "text" to line)))
        }

        val outer = mutableMapOf<String, Any>()
        outer["zh_cn"] = mapOf("title" to "", "content" to rows)

        return gson.toJson(outer)
    }

    /**
     * 检测行内是否包含 Markdown 格式（用于决定使用 md 还是 text tag）
     */
    private fun containsInlineMarkdown(line: String): Boolean {
        // 检测行首列表标记（需要用 md tag 让飞书渲染为列表）
        val listPatterns = listOf(
            Regex("^\\s*[-*+]\\s"),   // 无序列表: - * +
            Regex("^\\s*\\d+\\.\\s"),  // 有序列表: 1. 2.
            Regex("^\\s*#+\\s"),       // 标题: # ## ###
        )
        if (listPatterns.any { it.containsMatchIn(line) }) return true

        val inlinePatterns = listOf(
            Regex("```"),              // 代码块
            Regex("`[^`]+`"),          // 行内代码
            Regex("\\*\\*[^*]+"),      // 粗体
            Regex("\\*[^*]+\\*"),      // 斜体
            Regex("~~[^~]+"),          // 删除线
            Regex("\\[.+?]\\(.+?\\)")  // 链接
        )
        return inlinePatterns.any { it.containsMatchIn(line) }
    }

    /**
     * 分割长消息
     */
    private fun splitMessage(text: String, maxLength: Int): List<String> {
        if (text.length <= maxLength) return listOf(text)

        val chunks = mutableListOf<String>()
        var remaining = text

        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLength) {
                chunks.add(remaining)
                break
            }

            // 尝试在换行符处分割
            val splitAt = remaining.lastIndexOf('\n', maxLength)
            if (splitAt > maxLength / 2) {
                chunks.add(remaining.substring(0, splitAt))
                remaining = remaining.substring(splitAt + 1)
            } else {
                // 在空格处分割
                val spaceAt = remaining.lastIndexOf(' ', maxLength)
                if (spaceAt > maxLength / 2) {
                    chunks.add(remaining.substring(0, spaceAt))
                    remaining = remaining.substring(spaceAt + 1)
                } else {
                    // 硬分割
                    chunks.add(remaining.substring(0, maxLength))
                    remaining = remaining.substring(maxLength)
                }
            }
        }

        return chunks
    }

    public fun getBotOpenId(): String = botOpenId
    public fun getBotName(): String = botName
}
