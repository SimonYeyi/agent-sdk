package io.github.yeyi.agent.hook

internal class LoggingHook : Hook {

    override val priority: Int = Int.MAX_VALUE

    override suspend fun execute(event: Event, context: HookContext): Result {
        log.info("$event $context")
        return Result.Continue
    }
}
