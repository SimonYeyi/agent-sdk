package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentHook
import kotlin.reflect.KClass

/**
 * Hook 流水线接口，同时实现 [AgentHook]。
 *
 * 设计原则：
 * - [HookPipeline] 本身是一个 [AgentHook] 实现，可直接传给 [io.github.yeyi.agent.ReActAgent]
 * - 事件携带各自参数，无需通用 Context
 * - Session 事件由 session 模块通过同一流水线扩展
 */
public interface HookPipeline : AgentHook {

    /** 注册一个 hook */
    public fun register(hook: Hook)

    /** 按名称注销一个 hook */
    public fun unregister(hookName: String)

    /** 按类型批量注销所有该类型的 hook */
    public fun unregister(hookClass: KClass<out Hook>)

    /** 执行流水线 */
    public suspend fun run(event: HookEvent, context: HookContext): HookResult

    /** 获取所有已注册的 hooks */
    public fun getHooks(): List<Hook>

    /** 获取订阅指定事件类型的 hooks */
    public fun getHooks(eventClass: KClass<out HookEvent>): List<Hook>
}
