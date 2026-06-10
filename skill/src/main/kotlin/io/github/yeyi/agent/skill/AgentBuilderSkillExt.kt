package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder

/**
 * Register a single [Skill] into this builder's tool set as a [SkillTool].
 *
 * The skill's own [Skill.tools] are also registered as plain tools, so the LLM sees both
 * the skill invocation handle (`skill_<name>`) and any tools the skill bundles.
 *
 * @throws IllegalArgumentException if any tool name or the skill's tool-name conflicts
 *   with something already registered.
 */
public fun AgentBuilder.skill(s: Skill) {
    tool(SkillTool(s))
    tools(s.tools)
}

/**
 * Register multiple [Skill]s in iteration order. Equivalent to calling [skill] for each.
 */
public fun AgentBuilder.skills(skills: Iterable<Skill>) {
    skills.forEach { skill(it) }
}

/**
 * Register all skills from a [SkillRegistry] in registration order.
 */
public fun AgentBuilder.skills(registry: SkillRegistry) {
    skills(registry.all())
}
