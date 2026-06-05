package io.github.yeyi.agent.llm

import io.github.yeyi.agent.tool.ToolParameters

public data class ToolDefinition(
    public val name: String,
    public val description: String,
    public val parametersSchema: ToolParameters
)
