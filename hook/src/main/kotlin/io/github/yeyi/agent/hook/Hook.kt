package io.github.yeyi.agent.hook

import kotlin.reflect.KClass

public interface Hook {
    public val name: String get() = this::class.simpleName
        ?: throw UnsupportedOperationException(
            "Cannot determine class name, please override name to return a unique identifier"
        )
    public val events: Set<KClass<out Event>>? get() = null
    /** 数值越大越先执行，默认 0。 */
    public val priority: Int get() = 0
    public suspend fun execute(event: Event, context: HookContext): Result
}