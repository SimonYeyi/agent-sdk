package io.github.yeyi.agent.hook

import io.github.yeyi.agent.log.LoggingTagged

/**
 * hook 模块专用日志扩展,固定 tag 为 "hook"。
 *
 * 用法:
 * ```kotlin
 * Logging.hook().warn("something happened")
 * ```
 */
internal val log = LoggingTagged("hook")
