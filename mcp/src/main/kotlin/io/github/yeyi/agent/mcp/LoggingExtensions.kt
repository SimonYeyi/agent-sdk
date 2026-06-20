package io.github.yeyi.agent.mcp

import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.log.LoggingTagged

/**
 * hook 模块专用日志扩展,固定 tag 为 "mcp"。
 *
 * 用法:
 * ```kotlin
 * Logging.mcp().warn("something happened")
 * ```
 */
internal fun Logging.mcp(): LoggingTagged = LoggingTagged("mcp")
