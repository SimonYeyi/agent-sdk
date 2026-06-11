package io.github.yeyi.agent.log

/**
 * 极简 logger,可在 v2.x 替换为 SLF4J/Logback。
 *
 */
public object Logging {
    internal fun debug(tag: String, msg: String) {
        System.err.println("[DEBUG] $tag: $msg")
    }

    internal fun info(tag: String, msg: String) {
        System.err.println("[INFO] $tag: $msg")
    }

    internal fun warn(tag: String, msg: String) {
        // v1 用 stderr;Android 端通过 JUL bridge 或后续替换为 Log.w
        System.err.println("[WARN] $tag: $msg")
    }

    internal fun error(tag: String, msg: String) {
        System.err.println("[ERROR] $tag: $msg")
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
     * 输出调试日志。
     */
    public fun debug(msg: String) {
        Logging.debug(tag, msg)
    }

    /**
     * 输出信息日志。
     */
    public fun info(msg: String) {
        Logging.info(tag, msg)
    }

    /**
     * 输出警告日志。
     */
    public fun warn(msg: String) {
        Logging.warn(tag, msg)
    }

    /**
     * 输出错误日志。
     */
    public fun error(msg: String) {
        Logging.error(tag, msg)
    }
}
