package io.github.yeyi.agent.internal

/**
 * 极简 logger,可在 v2.x 替换为 SLF4J/Logback。
 *
 * 暴露为 public 以便扩展模块(如 `:hook`)统一使用同一日志通道。
 */
public object Logging {
    public fun warn(tag: String, msg: String) {
        // v1 用 stderr;Android 端通过 JUL bridge 或后续替换为 Log.w
        System.err.println("[agent-sdk WARN] $tag: $msg")
    }
}
