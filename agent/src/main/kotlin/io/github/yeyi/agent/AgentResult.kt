package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.serialization.json.JsonElement
import java.time.Instant

public data class AgentResult(
    public val message: ChatMessage.Assistant,
    public val iterations: Int,
    public val toolCalls: List<ToolCallRecord>,
) {
    public data class ToolCallRecord(
        public val callId: String,
        public val toolName: String,
        public val arguments: JsonElement,
        public val result: ToolExecutionResult,
        public val timestamp: Instant,
    )
}
