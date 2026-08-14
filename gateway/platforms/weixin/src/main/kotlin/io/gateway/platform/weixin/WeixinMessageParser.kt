package io.gateway.platform.weixin

import io.gateway.model.MessageContent.Resource
import io.gateway.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class WeixinMessageParser(
    private val config: WeixinConfig,
    private val json: Json
) {
    fun parseMessage(jsonMap: JsonObject): IncomingMessage? {
        val messageId = jsonMap["message_id"]?.jsonPrimitive?.content ?: return null
        val chatId = jsonMap["chat_id"]?.jsonPrimitive?.content ?: return null
        val userId = jsonMap["sender_id"]?.jsonPrimitive?.content ?: return null

        val chatType = when (jsonMap["chat_type"]?.jsonPrimitive?.content) {
            "private" -> ChatType.DIRECT_MESSAGE
            "group" -> ChatType.GROUP
            else -> ChatType.DIRECT_MESSAGE
        }

        val msgType = jsonMap["msg_type"]?.jsonPrimitive?.content ?: "text"
        val content = parseContent(msgType, jsonMap)

        val source = MessageSource(
            platform = PlatformId.WEIXIN,
            chatId = chatId,
            chatType = chatType,
            userId = userId,
            userName = jsonMap["sender_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
            chatName = jsonMap["chat_name"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        )

        val metadata = MessageMetadata(
            replyToMessageId = jsonMap["reply_to_message_id"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
        )

        return IncomingMessage(
            id = MessageId(messageId),
            source = source,
            content = content,
            metadata = metadata
        )
    }

    private fun parseContent(msgType: String, json: JsonObject): MessageContent {
        return when (msgType) {
            "text" -> {
                val text = json["content"]?.jsonPrimitive?.content ?: ""
                MessageContent.Text(text)
            }
            "image" -> {
                val imageUrl = json["image_url"]?.jsonPrimitive?.content ?: ""
                MessageContent.Image(parts = listOf(Resource.Http(imageUrl)))
            }
            "voice" -> {
                val voiceUrl = json["voice_url"]?.jsonPrimitive?.content ?: ""
                MessageContent.Audio(resource = Resource.Http(voiceUrl))
            }
            "video" -> {
                val videoUrl = json["video_url"]?.jsonPrimitive?.content ?: ""
                MessageContent.Video(resource = Resource.Http(videoUrl))
            }
            "file" -> {
                val fileUrl = json["file_url"]?.jsonPrimitive?.content ?: ""
                val fileName = json["file_name"]?.jsonPrimitive?.content ?: ""
                MessageContent.Document(resource = Resource.Http(fileUrl), fileName = fileName)
            }
            "location" -> {
                val lat = json["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val lng = json["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                val name = json["label"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                MessageContent.Location(latitude = lat, longitude = lng, name = name)
            }
            "event" -> {
                val eventType = json["event_type"]?.jsonPrimitive?.content ?: ""
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
