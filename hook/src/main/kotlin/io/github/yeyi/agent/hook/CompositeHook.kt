package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.tool.ToolExecutionResult

/**
 * 把多个 [Hook] 组合成一个的复合 hook。
 *
 * [ReActAgent] 只接受**单个** hook 入口;实际项目里通常需要挂载多个(如日志 + 指标 + 审计),
 * 这种场景下用 [CompositeHook] 把它们串起来再传给 ReActAgent。
 *
 * 调度语义:
 * - [beforeLlmCall] / [afterLlmResponse] / [onError] / [onRunFinished]: 按注册顺序 fan-out
 *   依次调用所有内部 hook;任一 hook 抛异常会被吞掉并 log,不影响其他 hook
 * - [beforeToolCall]: 短路决策采用"首个非 null 胜出" — 按注册顺序遍历内部 hook,
 *   第一个返回非 null 的 hook 决定 synthetic result;后续 hook 不再被调用
 * - [afterToolCall]: 链式改写 — 每个 hook 拿到上一个的输出作为 `result` 参数,
 *   最终返回值作为回传给主流程的结果
 *
 * 异常隔离:任意内部 hook 抛 [kotlinx.coroutines.CancellationException] 会被原样抛出(尊重
 * 结构化并发);其他 [Throwable] 会被吞掉并通过 [Logging] 记录,继续 fan-out。
 *
 * 类型边界:[CompositeHook] 内部只接受 [Hook] 实现(不是任意 [AgentHook]);这样可以保证
 * 参与组合的 hook 都来自 :hook 模块的命名空间,符合"hook 模块管全局 hook"的设计意图。
 *
 * 空列表:合法 — 空 [CompositeHook] 等价于无操作(no-op),但仍是一个真正的 [Hook] 实例。
 * 如果想要"完全不挂 hook",交给 [ReActAgent] 构造器的默认行为处理(agent 模块内部细节),
 * 本模块不主动返回空对象的别名 — 工厂和构造器语义一致。
 *
 * ### Example
 * ```kotlin
 * val composite = CompositeHook.of(
 *     LoggingHook(),
 *     MyMetricsHook(),
 *     MyAuditHook(),
 * )
 * ```
 *
 * 或用 DSL 多次 `hook()` 调用智能累积:
 * ```kotlin
 * agent {
 *     hook(LoggingHook())
 *     hook(MyMetricsHook())
 *     hook(MyAuditHook())
 * }
 * // 内部组合为 CompositeHook(LoggingHook, MyMetricsHook, MyAuditHook)
 * ```
 */
public class CompositeHook(
    public val hooks: List<Hook>,
) : Hook {

    override suspend fun beforeLlmCall(iteration: Int, messages: List<ChatMessage>) {
        for (h in hooks) {
            this.safeInvoke { beforeLlmCall(iteration, messages) }
        }
    }

    override suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {
        for (h in hooks) {
            h.safeInvoke { afterLlmResponse(iteration, response) }
        }
    }

    override suspend fun beforeToolCall(call: ToolCall): ToolExecutionResult? {
        for (h in hooks) {
            val r = h.safeInvoke { beforeToolCall(call) }
            if (r != null) return r
        }
        return null
    }

    override suspend fun afterToolCall(
        call: ToolCall,
        result: ToolExecutionResult,
        durationMs: Long,
    ): ToolExecutionResult {
        var current = result
        for (h in hooks) {
            current = h.safeInvoke { afterToolCall(call, current, durationMs) } ?: current
        }
        return current
    }

    override suspend fun onError(iteration: Int, cause: Throwable) {
        for (h in hooks) {
            h.safeInvoke { onError(iteration, cause) }
        }
    }

    override suspend fun onRunFinished(result: AgentResult) {
        for (h in hooks) {
            h.safeInvoke { onRunFinished(result) }
        }
    }

    public companion object {
        /**
         * 工厂:把变参形式的 hook 列表组装成单个 [CompositeHook] (空列表得到空 CompositeHook)。
         */
        public fun of(vararg hooks: Hook): CompositeHook = CompositeHook(hooks.toList())

        /** 工厂:接收任意 [Iterable],空集合得到空 CompositeHook。 */
        public fun of(hooks: Iterable<Hook>): CompositeHook = CompositeHook(hooks.toList())
    }
}


