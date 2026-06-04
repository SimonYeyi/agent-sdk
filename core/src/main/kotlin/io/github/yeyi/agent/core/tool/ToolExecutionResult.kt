package io.github.yeyi.agent.core.tool

public data class ToolExecutionResult(
    public val content: String,
    public val isError: Boolean = false
)
