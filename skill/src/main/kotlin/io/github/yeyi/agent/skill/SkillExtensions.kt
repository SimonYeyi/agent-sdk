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
    skills.forEach { skill(it) }
}

