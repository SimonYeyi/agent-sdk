package io.github.yeyi.agent

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
 * 多 hook 调用约定:
 * - 按 List 顺序依次调用;同一 step 内前一个 hook 抛异常不会阻止后续 hook 被调用
 * - 同一 step 内所有 hook 按顺序串行调用,不并发
 *
 * v1 实现范围:
 * - 仅 `run` 路径触发上述回调;`runStream` 在 v1.x 中暂不触发 hook(由 v1.1 任务补齐)
 */
public interface AgentHook {
    public suspend fun beforeLlmCall(iteration: Int, messages: List<ChatMessage>) {}
    public suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {}
    public suspend fun beforeToolCall(call: ToolCall) {}
    public suspend fun afterToolCall(call: ToolCall, result: ToolExecutionResult, durationMs: Long) {}
    public suspend fun onError(iteration: Int, cause: Throwable) {}
    public suspend fun onRunFinished(result: AgentResult) {}
}

/**
 * 默认无操作的 AgentHook 实现。可作为占位符使用,或在 DSL 中显式声明"无副作用"的 hook 槽位。
 */
public object NoOpAgentHook : AgentHook
