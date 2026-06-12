package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.ToolContext
import kotlinx.serialization.json.JsonElement

public data class SkillContext(
    public val arguments: JsonElement,
    public val toolContext: ToolContext,
)