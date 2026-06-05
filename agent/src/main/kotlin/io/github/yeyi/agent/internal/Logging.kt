package io.github.yeyi.agent.internal

/** 极简内部 logger,可在 v2.x 替换为 SLF4J/Logback */
internal object Logging {
    fun warn(tag: String, msg: String) {
        // v1 用 stderr;Android 端通过 JUL bridge 或后续替换为 Log.w
        System.err.println("[agent-sdk WARN] $tag: $msg")
    }
}
