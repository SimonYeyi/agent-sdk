package io.gateway.model

import kotlinx.serialization.Serializable

@Serializable
public data class MessageSource(
    val platform: PlatformId,
    val chatId: String,
    val chatType: ChatType,
    val userId: String,
    val userName: String? = null,
    val chatName: String? = null,
    val threadId: String? = null
) {
    public fun sessionKey(): String = "${platform.value}:$chatId:$userId"
}
