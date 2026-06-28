package io.gateway.platform.feishu

import com.google.gson.Gson
import com.lark.oapi.Client
import com.lark.oapi.core.token.AccessTokenType
import com.lark.oapi.service.im.v1.enums.ReceiveIdTypeEnum
import com.lark.oapi.service.im.v1.model.CreateMessageReq
import com.lark.oapi.service.im.v1.model.CreateMessageReqBody
import com.lark.oapi.service.im.v1.model.DeleteMessageReq
import com.lark.oapi.service.im.v1.model.UpdateMessageReq
import com.lark.oapi.service.im.v1.model.UpdateMessageReqBody
import io.gateway.model.PlatformId
import io.gateway.model.SendResult
import io.gateway.util.gatewayLog

public class FeishuApiClient(
    appId: String,
    appSecret: String,
    private val gson: Gson
) {
    private val log = gatewayLog("FeishuApiClient")
    private var apiClient: Client = Client.Builder(appId, appSecret).build()

    public data class BotInfo(public val openId: String, public val name: String)
    public data class ReactionResult(public val reactionId: String?, public val success: Boolean)

    public enum class ReactionType(public val emojiType: String) {
        ACK("Typing"),
        DONE("Done"),
        FAILURE("CrossMark")
    }

    public suspend fun sendMessage(
        chatId: String,
        msgType: String,
        content: String,
        replyTo: String? = null
    ): SendResult {
        return try {
            val request = CreateMessageReq.newBuilder()
                .receiveIdType(ReceiveIdTypeEnum.CHAT_ID.getValue())
                .createMessageReqBody(
                    CreateMessageReqBody.newBuilder()
                        .receiveId(chatId)
                        .msgType(msgType)
                        .content(content)
                        .build()
                )
                .build()

            val response = apiClient.im().message().create(request)

            if (response.code == 0) {
                SendResult.Success(
                    messageId = response.data?.messageId ?: "",
                    platform = PlatformId.FEISHU
                )
            } else {
                SendResult.Failure(
                    error = "Send failed: ${response.msg}",
                    retryable = false
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to send message", e)
            SendResult.Failure(
                error = "Send exception: ${e.message}",
                retryable = true,
                exception = e.javaClass.name
            )
        }
    }

    public suspend fun updateMessage(messageId: String, newText: String): SendResult {
        return try {
            val request = UpdateMessageReq.newBuilder()
                .messageId(messageId)
                .updateMessageReqBody(
                    UpdateMessageReqBody.newBuilder()
                        .content("""{"text":"${newText.replace("\"", "\\\"")}"}""")
                        .build()
                )
                .build()

            val response = apiClient.im().message().update(request)

            if (response.code == 0) {
                SendResult.Success(
                    messageId = messageId,
                    platform = PlatformId.FEISHU
                )
            } else {
                SendResult.Failure(
                    error = "Edit failed: ${response.msg}",
                    retryable = false
                )
            }
        } catch (e: Exception) {
            log.warn("Failed to edit message", e)
            SendResult.Failure(
                error = "Edit exception: ${e.message}",
                retryable = true,
                exception = e.javaClass.name
            )
        }
    }

    public suspend fun deleteMessage(messageId: String): Boolean {
        return try {
            val request = DeleteMessageReq.newBuilder()
                .messageId(messageId)
                .build()

            val response = apiClient.im().message().delete(request)
            response.code == 0
        } catch (e: Exception) {
            log.warn("Failed to delete message $messageId", e)
            false
        }
    }

    public fun getBotInfo(): BotInfo? {
        return try {
            val response = apiClient.get(
                "/open-apis/bot/v3/info",
                null,
                AccessTokenType.App
            )
            val json = gson.fromJson(String(response.body), Map::class.java)
            val data = (json as? Map<String, Any>)?.get("bot") as? Map<String, Any>
            if (data != null) {
                BotInfo(
                    openId = data["open_id"] as? String ?: "",
                    name = data["app_name"] as? String ?: "Bot"
                )
            } else {
                null
            }
        } catch (e: Exception) {
            log.warn("Failed to get bot info", e)
            null
        }
    }

    public fun addReaction(messageId: String, reactionType: ReactionType): ReactionResult {
        return try {
            val payload = mapOf(
                "reaction_type" to mapOf("emoji_type" to reactionType.emojiType)
            )
            val response = apiClient.post(
                "/open-apis/im/v1/messages/$messageId/reactions",
                payload,
                AccessTokenType.App
            )
            val json = gson.fromJson(String(response.body), Map::class.java)
            val code = (json["code"] as? Number)?.toInt() ?: -1
            if (code == 0) {
                val reactionId = (json["data"] as? Map<String, Any>)
                    ?.get("reaction_id") as? String ?: ""
                ReactionResult(reactionId, true)
            } else {
                log.warn("Failed to add reaction: code=$code")
                ReactionResult(null, false)
            }
        } catch (e: Exception) {
            log.warn("Failed to add reaction: ${e.message}")
            ReactionResult(null, false)
        }
    }

    public fun removeReaction(messageId: String, reactionId: String): Boolean {
        return try {
            val response = apiClient.delete(
                "/open-apis/im/v1/messages/$messageId/reactions/$reactionId",
                null,
                AccessTokenType.App
            )
            val json = gson.fromJson(String(response.body), Map::class.java)
            val code = (json["code"] as? Number)?.toInt() ?: -1
            code == 0
        } catch (e: Exception) {
            log.warn("Failed to remove reaction: ${e.message}")
            false
        }
    }
}