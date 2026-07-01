package io.github.yeyi.agent.hook

internal class LoggingHook : Hook {

    override val priority: Int = Int.MAX_VALUE

    override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
        val outerSimpleName = event::class.qualifiedName
            ?.split('.')
            ?.dropLast(1)
            ?.dropWhile { it.first().isLowerCase() }
            ?.joinToString(".")
        log.info("$outerSimpleName.$event $context")
        return HookResult.Continue
    }
}
