package io.github.yeyi.agent.gateway.jvm

import java.io.PrintStream

fun main() {
    forceStdStreamsToUtf8()
    val config = GatewayDaemonConfig.load()
    val daemon = GatewayDaemon(config)

    Runtime.getRuntime().addShutdownHook(Thread {
        daemon.stop()
    })

    daemon.start()
}

/**
 * Re-wrap System.out/System.err with UTF-8 PrintStreams. `-Dstdout.encoding`
 * is honored only at JVM bootstrap, so it covers `./gradlew :gateway:jvm:run`
 * (which forwards applicationDefaultJvmArgs) but IDE Run Configuration
 * bypasses that path and starts the JVM with only its own VM args. Wrapping
 * at the entry point unifies Gradle / IDE / `java -jar` so non-ASCII output
 * (Chinese, etc.) doesn't fall back to the platform default — Windows
 * cmd.exe's 936 (GBK) is the usual culprit. Terminal must still render
 * UTF-8: cmd.exe users run `chcp 65001`; Windows Terminal / PowerShell 7+
 * are UTF-8 by default.
 */
private fun forceStdStreamsToUtf8() {
    val utf8 = Charsets.UTF_8.name()
    System.setOut(PrintStream(System.out, true, utf8))
    System.setErr(PrintStream(System.err, true, utf8))
}
