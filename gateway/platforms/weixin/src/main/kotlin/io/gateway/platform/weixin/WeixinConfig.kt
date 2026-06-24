package io.gateway.platform.weixin

public data class WeixinConfig(
    val accountId: String,
    val loginToken: String,
    val baseUrl: String = "https://ilink.bot.weixin.qq.com",
    val routeTag: String? = null,
    val allowedUsers: Set<String> = emptySet(),
    val allowedChats: Set<String> = emptySet()
)
