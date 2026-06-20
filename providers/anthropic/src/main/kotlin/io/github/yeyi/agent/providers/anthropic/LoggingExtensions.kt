package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.log.LoggingTagged
import io.ktor.client.plugins.logging.Logger

/**
 * Anthropic provider 日志扩展，固定 tag 为 "anthropic"。
 */
internal fun Logging.anthropic(): LoggingTagged = LoggingTagged("anthropic")

internal class HttpLogger : Logger {
    override fun log(message: String) {
        Logging.anthropic().debug(message)
    }
}