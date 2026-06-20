package io.github.yeyi.agent.app.log

import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.log.LoggingTagged
import io.ktor.client.plugins.logging.Logger

/**
 * OpenAI provider 日志扩展，固定 tag 为 "openai"。
 */
internal fun Logging.app(): LoggingTagged = LoggingTagged("app")

internal class HttpLogger : Logger {
    override fun log(message: String) {
        Logging.app().debug(message)
    }
}