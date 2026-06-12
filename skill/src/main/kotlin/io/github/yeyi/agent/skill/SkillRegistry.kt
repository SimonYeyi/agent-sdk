package io.github.yeyi.agent.skill

import java.util.concurrent.ConcurrentHashMap

internal class SkillRegistry {
    private val skills: MutableMap<String, Skill> = ConcurrentHashMap()

    fun register(skill: Skill) {
        require(skill.name !in skills) { "Duplicate skill: ${skill.name}" }
        skills[skill.name] = skill
    }

    fun register(skills: Iterable<Skill>) {
        skills.forEach { register(it) }
    }

    fun load(name: String, context: SkillContext): String? = skills[name]?.load(context)

    fun buildIndexPrompt(): String = skills.values.joinToString("\n") {
        "    - ${it.name}: ${it.description}"
    }
}