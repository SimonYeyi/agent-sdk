package io.gateway.platform.feishu

import com.google.gson.Gson
import io.gateway.model.ChatType
import io.gateway.model.IncomingMessage
import io.gateway.model.Mention
import io.gateway.model.MessageContent
import io.gateway.model.MessageId
import io.gateway.model.MessageMetadata
import io.gateway.model.MessageSource
import io.gateway.model.PlatformId
import io.gateway.util.gatewayLog
import com.lark.oapi.service.im.v1.model.P2MessageReceiveV1

internal class FeishuMessageParser(
    private val gson: Gson = Gson()
) {
    private val log = gatewayLog("FeishuMessageParser")

    internal fun parseSdkEvent(event: P2MessageReceiveV1): IncomingMessage? {
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

    @Suppress("UNCHECKED_CAST")
    internal fun parseContent(messageType: String, contentStr: String): MessageContent {
        return try {
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
    internal fun extractPostText(contentJson: Map<String, Any>): String {
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

    internal fun extractCardText(contentStr: String): String {
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
    internal fun parseMentions(contentStr: String): List<Mention> {
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

    internal fun buildReactionCommand(
        emojiType: String,
        messageId: String,
        operatorId: String
    ): IncomingMessage {
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

        return IncomingMessage(
            id = MessageId(messageId),
            source = source,
            content = content,
            metadata = MessageMetadata()
        )
    }
}