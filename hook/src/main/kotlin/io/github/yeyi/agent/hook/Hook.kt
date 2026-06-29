package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentContext
import kotlin.reflect.KClass

public interface Hook {
    public val name: String get() = this::class.simpleName
        ?: throw UnsupportedOperationException(
            "Cannot determine class name, please override name to return a unique identifier"
        )
    public val events: Set<KClass<out HookEvent>>? get() = null
    /** 数值越大越先执行，默认 0。 */
    public val priority: Int get() = 0
    public suspend fun execute(event: HookEvent, context: HookContext): HookResult
}

public interface HookEvent

/**
 * Hook 执行上下文。
 */
public data class HookContext(
    val agentContext: AgentContext? = null,
    val metadata: MutableMap<String, String> = mutableMapOf()
)

/** Hook 执行结果 */
public sealed class HookResult {
    public object Continue : HookResult()
    public data class Halt(val syntheticResult: String) : HookResult()
    public data class Modify(val newResult: Any) : HookResult()
}
