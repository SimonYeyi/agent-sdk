package io.github.yeyi.agent.log

import java.io.StringWriter
import java.io.PrintWriter

/**
 * 日志级别。
 */
public enum class LogLevel {
    DEBUG, INFO, WARN, ERROR
}

/**
 * 日志委托接口。统一出口只接收格式化后的字符串。
 */
public interface LogDelegate {
    public fun log(level: LogLevel, tag: String, msg: String)
}

/**
 * 默认日志委托实现。
 *
 * debug/info 输出到 System.out，warn/error 输出到 System.err。
 */
private class DefaultLogDelegate : LogDelegate {
    override fun log(level: LogLevel, tag: String, msg: String) {
        val prefix = "[${level.name}] $tag: "
        when (level) {
            LogLevel.DEBUG, LogLevel.INFO -> println(prefix + msg)
            LogLevel.WARN, LogLevel.ERROR -> System.err.println(prefix + msg)
        }
    }
}

/**
 * 日志门面对象。
 *
 * 通过 [setDelegate] 可注入自定义 [LogDelegate] 实现。
 */
public object Logging {
    private var logDelegate: LogDelegate = DefaultLogDelegate()

    /**
     * 设置日志委托。
     *
     * @param delegate 日志委托实现
     */
    public fun setDelegate(delegate: LogDelegate) {
        logDelegate = delegate
    }

    internal fun log(level: LogLevel, tag: String, msg: String?, e: Throwable? = null) {
        logDelegate.log(level, tag, buildMessage(msg, e))
    }

    private fun buildMessage(msg: String?, e: Throwable?): String {
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
    public fun debug(msg: String): Unit = Logging.log(LogLevel.DEBUG, tag, msg)
    public fun info(msg: String): Unit = Logging.log(LogLevel.INFO, tag, msg)
    public fun warn(msg: String): Unit = Logging.log(LogLevel.WARN, tag, msg)
    public fun warn(e: Throwable): Unit = Logging.log(LogLevel.WARN, tag, null, e)
    public fun warn(msg: String, e: Throwable): Unit = Logging.log(LogLevel.WARN, tag, msg, e)
    public fun error(msg: String): Unit = Logging.log(LogLevel.ERROR, tag, msg)
    public fun error(e: Throwable): Unit = Logging.log(LogLevel.ERROR, tag, null, e)
    public fun error(msg: String, e: Throwable): Unit = Logging.log(LogLevel.ERROR, tag, msg, e)
}
