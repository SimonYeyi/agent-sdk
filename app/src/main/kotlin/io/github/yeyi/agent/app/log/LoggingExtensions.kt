package io.github.yeyi.agent.app.log

import io.github.yeyi.agent.log.LoggingTagged
import io.ktor.client.plugins.logging.Logger

internal val log = LoggingTagged("app")

internal class HttpLogger : Logger {
    override fun log(message: String) {
        log.debug(message)
    }
}