package io.gateway.platform.telegram

import io.gateway.model.OutgoingMessage
import io.gateway.model.OutgoingContent
import io.gateway.model.SendResult
import io.gateway.model.PlatformId
import io.gateway.model.ParseMode
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

internal class TelegramMessageSender(
    private val config: TelegramConfig,
    private val httpClient: HttpClient,
    private val json: Json
) {
    suspend fun sendMessage(message: OutgoingMessage): SendResult {
        val method = getMethod(message.content)
        val params = buildParams(message)
        return callApi(method, params)
    }

    suspend fun editMessage(chatId: String, messageId: String, newText: String): SendResult {
        val params = buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId.toIntOrNull() ?: 0)
            put("text", newText)
        }
        return callApi("editMessageText", params)
    }

    suspend fun deleteMessage(chatId: String, messageId: String): Boolean {
        val params = buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId.toIntOrNull() ?: 0)
        }
        return try {
            val result = callApi("deleteMessage", params)
            result is SendResult.Success
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendTypingIndicator(chatId: String): Boolean {
        val params = buildJsonObject {
            put("chat_id", chatId)
            put("action", "typing")
        }
        return try {
            val result = callApi("sendChatAction", params)
            result is SendResult.Success
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun getMethod(content: OutgoingContent): String {
        return when (content) {
            is OutgoingContent.Text -> "sendMessage"
            is OutgoingContent.Image -> "sendPhoto"
            is OutgoingContent.Audio -> "sendAudio"
            is OutgoingContent.Document -> "sendDocument"
        }
    }

    private fun buildParams(message: OutgoingMessage): JsonObject {
        return buildJsonObject {
            put("chat_id", message.chatId)
            message.replyToMessageId?.let {
                put("reply_to_message_id", it.toIntOrNull() ?: 0)
            }
            put("disable_web_page_preview", message.metadata.disablePreview)
            put("disable_notification", message.metadata.disableNotification)

            when (val content = message.content) {
                is OutgoingContent.Text -> {
                    put("text", content.text)
                    if (content.parseMode != ParseMode.PLAIN) {
                        put("parse_mode", parseModeToString(content.parseMode))
                    }
                }
                is OutgoingContent.Image -> {
                    put("photo", content.url)
                    content.caption?.let { put("caption", it) }
                }
                is OutgoingContent.Audio -> put("audio", content.url)
                is OutgoingContent.Document -> put("document", content.url)
            }
        }
    }

    private fun parseModeToString(parseMode: ParseMode): String {
        return when (parseMode) {
            ParseMode.PLAIN -> ""
            ParseMode.MARKDOWN -> "Markdown"
            ParseMode.MARKDOWN_V2 -> "MarkdownV2"
            ParseMode.HTML -> "HTML"
        }
    }

    private suspend fun callApi(method: String, params: JsonObject): SendResult {
        val url = "${config.apiBaseUrl}/bot${config.botToken}/$method"

        return try {
            val response = httpClient.post(url) {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(params.toString())
            }

            val body = response.bodyAsText()
            val jsonResponse = json.decodeFromString<Map<String, Any>>(body)

            if (response.status.value in 200..299 && jsonResponse["ok"] == true) {
                val result = jsonResponse["result"] as? Map<String, Any>
                val messageId = (result?.get("message_id") as? Number)?.toInt()?.toString() ?: ""
                SendResult.Success(
                    messageId = messageId,
                    platform = PlatformId.TELEGRAM
                )
            } else {
                val description = jsonResponse["description"] as? String ?: "Unknown error"
                SendResult.Failure(
                    error = "Telegram API error: $description",
                    retryable = response.status.value in 500..599
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.Failure(
                error = "Telegram API exception: ${e.message}",
                retryable = true,
                exception = e.javaClass.name
            )
        }
    }
}
