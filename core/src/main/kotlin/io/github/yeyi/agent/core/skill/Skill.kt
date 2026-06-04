package io.github.yeyi.agent.core.skill

import io.github.yeyi.agent.core.tool.Tool

public data class Skill(
    public val name: String,
    public val description: String,
    public val systemPromptFragment: String = "",
    public val tools: List<Tool> = emptyList()
)
