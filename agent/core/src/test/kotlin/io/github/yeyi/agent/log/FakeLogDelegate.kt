package io.github.yeyi.agent.log

/**
 * 用于测试的日志委托实现。
 */
public class FakeLogDelegate : LogDelegate {
    public data class LogEntry(
        val level: Level,
        val tag: String,
        val msg: String?,
        val throwable: Throwable?
    )

    public enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    public val entries: MutableList<LogEntry> = mutableListOf()

    override fun debug(tag: String, msg: String) {
        entries.add(LogEntry(Level.DEBUG, tag, msg, null))
    }

    override fun info(tag: String, msg: String) {
        entries.add(LogEntry(Level.INFO, tag, msg, null))
    }

    override fun warn(tag: String, msg: String?, e: Throwable?) {
        entries.add(LogEntry(Level.WARN, tag, msg, e))
    }

    override fun error(tag: String, msg: String?, e: Throwable?) {
        entries.add(LogEntry(Level.ERROR, tag, msg, e))
    }

    public fun clear() {
        entries.clear()
    }
}