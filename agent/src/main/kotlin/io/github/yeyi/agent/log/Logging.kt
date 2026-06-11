package io.github.yeyi.agent.log

/**
 * 极简 logger,可在 v2.x 替换为 SLF4J/Logback。
 *
 */
public object Logging {
    internal fun warn(tag: String, msg: String) {
        // v1 用 stderr;Android 端通过 JUL bridge 或后续替换为 Log.w
        System.err.println("[WARN] $tag: $msg")
    }
}

/**
 * agent 模块专用日志扩展,固定 tag 为 "agent"。
 *
 * 用法:
 * ```kotlin
 * Logging.agent().warn("something happened")
 * ```
 */
public fun Logging.agent(): LoggingTagged = LoggingTagged("agent")

/**
 * 带固定 tag 的日志包装器。
 */
public class LoggingTagged(private val tag: String) {
    /**
     * 输出警告日志。
     */
    public fun warn(msg: String) {
        Logging.warn(tag, msg)
    }
}
