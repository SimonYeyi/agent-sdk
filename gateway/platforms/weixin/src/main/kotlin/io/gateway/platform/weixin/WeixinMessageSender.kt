package io.gateway.platform.weixin

import io.gateway.model.OutgoingMessage
import io.gateway.model.OutgoingContent
import io.gateway.model.SendResult
import io.gateway.model.PlatformId
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

internal class WeixinMessageSender(
    private val config: WeixinConfig,
    private val httpClient: HttpClient,
    private val json: Json
) {
    suspend fun sendMessage(message: OutgoingMessage): SendResult {
        val body = buildSendBody(message)
        val url = "${config.baseUrl}/cgi-bin/message/send"
        return callApi(url, body)
    }

    suspend fun editMessage(chatId: String, messageId: String, newText: String): SendResult {
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
            put("msg_type", "text")
            put("content", buildJsonObject { put("text", newText) }.toString())
        }
        val url = "${config.baseUrl}/cgi-bin/message/update"
        return callApi(url, body)
    }

    suspend fun deleteMessage(chatId: String, messageId: String): Boolean {
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("message_id", messageId)
        }
        val url = "${config.baseUrl}/cgi-bin/message/delete"
        return try {
            val result = callApi(url, body)
            result is SendResult.Success
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendTypingIndicator(chatId: String): Boolean {
        val body = buildJsonObject {
            put("chat_id", chatId)
            put("command", "Typing")
        }
        val url = "${config.baseUrl}/cgi-bin/message/typing"
        return try {
            val result = callApi(url, body)
            result is SendResult.Success
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun buildSendBody(message: OutgoingMessage): JsonObject {
        return buildJsonObject {
            put("chat_id", message.chatId)
            put("msg_type", getMsgType(message.content))
            message.replyToMessageId?.let { put("reply_to_message_id", it) }

            when (val content = message.content) {
                is OutgoingContent.Text -> put("content", content.text)
                is OutgoingContent.Image -> put("content", content.url)
                is OutgoingContent.Audio -> put("content", content.url)
                is OutgoingContent.Document -> put(
                    "content",
                    buildJsonObject {
                        put("media_id", content.url)
                        put("file_name", content.fileName)
                    }.toString()
                )
            }
        }
    }

    private fun getMsgType(content: OutgoingContent): String {
        return when (content) {
            is OutgoingContent.Text -> "text"
            is OutgoingContent.Image -> "image"
            is OutgoingContent.Audio -> "voice"
            is OutgoingContent.Document -> "file"
        }
    }

    private suspend fun callApi(url: String, body: JsonObject): SendResult {
        return try {
            val response = httpClient.post(url) {
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                header(HttpHeaders.Authorization, "Bearer ${config.loginToken}")
                setBody(body.toString())
            }

            val responseBody = response.bodyAsText()
            val responseJson = json.decodeFromString<Map<String, Any>>(responseBody)

            val errcode = (responseJson["errcode"] as? Number)?.toInt() ?: -1
            if (response.status.value in 200..299 && errcode == 0) {
                val messageId = responseJson["message_id"] as? String ?: ""
                SendResult.Success(
                    messageId = messageId,
                    platform = PlatformId.WEIXIN
                )
            } else {
                val errmsg = responseJson["errmsg"] as? String ?: "Unknown error"
                SendResult.Failure(
                    error = "Weixin API error: $errmsg (code: $errcode)",
                    retryable = errcode == -1 || errcode in 500..599
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            SendResult.Failure(
                error = "Weixin API exception: ${e.message}",
                retryable = true,
                exception = e.javaClass.name
            )
        }
    }
}
