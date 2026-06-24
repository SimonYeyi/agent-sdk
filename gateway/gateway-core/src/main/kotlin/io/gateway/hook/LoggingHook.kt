package io.gateway.hook

import io.gateway.api.HookPipeline

class LoggingHook(
    private val logReceiver: (level: Level, message: String) -> Unit = { level, msg ->
        println("[${level.name}] $msg")
    }
) : HookPipeline.Hook {

    enum class Level { DEBUG, INFO, WARN, ERROR }

    override val name: String = "logging"

    override val events: Set<HookPipeline.Event> = HookPipeline.Event.values().toSet()

    override val priority: Int = 1000

    override suspend fun execute(context: HookPipeline.Context): HookPipeline.Result {
        val event = context.event
        val platform = context.platform?.value ?: "unknown"

        when (event) {
            HookPipeline.Event.ON_START ->
                log(Level.INFO, "Gateway starting up")

            HookPipeline.Event.ON_STOP ->
                log(Level.INFO, "Gateway shutting down")

            HookPipeline.Event.ON_PLATFORM_CONNECT ->
                log(Level.INFO, "Platform connected: $platform")

            HookPipeline.Event.ON_PLATFORM_DISCONNECT ->
                log(Level.WARN, "Platform disconnected: $platform")

            HookPipeline.Event.ON_ERROR ->
                log(Level.ERROR, "Error on $platform: ${context.error?.message ?: "unknown"}")

            HookPipeline.Event.BEFORE_RECEIVE -> {
                val msg = context.message
                log(Level.DEBUG, "Received message from $platform: ${msg?.source?.chatId}")
            }

            HookPipeline.Event.ON_SEND_FAILED ->
                log(Level.ERROR, "Send failed on $platform: ${context.sendResult?.let { (it as? io.gateway.model.SendResult.Failure)?.error }}")

            HookPipeline.Event.ON_SESSION_CREATE ->
                log(Level.DEBUG, "Session created: ${context.session?.key}")

            HookPipeline.Event.ON_SESSION_DESTROY ->
                log(Level.DEBUG, "Session destroyed: ${context.session?.key}")

            else -> { /* 不记录其他事件 */ }
        }

        return HookPipeline.Result.Continue
    }

    private fun log(level: Level, message: String) {
        logReceiver(level, message)
    }
}
