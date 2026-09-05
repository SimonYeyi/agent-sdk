package io.github.yeyi.agent.log

/**
 * 用于测试的日志委托实现。
 */
public class FakeLogDelegate : LogDelegate {
    public data class LogEntry(
        val level: LogLevel,
        val tag: String,
        val msg: String
    )

    public val entries: MutableList<LogEntry> = mutableListOf()

    override fun log(level: LogLevel, tag: String, msg: String) {
        entries.add(LogEntry(level, tag, msg))
    }

    public fun clear() {
        entries.clear()
    }
}