package io.gateway.platform.telegram

data class TelegramConfig(
    val botToken: String,
    val apiBaseUrl: String = "https://api.telegram.org",
    val pollingTimeout: Int = 30,
    val pollingLimit: Int = 100,
    val allowedUsers: Set<String> = emptySet(),
    val allowedChats: Set<String> = emptySet(),
    val sendTypingIndicator: Boolean = true
)
