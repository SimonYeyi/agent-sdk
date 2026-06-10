package io.github.yeyi.agent.skill

/**
 * Central store for [Skill] instances keyed by [Skill.name].
 *
 * Registration is order-preserving (a [LinkedHashMap] under the hood), so iterating
 * [all] yields the skills in the order they were registered. Re-registering a name is
 * a hard error rather than a silent overwrite — the same strictness used by
 * [io.github.yeyi.agent.tool.ToolRegistry].
 */
public class SkillRegistry {
    private val byName: MutableMap<String, Skill> = LinkedHashMap()

    public fun register(skill: Skill) {
        require(skill.name !in byName) { "Duplicate skill name: ${skill.name}" }
        byName[skill.name] = skill
    }

    public fun registerAll(skills: Iterable<Skill>) {
        skills.forEach(::register)
    }

    public fun names(): List<String> = byName.keys.toList()

    public fun all(): List<Skill> = byName.values.toList()

    public fun get(name: String): Skill? = byName[name]
}
