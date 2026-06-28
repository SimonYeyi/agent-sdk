package io.gateway.platform.feishu

import io.gateway.model.IncomingMessage
import io.gateway.util.MessageDeduplicator
import io.gateway.util.gatewayLog

internal class FeishuMessageFilter(
    private val config: FeishuConfig,
    private val deduplicator: MessageDeduplicator
) {
    private val log = gatewayLog("FeishuMessageFilter")

    internal fun shouldProcess(message: IncomingMessage): Boolean {
        if (deduplicator.isDuplicate(message.id.value)) {
            log.info("Duplicate message dropped: ${message.id.value}")
            return false
        }

        if (config.allowedUsers.isNotEmpty() && message.source.userId !in config.allowedUsers) {
            log.info("User not in allowlist (message rejected): ${message.source.userId}")
            return false
        }

        if (config.allowedChats.isNotEmpty() && message.source.chatId !in config.allowedChats) {
            log.info("Chat not in allowlist (message rejected): ${message.source.chatId}")
            return false
        }

        return true
    }
}