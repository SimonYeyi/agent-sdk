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
 * 通用调度语义（所有事件通用）：
 * - [Continue]：继续下一个 Hook，不干预主流程
 * - [Refuse]：投票拒绝；不中断 chain，调度末尾按注册顺序累积聚合
 * - [Modify]：链式改写结果；由具体事件的 [HookEvent.copyWith] 决定如何应用
 */
public sealed class HookResult {
    /** 继续执行，不干预主流程。所有事件唯一通用结果。 */
    public object Continue : HookResult()

    /**
     * 投票拒绝。
     *
     * 通用调度层：[DefaultHookPipeline.runEvents] 不会因 [Refuse] 中断 chain，
     * 所有匹配的 Hook 都会被调用一次。多 [Refuse] 在调度末尾聚合为单个 [Refuse]，
     * `reason` 用 `"; "` 拼接各段（如 `"权限拒绝; 额度满"`）。
     *
     * 聚合结果的后续处理由事件订阅方决定（参见各事件的 `AgentHook` 入口方法）。
     */
    public data class Refuse(val reason: String) : HookResult()

    /**
     * 链式改写结果。
     *
     * 调度层调用 [HookEvent.copyWith] 用 `newResult` 创建事件副本，
     * 下一个 Hook 看到改写后的事件。事件本身负责 [HookEvent.copyWith] 的实现：
     * 不支持改写的事件可保留默认 `return this`（视为 Continue）；
     * 字段类型不匹配时 [HookEvent.copyWith] 应抛 `IllegalArgumentException`。
     *
     * @param newResult 新的结果值，类型必须与具体事件的 [HookEvent.copyWith] 契约匹配
     */
    public data class Modify(val newResult: Any) : HookResult()
}
