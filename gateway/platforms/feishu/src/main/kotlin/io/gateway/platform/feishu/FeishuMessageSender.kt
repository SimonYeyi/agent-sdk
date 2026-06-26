package io.gateway.platform.feishu

import io.gateway.model.OutgoingMessage
import io.gateway.model.OutgoingContent
import io.gateway.model.SendResult
import io.gateway.model.PlatformId
import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.patch
import io.ktor.client.request.delete
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.client.statement.readRawBytes
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
private data class SendMessageRequest(
    val receive_id: String,
    val msg_type: String,
    val content: String,
    val uuid: String = UUID.randomUUID().toString()
)

internal class FeishuMessageSender(
    private val config: FeishuConfig,
    private val httpClient: HttpClient,
    private val json: Json,
    private val tokenProvider: () -> String?
) {
    suspend fun sendMessage(message: OutgoingMessage): SendResult {
        val token = tokenProvider() ?: return SendResult.Failure(
            error = "No access token available",
            retryable = true
        )

        val (msgType, content) = buildContent(message.content)

        try {
            val response = httpClient.post("${config.domain}/open-apis/im/v1/messages?receive_id_type=chat_id") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(buildJsonObject {
                    put("receive_id", message.chatId)
                    put("msg_type", msgType)
                    put("content", content)
                    put("uuid", UUID.randomUUID().toString())
                })
            }

            val responseBody = response.bodyAsText()
            val responseJson = json.decodeFromString<JsonObject>(responseBody)

            if (response.status.value in 200..299 && responseJson["code"]?.jsonPrimitive?.content?.toIntOrNull() == 0) {
                val data = responseJson["data"]?.jsonObject
                val messageId = data?.get("message_id")?.jsonPrimitive?.content ?: ""
                return SendResult.Success(
                    messageId = messageId,
                    platform = PlatformId.FEISHU
                )
            } else {
                val errorMsg = responseJson["msg"]?.jsonPrimitive?.content ?: "Unknown error"
                return SendResult.Failure(
                    error = "Send failed: $errorMsg",
                    retryable = response.status.value in 500..599
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return SendResult.Failure(
                error = "Send exception: ${e.message}",
                retryable = true,
                exception = e.javaClass.name
            )
        }
    }

    suspend fun editMessage(chatId: String, messageId: String, newText: String): SendResult {
        val token = tokenProvider() ?: return SendResult.Failure(
            error = "No access token available",
            retryable = true
        )

        try {
            val response = httpClient.patch("${config.domain}/open-apis/im/v1/messages/$messageId") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(buildJsonObject {
                    put("content", buildJsonObject { put("text", newText) }.toString())
                })
            }

            val responseBody = response.bodyAsText()
            val responseJson = json.decodeFromString<JsonObject>(responseBody)

            if (response.status.value in 200..299 && responseJson["code"]?.jsonPrimitive?.content?.toIntOrNull() == 0) {
                return SendResult.Success(
                    messageId = messageId,
                    platform = PlatformId.FEISHU
                )
            } else {
                val errorMsg = responseJson["msg"]?.jsonPrimitive?.content ?: "Unknown error"
                return SendResult.Failure(
                    error = "Edit failed: $errorMsg",
                    retryable = false
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            return SendResult.Failure(
                error = "Edit exception: ${e.message}",
                retryable = true,
                exception = e.javaClass.name
            )
        }
    }

    suspend fun deleteMessage(chatId: String, messageId: String): Boolean {
        val token = tokenProvider() ?: return false

        return try {
            val response = httpClient.delete("${config.domain}/open-apis/im/v1/messages/$messageId") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }

            val responseBody = response.bodyAsText()
            val responseJson = json.decodeFromString<JsonObject>(responseBody)
            response.status.value in 200..299 && responseJson["code"]?.jsonPrimitive?.content?.toIntOrNull() == 0
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    suspend fun sendTypingIndicator(chatId: String): Boolean {
        val token = tokenProvider() ?: return false

        return try {
            val response = httpClient.post("${config.domain}/open-apis/im/v1/messages/batch_send_urgent_app") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(buildJsonObject {
                    put("user_id_type", "app_id")
                    put("chat_id", chatId)
                })
            }
            response.status.value in 200..299
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    suspend fun addReaction(messageId: String, emoji: String): Boolean {
        val token = tokenProvider() ?: return false

        return try {
            val response = httpClient.post("${config.domain}/open-apis/im/v1/messages/$messageId/reactions") {
                header(HttpHeaders.Authorization, "Bearer $token")
                header(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                setBody(buildJsonObject {
                    put("reaction_type", "emoji")
                    put("reaction_id", emoji)
                })
            }
            response.status.value in 200..299
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            false
        }
    }

    private fun buildContent(content: OutgoingContent): Pair<String, String> {
        return when (content) {
            is OutgoingContent.Text -> {
                "text" to buildJsonObject { put("text", content.text) }.toString()
            }
            is OutgoingContent.Image -> {
                "image" to buildJsonObject { put("image_key", content.url) }.toString()
            }
            is OutgoingContent.Audio -> {
                "audio" to buildJsonObject { put("file_key", content.url) }.toString()
            }
            is OutgoingContent.Document -> {
                "file" to buildJsonObject {
                    put("file_key", content.url)
                    put("file_name", content.fileName)
                }.toString()
            }
        }
    }

    suspend fun downloadFile(fileKey: String): ByteArray? {
        val token = tokenProvider() ?: return null

        return try {
            val response = httpClient.get("${config.domain}/open-apis/im/v1/messages/files/$fileKey") {
                header(HttpHeaders.Authorization, "Bearer $token")
            }
            if (response.status.value in 200..299) {
                response.readRawBytes()
            } else null
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            null
        }
    }
}
