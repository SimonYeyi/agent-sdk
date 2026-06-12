package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder

/**
 * Register a single [Skill] into this builder's tool set as a [SkillTool].
 *
 * `Skill` does not bundle tools of its own: if the skill's [Skill.instructions] mention
 * tools the LLM should use, the caller is expected to register those tools on the
 * `AgentBuilder` separately. This extension only adds the `skill_<name>` handle.
 *
 * @throws IllegalArgumentException if a tool with the same name (e.g. another skill
 *   already registered as `skill_<name>`) is already present on the builder.
 */
public fun AgentBuilder.skill(s: Skill) {
    tool(SkillTool(s))
}

/**
 * Register multiple [Skill]s in iteration order. Equivalent to calling [skill] for each.
 */
public fun AgentBuilder.skills(skills: Iterable<Skill>) {
    val registry = SkillRegistry().apply { register(skills) }
    tool(LoadSkillTool(registry))
    val skillSystemPrompt = """
        你可以使用以下技能：
        ${registry.buildIndexPrompt()}

        当需要使用某个技能时，先调用 ${LoadSkillTool.NAME} 工具获取详细指令。
    """.trimIndent()
    systemPrompt(skillSystemPrompt)
}