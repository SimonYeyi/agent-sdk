package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentBuilder

/**
 * DSL 扩展:把 [Hook] 挂到 [AgentBuilder] 上,支持**智能累积**。
 *
 * 行为(根据 builder 当前 `hook` 的运行时类型分支):
 * - 当前是 [CompositeHook]:在内部列表末尾追加 `h`(O(n) 拷贝)
 * - 当前是其他 [Hook]:新建 [CompositeHook] 包含两者
 * - 当前是其他 [io.github.yeyi.agent.AgentHook] — 包括 agent 模块默认的空实现
 *   (其具体类型对 `:hook` 模块不可见):直接替换为 `h`
 *
 * 这样 DSL 用户可以自然地"想挂多少就挂多少",无需手动构造 [CompositeHook]:
 * ```kotlin
 * agent {
 *     hook(LoggingHook())
 *     hook(MyMetricsHook())
 *     hook(MyAuditHook())
 * }
 * // 内部 = CompositeHook(LoggingHook, MyMetricsHook, MyAuditHook)
 * ```
 *
 * 复杂度:每次 `hook(h)` 是 O(n) 拷贝,n 为已注册 hook 数。总 O(n²) 但 N 通常 < 10,
 * 与"挂载 N 个 hook 是低频配置操作"这一使用场景相称。
 *
 * 覆盖行为:此扩展是**累加**,不是替换;若想替换,直接对 `builder.hook` 赋值。
 */
public fun AgentBuilder.hook(h: Hook) {
    hook = when (val current = hook) {
        is CompositeHook -> CompositeHook(current.hooks + h)
        is Hook -> CompositeHook(listOf(current, h))
        // current 是个非 Hook 的 AgentHook(默认空实现 / 直接赋值过来的)— 没法并入 Hook 列表,直接覆盖
        else -> h
    }
}
