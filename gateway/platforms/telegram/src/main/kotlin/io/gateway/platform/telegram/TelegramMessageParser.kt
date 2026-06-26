package io.gateway.platform.telegram

import io.gateway.model.*
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class TelegramMessageParser(
    private val config: TelegramConfig,
    private val json: Json
) {
    fun parseUpdate(jsonMap: JsonObject): IncomingMessage? {
        val updateId = jsonMap["update_id"]?.jsonPrimitive?.content?.toLongOrNull()?.toString() ?: return null

        val message = when {
            jsonMap.containsKey("message") -> jsonMap["message"]?.jsonObject
            jsonMap.containsKey("edited_message") -> jsonMap["edited_message"]?.jsonObject
            jsonMap.containsKey("channel_post") -> jsonMap["channel_post"]?.jsonObject
            jsonMap.containsKey("edited_channel_post") -> jsonMap["edited_channel_post"]?.jsonObject
            else -> null
        } ?: return null

        val chat = message["chat"]?.jsonObject ?: return null
        val from = message["from"]?.jsonObject ?: return null

        val chatId = chat["id"]?.jsonPrimitive?.content?.toLongOrNull()?.toString() ?: return null
        val userId = from["id"]?.jsonPrimitive?.content?.toLongOrNull()?.toString() ?: return null
        val chatType = when (chat["type"]?.jsonPrimitive?.content) {
            "private" -> ChatType.DIRECT_MESSAGE
            "group", "supergroup" -> ChatType.GROUP
            "channel" -> ChatType.CHANNEL
            else -> ChatType.GROUP
        }

        val messageId = message["message_id"]?.jsonPrimitive?.content?.toLongOrNull()?.toString() ?: return null
        val isEdited = jsonMap.containsKey("edited_message") || jsonMap.containsKey("edited_channel_post")

        val content = parseContent(message)
        val replyTo = message["reply_to_message"]?.jsonObject
            ?.get("message_id")?.jsonPrimitive?.content?.toLongOrNull()?.toString()

        val source = MessageSource(
            platform = PlatformId.TELEGRAM,
            chatId = chatId,
            chatType = chatType,
            userId = userId,
            userName = from["username"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                ?: from["first_name"]?.jsonPrimitive?.content,
            chatName = chat["title"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
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

    private fun parseContent(message: JsonObject): MessageContent {
        return when {
            message.containsKey("text") -> {
                val text = message["text"]?.jsonPrimitive?.content ?: ""
                val entities = message["entities"]?.jsonArray

                if (entities != null && entities.isNotEmpty()) {
                    val firstEntity = entities.firstOrNull()?.jsonObject
                    if (firstEntity?.get("type")?.jsonPrimitive?.content == "bot_command") {
                        val offset = firstEntity["offset"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
                        val length = firstEntity["length"]?.jsonPrimitive?.content?.toIntOrNull() ?: 0
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
                val photos = message["photo"]?.jsonArray ?: JsonArray(emptyList())
                val urls = photos.mapNotNull { it.jsonObject?.get("file_id")?.jsonPrimitive?.content }
                val caption = message["caption"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
                MessageContent.Image(urls = urls, caption = caption)
            }
            message.containsKey("audio") -> {
                val audio = message["audio"]?.jsonObject ?: return MessageContent.Unknown("")
                MessageContent.Audio(
                    url = audio["file_id"]?.jsonPrimitive?.content ?: "",
                    durationSeconds = audio["duration"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }
                )
            }
            message.containsKey("voice") -> {
                val voice = message["voice"]?.jsonObject ?: return MessageContent.Unknown("")
                MessageContent.Audio(
                    url = voice["file_id"]?.jsonPrimitive?.content ?: "",
                    durationSeconds = voice["duration"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }
                )
            }
            message.containsKey("video") -> {
                val video = message["video"]?.jsonObject ?: return MessageContent.Unknown("")
                MessageContent.Video(
                    url = video["file_id"]?.jsonPrimitive?.content ?: "",
                    durationSeconds = video["duration"]?.jsonPrimitive?.content?.toIntOrNull()?.takeIf { it > 0 }
                )
            }
            message.containsKey("document") -> {
                val doc = message["document"]?.jsonObject ?: return MessageContent.Unknown("")
                MessageContent.Document(
                    url = doc["file_id"]?.jsonPrimitive?.content ?: "",
                    fileName = doc["file_name"]?.jsonPrimitive?.content ?: "",
                    mimeType = doc["mime_type"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() },
                    sizeBytes = doc["file_size"]?.jsonPrimitive?.content?.toLongOrNull()?.takeIf { it > 0 }
                )
            }
            message.containsKey("sticker") -> {
                val sticker = message["sticker"]?.jsonObject ?: return MessageContent.Unknown("")
                MessageContent.Image(urls = listOf(sticker["file_id"]?.jsonPrimitive?.content ?: ""))
            }
            message.containsKey("location") -> {
                val location = message["location"]?.jsonObject ?: return MessageContent.Unknown("")
                MessageContent.Location(
                    latitude = location["latitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0,
                    longitude = location["longitude"]?.jsonPrimitive?.content?.toDoubleOrNull() ?: 0.0
                )
            }
            message.containsKey("contact") -> {
                val contact = message["contact"]?.jsonObject ?: return MessageContent.Unknown("")
                MessageContent.Contact(
                    name = contact["first_name"]?.jsonPrimitive?.content ?: "",
                    phone = contact["phone_number"]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }
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
