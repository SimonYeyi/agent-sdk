package io.gateway.platform.feishu

public data class FeishuConfig(
    val appId: String,
    val appSecret: String,
    val domain: String = "https://open.feishu.cn",
    val verificationToken: String? = null,
    val encryptKey: String? = null,
    val allowedUsers: Set<String> = emptySet(),
    val allowedChats: Set<String> = emptySet(),
    val sendAckReaction: Boolean = true,
    val ackEmoji: String = "Clapping"
)
