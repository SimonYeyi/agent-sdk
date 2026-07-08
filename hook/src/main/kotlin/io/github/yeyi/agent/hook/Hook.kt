package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentContext
import kotlin.reflect.KClass

/**
 * Hook 接口，用于对 Agent 生命周期事件进行拦截和扩展。
 *
 * 实现者通过 [events] 声明感兴趣的事件类型，[execute] 处理具体逻辑。
 * 调度顺序由 [priority] 决定，数值越大越先执行。
 *
 * 示例：
 * ```
 * class MyHook : Hook {
 *     override val events = setOf(AgentHookEvent.BeforeLlmCall::class)
 *     override suspend fun execute(event: HookEvent, context: HookContext): HookResult {
 *         // ...
 *         return HookResult.Continue
 *     }
 * }
 * ```
 */
public interface Hook {
    /** Hook 唯一名称，用于日志和调试。 */
    public val name: String get() = this::class.simpleName
        ?: throw UnsupportedOperationException(
            "Cannot determine class name, please override name to return a unique identifier"
        )

    /**
     * 此 Hook 感兴趣的事件类型集合。
     *
     * 返回 null 表示接收所有事件；返回具体 set 则只接收匹配事件。
     */
    public val events: Set<KClass<out HookEvent>>? get() = null

    /** 数值越大越先执行，默认 0。多个同 priority Hook 按注册顺序执行。 */
    public val priority: Int get() = 0

    /**
     * 处理事件。
     *
     * @param event 具体事件子类型（如 [AgentHookEvent.BeforeLlmCall]）
     * @param context 执行上下文，含 [AgentContext] 和扩展 metadata
     * @return 执行结果，决定后续调度行为
     */
    public suspend fun execute(event: HookEvent, context: HookContext): HookResult
}

/** 事件标记接口，所有具体事件均实现此接口。 */
public interface HookEvent {
    /**
     * 用新结果创建事件副本。复写此方法可实现链式结果修改。
     *
     * 默认返回自身（不可变 or 不支持链式修改的事件不需要复写）。
     */
    public fun copyWith(newResult: Any): HookEvent = this
}

/**
 * Hook 执行上下文。
 *
 * @param agentContext 当前 Agent 上下文，可能为 null（部分初始化阶段）
 * @param metadata 扩展 metadata，用于在 Hook 之间传递数据
 */
public data class HookContext(
    val agentContext: AgentContext? = null,
    val metadata: MutableMap<String, String> = mutableMapOf()
)

/**
 * Hook [execute][Hook.execute] 的返回值，决定后续调度行为。
 *
 * - [Continue]：继续执行下一个 Hook（若无则走主流程）
 * - [Halt]：中断后续 Hook 链，通常用于打日志等副作用
 * - [Modify]：修改事件结果，只在特定事件（如 AfterToolCall）时有效
 */
public sealed class HookResult {
    /** 继续执行，不干预主流程。 */
    public object Continue : HookResult()

    /** 中断 Hook 链执行，常用于日志类 Hook 的副作用埋点。 */
    public data class Halt(val reason: String) : HookResult()

    /**
     * 修改事件结果。
     *
     * 当前仅 [AgentHookEvent.AfterToolCall] 支持此语义；
     * 其他事件收到此结果等价于 [Continue]。
     *
     * @param newResult 新的结果值，类型必须与具体事件匹配
     */
    public data class Modify(val newResult: Any) : HookResult()
}
