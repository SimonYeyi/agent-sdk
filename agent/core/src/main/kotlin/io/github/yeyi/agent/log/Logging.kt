package io.github.yeyi.agent.log

import java.io.StringWriter
import java.io.PrintWriter

public interface LogDelegate {
    public fun debug(tag: String, msg: String)
    public fun info(tag: String, msg: String)
    public fun warn(tag: String, msg: String? = null, e: Throwable? = null)
    public fun error(tag: String, msg: String? = null, e: Throwable? = null)
}

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

    internal fun warn(tag: String, msg: String? = null, e: Throwable? = null) {
        System.err.println("[WARN] $tag: ${buildThrowableMessage(msg, e)}")
    }

    internal fun error(tag: String, msg: String? = null, e: Throwable? = null) {
        System.err.println("[ERROR] $tag: ${buildThrowableMessage(msg, e)}")
    }

    private fun buildThrowableMessage(msg: String? = null, e: Throwable? = null): String {
        return buildString {
            if (msg != null) append(msg).append("\n")
            e?.let { ex ->
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                append(sw.toString())
            }
        }.trimEnd()
    }
}

internal val log = LoggingTagged("agent")

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
        Logging.warn(tag, msg, null)
    }

    /** 输出警告日志，仅异常信息。 */
    public fun warn(e: Throwable) {
        Logging.warn(tag, null, e)
    }

    /** 输出警告日志，带消息和异常。 */
    public fun warn(msg: String, e: Throwable) {
        Logging.warn(tag, msg, e)
    }

    /**
     * 输出错误日志。
     */
    public fun error(msg: String) {
        Logging.error(tag, msg, null)
    }

    /** 输出错误日志，仅异常信息。 */
    public fun error(e: Throwable) {
        Logging.error(tag, null, e)
    }

    /** 输出错误日志，带消息和异常。 */
    public fun error(msg: String, e: Throwable) {
        Logging.error(tag, msg, e)
    }
}
