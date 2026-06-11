package io.github.yeyi.agent

import io.github.yeyi.agent.internal.Logging
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.tool.ToolExecutionResult

/**
 * Agent 生命周期回调。所有方法默认 no-op。
 *
 * 契约:
 * 1. Hook 抛异常不影响主流程,会被 SDK 吞掉并 log
 * 2. Hook 不应阻塞/sleep,可能影响 agent 延迟
 * 3. Hook 不能修改 config/memory(应只读使用)
 * 4. 调用顺序: beforeLlmCall → afterLlmResponse → (beforeToolCall → afterToolCall)* → onRunFinished
 *
 * 工具调用拦截语义(v1.1):
 * - [beforeToolCall] 返回 `null` → 继续走真实工具执行
 * - [beforeToolCall] 返回非 `null` → 跳过真实工具,该返回值作为"合成结果"注入到 memory,
 *   模型下一轮看到的是 hook 决定的内容。屏蔽工具 = 返回 `isError = true` 的合成结果
 *   **短路时不会发出 `ToolCallStarted`/`ToolCallFinished` 事件**(因为工具压根没被调用)
 * - [afterToolCall] 拿到上一个 hook(或真实工具)的输出,返回的值作为最终结果回传给主流程,
 *   支持逐 hook 链式改写
 *
 * 错误语义:
 * - onError 在主流程抛出前调用;iteration 为错误发生的迭代编号(若发生在 LLM 调用前则为 0)
 * - onError 不接收 CancellationException(由结构化并发保证)
 * - 工具执行错误若被 SDK 转换为 ToolExecutionResult(isError=true)不会触发 onError;只有真正
 *   抛出的异常才会触发
 *
 * 终止语义:
 * - onRunFinished 仅在成功完成时触发(对应 AgentResult 正常返回)
 * - 若需保证终止埋点,使用调用方的 try/finally 包装 agent.run,或依赖 onError 处理失败路径
 *
 * v1 实现范围:
 * - 仅 `run` 路径触发上述回调;`runStream` 在 v1.x 中暂不触发 hook(由 v1.1 任务补齐)
 *
 * 接入方式:
 * - ReActAgent 构造器只接受单个 [AgentHook](默认 agent 模块内部的空实现);如需挂载多个 hook,
 *   使用 `hook` 模块的 `CompositeHook` 组合
 */
public interface AgentHook {
    public suspend fun beforeLlmCall(iteration: Int, messages: List<ChatMessage>) {}
    public suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {}

    /**
     * 工具执行前的拦截点。
     * @return 非 null 表示跳过真实工具执行,把该返回值作为"合成结果"注入 memory;
     *         null 表示按正常流程执行工具。
     */
    public suspend fun beforeToolCall(call: ToolCall): ToolExecutionResult? = null

    /**
     * 工具执行后的改写点。
     * @return 主流程将使用该返回值(可与原 result 不同)。多个 hook 时按注册顺序链式改写。
     */
    public suspend fun afterToolCall(
        call: ToolCall,
        result: ToolExecutionResult,
        durationMs: Long,
    ): ToolExecutionResult = result

    public suspend fun onError(iteration: Int, cause: Throwable) {}
    public suspend fun onRunFinished(result: AgentResult) {}
}

/**
 * 默认无操作的 AgentHook 实现。
 *
 * 标记 `internal` — 仅供 agent 模块内部作为"未挂载 hook"的占位实现
 */
internal object NoOpAgentHook : AgentHook

/**
 * 调用 hook 方法的统一异常隔离包装。
 *
 * 行为:
 * - [action] 正常完成 → 返回其结果(类型 [T])
 * - [action] 抛 [kotlinx.coroutines.CancellationException] → 原样抛出(尊重结构化并发)
 * - [action] 抛其他 [Throwable] → 被吞掉,通过 [Logging] 记一行 WARN,返回 `null`
 *
 * 返回类型 [T?] 让调用方在 hook 抛异常时统一处理 `null`(典型做法:fallback 到某个默认值)。
 * ReActAgent 对每个 hook 回调点都用此扩展,保证单个 hook 抛异常不会破坏 agent 主流程。
 *
 * 模块级 `internal` 可见:仅供 agent 模块内部使用。
 */
internal suspend inline fun <T> AgentHook.safeInvoke(
    crossinline action: suspend AgentHook.() -> T,
): T? {
    return try {
        action()
    } catch (t: kotlinx.coroutines.CancellationException) {
        throw t
    } catch (t: Throwable) {
        Logging.warn("AgentHook", "${this::class.simpleName} threw ${t::class.simpleName}: ${t.message}")
        null
    }
}