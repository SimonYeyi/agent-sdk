package io.github.yeyi.agent.hook

internal class LoggingHook : Hook {

    override val priority: Int = Int.MAX_VALUE

    override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
        log.info("$event $context")
        return HookResult.Continue
    }
}
