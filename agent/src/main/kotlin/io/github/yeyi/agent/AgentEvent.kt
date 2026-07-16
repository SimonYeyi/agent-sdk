package io.github.yeyi.agent

import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.tool.ToolExecutionResult

/**
 * Agent 事件流模型。
 *
 * 所有事件在流式/非流式模式下行为一致，非流式模式的事件序列是流式模式的子集。
 * 流式模式额外提供 [TextDelta] 增量文本事件，用于实时预览。
 *
 * **非流式模式（run）：**
 * ```
 * Initial → [ToolCallExplanation → ToolCallStart → ToolCallEnd]? → Final
 * ```
 *
 * **流式模式（runStream）：**
 * ```
 * Initial → [TextDelta* → ToolCallExplanation → ToolCallStart → ToolCallEnd]? → TextDelta* → Final
 * ```
 *
 * - 一次 run(Stream) 可能包含多个 [ToolCallExplanation] → [ToolCallStart] → [ToolCallEnd] 事件循环（多轮工具调用）
 * - 一个 [ToolCallExplanation] 后面可能跟着多个 [ToolCallStart] → [ToolCallEnd] 事件对（一次调用多个工具）
 */
public sealed interface AgentEvent {
    /** 用户输入事件,首次循环前发出 */
    public data class Initial(public val userInput: String) : AgentEvent

    /**
     * 工具调用说明事件。在 [ToolCallStart] 之前发出，是 LLM 决定调用工具前对用户的解释性说明。
     *
     * @param text 说明文本。可能为 null，表示 LLM 没有产出解释性文本
     */
    public data class ToolCallExplanation(public val text: String?) : AgentEvent

    /** 工具调用开始事件。在 [ToolCallExplanation] 之后发出，仅作信号，不提交消息。 */
    public data class ToolCallStart(public val callId: String, public val toolName: String) : AgentEvent

    /** 工具调用结束事件。携带工具执行结果。 */
    public data class ToolCallEnd(public val callId: String, public val result: ToolExecutionResult) : AgentEvent

    /**
     * 最终结果事件。
     *
     * - 无工具调用时：表示对话正常结束
     * - 有工具调用时：在所有 [ToolCallEnd] 之后发出
     */
    public data class Final(public val result: AgentResult) : AgentEvent

    /**
     * 终态事件：Agent 内部出现未捕获异常后发出。
     *
     * 携带原始 [Throwable]（可能为 [AgentException] 家族成员，也可能是任意其他异常）。
     * 调用方拿到 [throwable] 后自行决定是否按 [AgentException] 判型——不再由 SDK 边界强制包装。
     */
    public data class Failed(public val throwable: Throwable) : AgentEvent

    public data class MemoryCompressing(public val summaries: List<Summary>) : AgentEvent

    public data class MemoryCompressed(public val summaries: List<Summary>) : AgentEvent

    /**
     * 流式文本增量事件。
     *
     * 实时推送文本增量。在增量文本发送完成后，仍会通过 [ToolCallExplanation] 或 [Final] 发射完整文本。
     */
    public data class TextDelta(public val text: String) : AgentEvent
}
