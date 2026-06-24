package io.gateway.platform.weixin

import io.gateway.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject

internal class WeixinMessageParser(
    private val config: WeixinConfig,
    private val json: Json
) {
    @Suppress("UNCHECKED_CAST")
    fun parseMessage(jsonMap: Map<String, Any>): IncomingMessage? {
        val messageId = jsonMap["message_id"] as? String ?: return null
        val chatId = jsonMap["chat_id"] as? String ?: return null
        val userId = jsonMap["sender_id"] as? String ?: return null

        val chatType = when (jsonMap["chat_type"] as? String) {
            "private" -> ChatType.DIRECT_MESSAGE
            "group" -> ChatType.GROUP
            else -> ChatType.DIRECT_MESSAGE
        }

        val msgType = jsonMap["msg_type"] as? String ?: "text"
        val content = parseContent(msgType, jsonMap)

        val source = MessageSource(
            platform = PlatformId.WEIXIN,
            chatId = chatId,
            chatType = chatType,
            userId = userId,
            userName = (jsonMap["sender_name"] as? String)?.takeIf { it.isNotBlank() },
            chatName = (jsonMap["chat_name"] as? String)?.takeIf { it.isNotBlank() }
        )

        val metadata = MessageMetadata(
            replyToMessageId = (jsonMap["reply_to_message_id"] as? String)?.takeIf { it.isNotBlank() }
        )

        return IncomingMessage(
            id = MessageId(messageId),
            source = source,
            content = content,
            metadata = metadata
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseContent(msgType: String, json: Map<String, Any>): MessageContent {
        return when (msgType) {
            "text" -> {
                val text = json["content"] as? String ?: ""
                MessageContent.Text(text)
            }
            "image" -> {
                val imageUrl = json["image_url"] as? String ?: ""
                MessageContent.Image(urls = listOf(imageUrl))
            }
            "voice" -> {
                val voiceUrl = json["voice_url"] as? String ?: ""
                MessageContent.Audio(url = voiceUrl)
            }
            "video" -> {
                val videoUrl = json["video_url"] as? String ?: ""
                MessageContent.Video(url = videoUrl)
            }
            "file" -> {
                val fileUrl = json["file_url"] as? String ?: ""
                val fileName = json["file_name"] as? String ?: ""
                MessageContent.Document(url = fileUrl, fileName = fileName)
            }
            "location" -> {
                val lat = (json["latitude"] as? Number)?.toDouble() ?: 0.0
                val lng = (json["longitude"] as? Number)?.toDouble() ?: 0.0
                val name = (json["label"] as? String)?.takeIf { it.isNotBlank() }
                MessageContent.Location(latitude = lat, longitude = lng, name = name)
            }
            "event" -> {
                val eventType = json["event_type"] as? String ?: ""
                MessageContent.SystemEvent(eventType = eventType)
            }
            else -> MessageContent.Unknown(rawContent = json.toString())
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
