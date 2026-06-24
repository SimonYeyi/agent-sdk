package io.gateway.platform.feishu

import io.gateway.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal class FeishuMessageParser(
    private val config: FeishuConfig,
    private val json: Json
) {
    fun parseMessageEvent(jsonString: String): IncomingMessage? {
        return runCatching {
            val event = json.decodeFromString<Map<String, Any>>(jsonString)
            parseMessageEvent(event)
        }.getOrNull()
    }

    private fun parseMessageEvent(json: Map<String, Any>): IncomingMessage? {
        val header = json["header"] as? Map<String, Any> ?: return null
        val event = json["event"] as? Map<String, Any> ?: return null

        val eventType = header["event_type"] as? String
        if (eventType != "im.message.receive_v1") return null

        val message = event["message"] as? Map<String, Any> ?: return null
        val sender = event["sender"] as? Map<String, Any> ?: return null

        val messageId = message["message_id"] as? String ?: return null
        val chatId = message["chat_id"] as? String ?: return null
        val chatType = when (message["chat_type"] as? String) {
            "p2p" -> ChatType.DIRECT_MESSAGE
            "group" -> ChatType.GROUP
            else -> ChatType.GROUP
        }

        val senderId = sender["sender_id"] as? Map<String, Any>
        val userId = (senderId?.get("user_id") ?: senderId?.get("open_id")) as? String ?: "unknown"

        val messageType = message["message_type"] as? String ?: "text"
        val contentStr = message["content"] as? String ?: "{}"
        val content = parseContent(messageType, contentStr)

        val mentions = parseMentions(message)

        val source = MessageSource(
            platform = PlatformId.FEISHU,
            chatId = chatId,
            chatType = chatType,
            userId = userId,
            chatName = null,
            userName = sender["sender_id"] as? String,
            threadId = (message["thread_id"] as? String)?.takeIf { it.isNotBlank() }
        )

        val metadata = MessageMetadata(
            mentions = mentions
        )

        return IncomingMessage(
            id = MessageId(messageId),
            source = source,
            content = content,
            metadata = metadata
        )
    }

    private fun parseContent(messageType: String, contentStr: String): MessageContent {
        return runCatching {
            val contentJson = json.decodeFromString<Map<String, Any>>(contentStr)
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
                    val title = contentJson["title"] as? String ?: ""
                    val content = extractPostText(contentJson)
                    val text = if (title.isNotBlank()) "$title\n$content" else content
                    MessageContent.Text(text)
                }
                "interactive" -> {
                    MessageContent.SystemEvent(
                        eventType = "card_interaction",
                        data = mapOf("raw" to contentStr)
                    )
                }
                "sticker" -> {
                    val fileKey = contentJson["file_key"] as? String ?: ""
                    MessageContent.Image(urls = listOf(fileKey))
                }
                else -> {
                    MessageContent.Unknown(rawContent = contentStr)
                }
            }
        }.getOrElse {
            MessageContent.Unknown(rawContent = contentStr)
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun extractPostText(contentJson: Map<String, Any>): String {
        val contentArray = contentJson["content"] as? List<Any> ?: return ""
        val lines = mutableListOf<String>()

        for (lineArray in contentArray) {
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

    @Suppress("UNCHECKED_CAST")
    private fun parseMentions(message: Map<String, Any>): List<Mention> {
        val mentionsArray = message["mentions"] as? List<Map<String, Any>> ?: return emptyList()
        return mentionsArray.map { mention ->
            Mention(
                userId = mention["id"] as? String ?: "",
                userName = mention["name"] as? String,
                key = mention["key"] as? String
            )
        }
    }

    fun isAllowed(message: IncomingMessage): Boolean {
        val userId = message.source.userId
        val chatId = message.source.chatId

        val userAllowed = config.allowedUsers.isEmpty() || userId in config.allowedUsers
        val chatAllowed = config.allowedChats.isEmpty() || chatId in config.allowedChats

        return userAllowed && chatAllowed
    }
}
