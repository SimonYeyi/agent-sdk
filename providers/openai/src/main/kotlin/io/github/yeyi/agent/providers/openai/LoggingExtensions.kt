package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.log.LoggingTagged
import io.ktor.client.plugins.logging.Logger

/**
 * OpenAI provider 日志扩展，固定 tag 为 "openai"。
 */
internal fun Logging.openai(): LoggingTagged = LoggingTagged("openai")

internal class HttpLogger : Logger {
    override fun log(message: String) {
        Logging.openai().debug(message)
    }
}