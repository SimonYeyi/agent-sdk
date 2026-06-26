package io.gateway.util

import java.io.PrintWriter
import java.io.StringWriter

internal object GatewayLogging {

    private var logDelegate: GatewayLogDelegate = DefaultGatewayLogDelegate()

    internal fun setDelegate(delegate: GatewayLogDelegate) {
        logDelegate = delegate
    }

    internal fun debug(tag: String, msg: String) {
        logDelegate.debug(tag, msg)
    }

    internal fun info(tag: String, msg: String) {
        logDelegate.info(tag, msg)
    }

    internal fun warn(tag: String, msg: String? = null, e: Throwable? = null) {
        logDelegate.warn(tag, msg, e)
    }

    internal fun error(tag: String, msg: String? = null, e: Throwable? = null) {
        logDelegate.error(tag, msg, e)
    }
}

public interface GatewayLogDelegate {
    public fun debug(tag: String, msg: String)
    public fun info(tag: String, msg: String)
    public fun warn(tag: String, msg: String? = null, e: Throwable? = null)
    public fun error(tag: String, msg: String? = null, e: Throwable? = null)
}

private class DefaultGatewayLogDelegate : GatewayLogDelegate {
    override fun debug(tag: String, msg: String) {
        System.err.println("[DEBUG] $tag: $msg")
    }

    override fun info(tag: String, msg: String) {
        System.err.println("[INFO] $tag: $msg")
    }

    override fun warn(tag: String, msg: String?, e: Throwable?) {
        System.err.println("[WARN] $tag: ${buildMessage(msg, e)}")
    }

    override fun error(tag: String, msg: String?, e: Throwable?) {
        System.err.println("[ERROR] $tag: ${buildMessage(msg, e)}")
    }

    private fun buildMessage(msg: String? = null, e: Throwable? = null): String {
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

public fun gatewayLog(tag: String? = null): GatewayLogger =
    GatewayLogger("gateway${tag?.let { ":$it" } ?: ""}")

public class GatewayLogger(private val tag: String) {
    public fun debug(msg: String) {
        GatewayLogging.debug(tag, msg)
    }

    public fun debug(msg: String, e: Throwable) {
        GatewayLogging.debug(tag, "$msg\n${e.stackTraceToString()}")
    }

    public fun info(msg: String) {
        GatewayLogging.info(tag, msg)
    }

    public fun warn(msg: String) {
        GatewayLogging.warn(tag, msg, null)
    }

    public fun warn(e: Throwable) {
        GatewayLogging.warn(tag, null, e)
    }

    public fun warn(msg: String, e: Throwable) {
        GatewayLogging.warn(tag, msg, e)
    }

    public fun error(msg: String) {
        GatewayLogging.error(tag, msg, null)
    }

    public fun error(e: Throwable) {
        GatewayLogging.error(tag, null, e)
    }

    public fun error(msg: String, e: Throwable) {
        GatewayLogging.error(tag, msg, e)
    }
}
