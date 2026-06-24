package io.gateway.platform.telegram

import io.gateway.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject

internal class TelegramMessageParser(
    private val config: TelegramConfig,
    private val json: Json
) {
    @Suppress("UNCHECKED_CAST")
    fun parseUpdate(jsonMap: Map<String, Any>): IncomingMessage? {
        val updateId = (jsonMap["update_id"] as? Number)?.toLong()?.toString() ?: return null

        val message = when {
            jsonMap.containsKey("message") -> jsonMap["message"] as? Map<String, Any>
            jsonMap.containsKey("edited_message") -> jsonMap["edited_message"] as? Map<String, Any>
            jsonMap.containsKey("channel_post") -> jsonMap["channel_post"] as? Map<String, Any>
            jsonMap.containsKey("edited_channel_post") -> jsonMap["edited_channel_post"] as? Map<String, Any>
            else -> null
        } ?: return null

        val chat = message["chat"] as? Map<String, Any> ?: return null
        val from = message["from"] as? Map<String, Any> ?: return null

        val chatId = (chat["id"] as? Number)?.toLong()?.toString() ?: return null
        val userId = (from["id"] as? Number)?.toLong()?.toString() ?: return null
        val chatType = when (chat["type"] as? String) {
            "private" -> ChatType.DIRECT_MESSAGE
            "group", "supergroup" -> ChatType.GROUP
            "channel" -> ChatType.CHANNEL
            else -> ChatType.GROUP
        }

        val messageId = (message["message_id"] as? Number)?.toLong()?.toString() ?: return null
        val isEdited = jsonMap.containsKey("edited_message") || jsonMap.containsKey("edited_channel_post")

        val content = parseContent(message)
        val replyTo = (message["reply_to_message"] as? Map<String, Any>)
            ?.get("message_id")?.let { (it as? Number)?.toLong()?.toString() }

        val source = MessageSource(
            platform = PlatformId.TELEGRAM,
            chatId = chatId,
            chatType = chatType,
            userId = userId,
            userName = (from["username"] as? String)?.takeIf { it.isNotBlank() }
                ?: (from["first_name"] as? String),
            chatName = (chat["title"] as? String)?.takeIf { it.isNotBlank() }
        )

        val metadata = MessageMetadata(
            replyToMessageId = replyTo,
            isEdited = isEdited
        )

        return IncomingMessage(
            id = MessageId(messageId),
            source = source,
            content = content,
            metadata = metadata
        )
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseContent(message: Map<String, Any>): MessageContent {
        return when {
            message.containsKey("text") -> {
                val text = message["text"] as? String ?: ""
                val entities = message["entities"] as? List<Map<String, Any>>

                if (entities != null && entities.isNotEmpty()) {
                    val firstEntity = entities.firstOrNull()
                    if (firstEntity?.get("type") == "bot_command") {
                        val offset = (firstEntity["offset"] as? Number)?.toInt() ?: 0
                        val length = (firstEntity["length"] as? Number)?.toInt() ?: 0
                        if (offset + length <= text.length) {
                            val command = text.substring(offset + 1, offset + length)
                            val args = text.substring(offset + length).trim().split(" ").filter { it.isNotBlank() }
                            return MessageContent.Command(command = command, args = args)
                        }
                    }
                }

                MessageContent.Text(text)
            }
            message.containsKey("photo") -> {
                val photos = message["photo"] as? List<Map<String, Any>> ?: emptyList()
                val urls = photos.mapNotNull { (it["file_id"] as? String) }
                val caption = (message["caption"] as? String)?.takeIf { it.isNotBlank() }
                MessageContent.Image(urls = urls, caption = caption)
            }
            message.containsKey("audio") -> {
                val audio = message["audio"] as? Map<String, Any> ?: return MessageContent.Unknown("")
                MessageContent.Audio(
                    url = audio["file_id"] as? String ?: "",
                    durationSeconds = (audio["duration"] as? Number)?.toInt()?.takeIf { it > 0 }
                )
            }
            message.containsKey("voice") -> {
                val voice = message["voice"] as? Map<String, Any> ?: return MessageContent.Unknown("")
                MessageContent.Audio(
                    url = voice["file_id"] as? String ?: "",
                    durationSeconds = (voice["duration"] as? Number)?.toInt()?.takeIf { it > 0 }
                )
            }
            message.containsKey("video") -> {
                val video = message["video"] as? Map<String, Any> ?: return MessageContent.Unknown("")
                MessageContent.Video(
                    url = video["file_id"] as? String ?: "",
                    durationSeconds = (video["duration"] as? Number)?.toInt()?.takeIf { it > 0 }
                )
            }
            message.containsKey("document") -> {
                val doc = message["document"] as? Map<String, Any> ?: return MessageContent.Unknown("")
                MessageContent.Document(
                    url = doc["file_id"] as? String ?: "",
                    fileName = doc["file_name"] as? String ?: "",
                    mimeType = (doc["mime_type"] as? String)?.takeIf { it.isNotBlank() },
                    sizeBytes = (doc["file_size"] as? Number)?.toLong()?.takeIf { it > 0 }
                )
            }
            message.containsKey("sticker") -> {
                val sticker = message["sticker"] as? Map<String, Any> ?: return MessageContent.Unknown("")
                MessageContent.Image(urls = listOf(sticker["file_id"] as? String ?: ""))
            }
            message.containsKey("location") -> {
                val location = message["location"] as? Map<String, Any> ?: return MessageContent.Unknown("")
                MessageContent.Location(
                    latitude = (location["latitude"] as? Number)?.toDouble() ?: 0.0,
                    longitude = (location["longitude"] as? Number)?.toDouble() ?: 0.0
                )
            }
            message.containsKey("contact") -> {
                val contact = message["contact"] as? Map<String, Any> ?: return MessageContent.Unknown("")
                MessageContent.Contact(
                    name = contact["first_name"] as? String ?: "",
                    phone = (contact["phone_number"] as? String)?.takeIf { it.isNotBlank() }
                )
            }
            else -> MessageContent.Unknown(message.toString())
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
