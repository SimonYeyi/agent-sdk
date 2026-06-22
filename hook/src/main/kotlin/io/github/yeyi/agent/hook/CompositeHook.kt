package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.session.Session
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
 *
 * ### Example
 * ```kotlin
 * val composite = CompositeHook(
 *     listOf(MyMetricsHook(), MyAuditHook()),
 *     logging = true
 * )
 * ```
 */
public class CompositeHook(hooks: List<Hook> = emptyList(), logging: Boolean = false) : Hook {

    private val hooks: List<Hook> = if (logging) listOf(LoggingHook()) + hooks else hooks

    override suspend fun beforeLlmCall(context: AgentContext) {
        for (hook in hooks) {
            hook.safeInvoke { beforeLlmCall(context) }
        }
    }

    override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) {
        for (hook in hooks) {
            hook.safeInvoke { afterLlmResponse(context, response) }
        }
    }

    override suspend fun beforeMemoryCompress(context: AgentContext, summaries: List<Summary>) {
        for (hook in hooks) {
            hook.safeInvoke { beforeMemoryCompress(context, summaries) }
        }
    }

    override suspend fun afterMemoryCompress(context: AgentContext, summaries: List<Summary>) {
        for (hook in hooks) {
            hook.safeInvoke { afterMemoryCompress(context, summaries) }
        }
    }

    override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? {
        for (hook in hooks) {
            val r = hook.safeInvoke { beforeToolCall(context, call) }
            if (r != null) return r
        }
        return null
    }

    override suspend fun afterToolCall(
        context: AgentContext,
        call: ToolCall,
        result: ToolExecutionResult,
        durationMs: Long,
    ): ToolExecutionResult {
        var current = result
        for (hook in hooks) {
            current = hook.safeInvoke { afterToolCall(context, call, current, durationMs) } ?: current
        }
        return current
    }

    override suspend fun onError(context: AgentContext, cause: AgentException) {
        for (hook in hooks) {
            hook.safeInvoke { onError(context, cause) }
        }
    }

    override suspend fun onRunFinished(context: AgentContext, result: AgentResult) {
        for (hook in hooks) {
            hook.safeInvoke { onRunFinished(context, result) }
        }
    }

    override suspend fun onSessionCreated(session: io.github.yeyi.agent.session.Session) {
        for (hook in hooks) {
            hook.safeInvoke { onSessionCreated(session) }
        }
    }

    override suspend fun onSessionDeleted(accountId: String, sessionId: String) {
        for (hook in hooks) {
            hook.safeInvoke { onSessionDeleted(accountId, sessionId) }
        }
    }

}