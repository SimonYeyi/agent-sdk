package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.session.SessionHook

/**
 * 标记接口:这个 hook 是 `:hook` 模块"全局 hook 系统"的一部分。
 *
 * [Hook] 同时实现 [AgentHook] 和 [SessionHook],使得:
 * - 一个 hook 实现可以同时参与 Agent 生命周期和 Session 生命周期的回调
 * - DSL 扩展 (`hook(h: Hook)`) 的入参类型可以表达"应当参与 hook 组合/排序"的设计意图
 * - 调用方代码与具体角色解耦:`hook(LoggingHook())`、`hook(MyMetricsHook())` 都通过同一个扩展
 *
 * 实现要求:
 * - 一个类实现 [Hook] 即表示愿意参与 [CompositeHook] 组合
 * - 实现者无需在本接口新增任何方法;[AgentHook] 和 [SessionHook] 的方法集就是 hook 回调的完整契约
 */
public interface Hook : AgentHook, SessionHook

/**
 * 调用 hook 方法的统一异常隔离包装。
 *
 * 行为:
 * - [action] 正常完成 → 返回其结果(类型 [T])
 * - [action] 抛 [kotlinx.coroutines.CancellationException] → 原样抛出(尊重结构化并发)
 * - [action] 抛其他 [Throwable] → 被吞掉,通过 [Logging] 记一行 WARN,返回 `null`
 *
 * 返回类型 [T?] 让调用方在 hook 抛异常时统一处理 `null`(典型做法:fallback 到某个默认值)。
 * CompositeHook 对每个 hook 回调点都用此扩展,保证单个 hook 抛异常不会破坏主流程。
 *
 * 模块级 `internal` 可见:仅供 hook 模块内部使用。
 */
internal suspend inline fun <T> Hook.safeInvoke(
    crossinline action: suspend Hook.() -> T,
): T? {
    return try {
        action()
    } catch (t: kotlinx.coroutines.CancellationException) {
        throw t
    } catch (t: Throwable) {
        Logging.hook().warn("${this::class.simpleName} hook exception", t)
        null
    }
}