package io.gateway.platform.feishu

import io.gateway.model.*
import io.gateway.util.gatewayLog
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive

internal class FeishuMessageParser(
    private val config: FeishuConfig,
    private val json: Json
) {
    private val log = gatewayLog("FeishuMessageParser")

    fun parseMessageEvent(jsonString: String): IncomingMessage? {
        return try {
            val event = json.decodeFromString<JsonObject>(jsonString)
            parseMessageEvent(event)
        } catch (e: Exception) {
            log.warn("Failed to parse message event", e)
            null
        }
    }

    private fun parseMessageEvent(json: JsonObject): IncomingMessage? {
        val header = json["header"]?.jsonObject ?: return null
        val event = json["event"]?.jsonObject ?: return null

        val eventType = header["event_type"]?.jsonPrimitive?.content
        if (eventType != "im.message.receive_v1") return null

        val message = event["message"]?.jsonObject ?: return null
        val sender = event["sender"]?.jsonObject ?: return null

        val messageId = message["message_id"]?.jsonPrimitive?.content ?: return null
        val chatId = message["chat_id"]?.jsonPrimitive?.content ?: return null
        val chatType = when (message["chat_type"]?.jsonPrimitive?.content) {
            "p2p" -> ChatType.DIRECT_MESSAGE
            "group" -> ChatType.GROUP
            else -> ChatType.GROUP
        }

        val senderId = sender["sender_id"]?.jsonObject
        val userId = (senderId?.get("user_id")?.jsonPrimitive?.content
            ?: senderId?.get("open_id")?.jsonPrimitive?.content) ?: "unknown"

        val messageType = message["message_type"]?.jsonPrimitive?.content ?: "text"
        val contentStr = message["content"]?.jsonPrimitive?.content ?: "{}"
        val content = parseContent(messageType, contentStr)

        val mentions = parseMentions(message)

        val source = MessageSource(
            platform = PlatformId.FEISHU,
            chatId = chatId,
            chatType = chatType,
            userId = userId,
            chatName = null,
            userName = sender["sender_id"]?.jsonPrimitive?.content,
            threadId = message["thread_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
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
        return try {
            val contentJson = json.decodeFromString<JsonObject>(contentStr)
            when (messageType) {
                "text" -> {
                    val text = contentJson["text"]?.jsonPrimitive?.content ?: ""
                    MessageContent.Text(text)
                }
                "image" -> {
                    val imageKey = contentJson["image_key"]?.jsonPrimitive?.content ?: ""
                    MessageContent.Image(urls = listOf(imageKey))
                }
                "audio" -> {
                    val fileKey = contentJson["file_key"]?.jsonPrimitive?.content ?: ""
                    MessageContent.Audio(url = fileKey)
                }
                "video" -> {
                    val fileKey = contentJson["file_key"]?.jsonPrimitive?.content ?: ""
                    MessageContent.Video(url = fileKey)
                }
                "file" -> {
                    val fileKey = contentJson["file_key"]?.jsonPrimitive?.content ?: ""
                    val fileName = contentJson["file_name"]?.jsonPrimitive?.content ?: ""
                    MessageContent.Document(url = fileKey, fileName = fileName)
                }
                "post" -> {
                    val title = contentJson["title"]?.jsonPrimitive?.content ?: ""
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
                    val fileKey = contentJson["file_key"]?.jsonPrimitive?.content ?: ""
                    MessageContent.Image(urls = listOf(fileKey))
                }
                else -> {
                    MessageContent.Unknown(rawContent = contentStr)
                }
            }
        } catch (e: Exception) {
            log.debug("Failed to parse content for message type $messageType", e)
            MessageContent.Unknown(rawContent = contentStr)
        }
    }

    private fun extractPostText(contentJson: JsonObject): String {
        val contentArray = contentJson["content"]?.jsonArray ?: return ""
        val lines = mutableListOf<String>()

        for (lineArray in contentArray) {
            val lineParts = mutableListOf<String>()
            val elements = lineArray.jsonArray ?: continue
            for (element in elements) {
                val elem = element.jsonObject ?: continue
                when (elem["tag"]?.jsonPrimitive?.content) {
                    "text" -> lineParts.add(elem["text"]?.jsonPrimitive?.content ?: "")
                    "a" -> lineParts.add(elem["text"]?.jsonPrimitive?.content ?: "")
                    "at" -> lineParts.add("@${elem["user_name"]?.jsonPrimitive?.content ?: ""}")
                    "img" -> lineParts.add("[图片]")
                    "media" -> lineParts.add("[媒体]")
                    "emotion" -> lineParts.add(elem["emoji_type"]?.jsonPrimitive?.content ?: "")
                }
            }
            if (lineParts.isNotEmpty()) {
                lines.add(lineParts.joinToString(""))
            }
        }

        return lines.joinToString("\n")
    }

    private fun parseMentions(message: JsonObject): List<Mention> {
        val mentionsArray = message["mentions"]?.jsonArray ?: return emptyList()
        return mentionsArray.mapNotNull { mention ->
            val m = mention.jsonObject ?: return@mapNotNull null
            Mention(
                userId = m["id"]?.jsonPrimitive?.content ?: "",
                userName = m["name"]?.jsonPrimitive?.content,
                key = m["key"]?.jsonPrimitive?.content
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
