package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.CancellationException

/**
 * Agent 事件流模型。
 *
 * 所有事件在流式/非流式模式下行为一致，非流式模式的事件序列是流式模式的子集。
 * 流式模式额外提供 [TextDelta] 增量文本事件，用于实时预览。
 *
 * 事件序列：`Initial → [...增量/工具事件...] → Final | Failed | Canceled`（终局三选一）。
 *
 * **非流式模式（run）：**
 * ```
 * Initial → [ToolCallExplanation → ToolCallStart → ToolCallEnd]? → Final | Failed | Canceled
 * ```
 *
 * **流式模式（runStream）：**
 * ```
 * Initial → [TextDelta* → ToolCallExplanation → ToolCallStart → ToolCallEnd]? → TextDelta* → Final | Failed | Canceled
 * ```
 *
 * - 一次 run(Stream) 可能包含多个 [ToolCallExplanation] → [ToolCallStart] → [ToolCallEnd] 事件循环（多轮工具调用）
 * - 一个 [ToolCallExplanation] 后面可能跟着多个 [ToolCallStart] → [ToolCallEnd] 事件对（一次调用多个工具）
 * - 在途指令被消费（写入记忆）时会在轮次中途发出 [Steering]（位置不定，取决于指令何时被检查点消费）
 */
public sealed interface AgentEvent {
    /** 用户输入事件,首次循环前发出 */
    public data class Initial(public val query: AgentQuery) : AgentEvent

    /**
     * 工具调用说明事件。在 [ToolCallStart] 之前发出，是 LLM 决定调用工具前对用户的解释性说明。
     *
     * @param text 说明文本。可能为 null，表示 LLM 没有产出解释性文本
     * @param toolCalls 即将执行的工具调用列表
     */
    public data class ToolCallExplanation(
        public val text: String?,
        public val toolCalls: List<ToolCall>,
    ) : AgentEvent

    /**
     * 工具调用开始事件。在 [ToolCallExplanation] 之后发出，仅作信号，不提交消息。
     *
     * 该事件是运行时中间态、无对应持久化条目；消费方（如 UI）以它渲染
     * "调用工具"临时气泡，并在收到配对 [ToolCallEnd] 时以最终结果替换，不残留。
     */
    public data class ToolCallStart(
        public val toolCall: ToolCall
    ) : AgentEvent

    /**
     * 工具调用结束事件。携带工具执行结果，与 [ToolCallStart] 配对。
     *
     * 结果是该工具调用持久化的唯一来源（写入记忆），消费方应以结果替换 [ToolCallStart] 的临时展示。
     */
    public data class ToolCallEnd(
        public val toolCall: ToolCall,
        public val result: ToolExecutionResult
    ) : AgentEvent

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
     * 调用方拿到 [cause] 后自行决定是否按 [AgentException] 判型——不再由 SDK 边界强制包装。
     */
    public data class Failed(public val cause: Throwable) : AgentEvent

    /**
     * 终态事件：回合被取消后发出（如 [Agent.Cancelable] 打断 / [Agent.Shutdownable] 关闭）。
     *
     * 取消不是失败：失败的正确默认动作是重试/告警，取消是停止/提示打断。
     * 载荷用 [CancellationException] —— `awaitResult` 可直接抛出而不被协程框架
     * 误判为失败。诊断信息沿 `cause.cause` 链获取：关闭场景（[Agent.Shutdownable]）
     * 由 [Agent.Shutdownable.ShutdownError] 承载，打断场景（[Agent.Cancelable]）
     * 由 message 承载传入原因——直接读 `cause.message` 可能为 null，请勿以此判型。
     */
    public data class Canceled(public val cause: CancellationException) : AgentEvent

    public data class MemoryCompressing(public val summaries: List<Summary>) : AgentEvent

    public data class MemoryCompressed(public val summaries: List<Summary>) : AgentEvent

    /**
     * 流式文本增量事件。
     *
     * 实时推送文本增量。在增量文本发送完成后，仍会通过 [ToolCallExplanation] 或 [Final] 发射完整文本。
     */
    public data class TextDelta(public val text: String) : AgentEvent

    /**
     * 在途指令已进入本轮对话事件 —— 指令被消费、写入记忆的那一刻发出。
     *
     * **本事件 ⟺ 指令进了本轮对话**：写入记忆即"纳入推理"—— 它随下一次模型请求生效。
     * 收集方据此渲染用户输入，渲染时刻与记忆里的事实一致（不会先渲染、后落记忆）。
     *
     * 与 [Agent.Steerable.steer] 的返回值分工：**返回值是签收**（指令进了信箱，
     * 同步可得，此刻它还没进对话）；**本事件是入卷**（指令进了记忆，即将成为
     * 模型看得见的内容）。轮次若在消费前终结，指令随轮作废 —— 既没有本事件，
     * 记忆里也没有它，两边一致。
     */
    public data class Steering(public val query: AgentQuery) : AgentEvent
}

/** 终局事件族（Final / Failed / Canceled）—— 供 core 与下游模块统一判定终态。 */
public val AgentEvent.isTerminal: Boolean
    get() = this is AgentEvent.Final || this is AgentEvent.Failed || this is AgentEvent.Canceled
