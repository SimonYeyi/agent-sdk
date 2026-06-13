package io.github.yeyi.agent.session

import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.log.LoggingTagged

/**
 * session 模块专用日志扩展,固定 tag 为 "session"。
 *
 * 用法:
 * ```kotlin
 * Logging.session().warn("something happened")
 * ```
 */
internal fun Logging.session(): LoggingTagged = LoggingTagged("session")
