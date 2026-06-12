package io.github.yeyi.agent

import io.github.yeyi.agent.tool.ToolExecutionResult

public sealed interface AgentEvent {
    public data class TextDelta(public val text: String) : AgentEvent
    public data class ToolCallStarted(public val callId: String, public val toolName: String) : AgentEvent
    public data class ToolCallFinished(public val callId: String, public val result: ToolExecutionResult) : AgentEvent
    public data class Final(public val result: AgentResult) : AgentEvent

    /**
     * 终态事件:Agent 内部出现未捕获异常,被边界包装为 [AgentException] 后发出。
     *
     * 消费者拿到的 [cause] 一定是 [AgentException] 家族成员(非通用 Throwable);
     * 这把"领域异常"的契约下放到事件层,下游不必再防御性判型。
     */
    public data class Failed(public val cause: AgentException) : AgentEvent
}
