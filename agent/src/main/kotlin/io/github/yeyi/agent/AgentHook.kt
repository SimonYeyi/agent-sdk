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
 * 工具调用拦截语义(v1.1):
 * - [beforeToolCall] 返回 `null` → 继续走真实工具执行
 * - [beforeToolCall] 返回非 `null` → 跳过真实工具,该返回值作为"合成结果"注入到 memory,
 *   模型下一轮看到的是 hook 决定的内容。屏蔽工具 = 返回 `isError = true` 的合成结果
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
 * - ReActAgent 构造器只接受单个 [AgentHook](默认 [NoOpAgentHook]);如需挂载多个 hook,
 *   使用 `hook` 模块的 `CompositeAgentHook` 组合
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
 * 默认无操作的 AgentHook 实现。可作为占位符使用,或在 DSL 中显式声明"无副作用"的 hook 槽位。
 */
public object NoOpAgentHook : AgentHook