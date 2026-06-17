package io.github.yeyi.agent.skill

import java.util.concurrent.ConcurrentHashMap

public class SkillRegistry {
    private val skills: MutableMap<String, Skill> = ConcurrentHashMap()

    public fun register(skill: Skill): SkillRegistry = apply {
        require(skill.name !in skills) { "Duplicate skill: ${skill.name}" }
        skills[skill.name] = skill
    }

    public fun register(skills: Iterable<Skill>): SkillRegistry = apply {
        skills.forEach { register(it) }
    }

    internal fun load(name: String, context: SkillContext): String? = skills[name]?.load(context)

    internal fun buildDescription(): String = skills.values.joinToString("\n") {
        "- ${it.name}: ${it.description}"
    }
}