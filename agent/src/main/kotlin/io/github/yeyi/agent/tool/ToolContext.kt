package io.github.yeyi.agent.tool

public data class ToolContext(
    public val toolCallId: String,
    public val metadata: Map<String, String> = emptyMap()
)
