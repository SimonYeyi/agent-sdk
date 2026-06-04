package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.tool.ToolExecutionResult
import kotlinx.serialization.json.JsonElement
import java.time.Instant

public data class ToolCallRecord(
    public val callId: String,
    public val toolName: String,
    public val arguments: JsonElement,
    public val result: ToolExecutionResult,
    public val timestamp: Instant
)
