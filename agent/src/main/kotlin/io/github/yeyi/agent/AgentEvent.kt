package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.tool.ToolExecutionResult

public sealed interface AgentEvent {
    public data class TextDelta(public val text: String) : AgentEvent
    public data class ToolCallStarted(public val callId: String, public val toolName: String) : AgentEvent
    public data class ToolCallFinished(public val callId: String, public val result: ToolExecutionResult) : AgentEvent
    public data class ToolCallRecorded(public val record: ToolCallRecord) : AgentEvent
    public data class Final(
        public val message: ChatMessage.Assistant,
        public val iterations: Int = 0,
        public val toolCallRecords: List<ToolCallRecord> = emptyList(),
    ) : AgentEvent
    public data class Failed(public val cause: Throwable) : AgentEvent
}
