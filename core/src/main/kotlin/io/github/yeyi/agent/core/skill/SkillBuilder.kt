package io.github.yeyi.agent.core.skill

import io.github.yeyi.agent.core.tool.Tool

public class SkillBuilder {
    public var description: String = ""
    public var systemPromptFragment: String = ""
    private val tools: MutableList<Tool> = mutableListOf()

    public fun tool(t: Tool) {
        tools += t
    }

    public fun build(name: String): Skill = Skill(
        name = name,
        description = description,
        systemPromptFragment = systemPromptFragment,
        tools = tools.toList()
    )
}

public fun skill(name: String, block: SkillBuilder.() -> Unit): Skill =
    SkillBuilder().apply(block).build(name)
