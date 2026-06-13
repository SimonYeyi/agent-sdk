package io.github.yeyi.agent

import io.github.yeyi.agent.tool.ToolExecutionResult

public sealed interface AgentEvent {
    /** 用户输入事件,首次循环前发出 */
    public data class Initial(public val userInput: String) : AgentEvent

    /**
     * 推理文本事件。
     *
     * 仅在 LLM 决定调用工具且存在推理文本时发出。
     * 无工具调用或无推理文本时不发此事件。
     */
    public data class Reasoning(public val text: String) : AgentEvent

    /** 工具调用开始事件 */
    public data class ToolCallStart(public val callId: String, public val toolName: String) : AgentEvent

    /** 工具调用结束事件 */
    public data class ToolCallEnd(public val callId: String, public val result: ToolExecutionResult) : AgentEvent

    /** 最终结果事件,无工具调用时发出 */
    public data class Final(public val result: AgentResult) : AgentEvent

    /** 流式文本增量事件 */
    public data class TextDelta(public val text: String) : AgentEvent

    /**
     * 终态事件:Agent 内部出现未捕获异常,被边界包装为 [AgentException] 后发出。
     *
     * 消费者拿到的 [cause] 一定是 [AgentException] 家族成员(非通用 Throwable);
     * 这把"领域异常"的契约下放到事件层,下游不必再防御性判型。
     */
    public data class Failed(public val cause: AgentException) : AgentEvent
}
