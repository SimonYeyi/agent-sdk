package io.github.yeyi.agent.core.llm

import io.github.yeyi.agent.core.tool.ToolParameters

public data class ToolDefinition(
    public val name: String,
    public val description: String,
    public val parametersSchema: ToolParameters
)
