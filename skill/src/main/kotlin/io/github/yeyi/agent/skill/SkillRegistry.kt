package io.github.yeyi.agent.skill

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.tool.Tool
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

    internal fun activate(persona: Persona, toolRegister: (tool: Tool) -> Unit) {
        val indexPrompt = """
            |你可以使用以下技能：
            |${buildIndexPrompt()}
            |
            |当需要使用某个技能时，先调用 ${LoadSkillTool.NAME} 工具获取详细指令。
        """.trimMargin()
        persona.other(indexPrompt)
        toolRegister.invoke(LoadSkillTool(this))
    }

    internal fun load(name: String, context: SkillContext): String? = skills[name]?.load(context)

    private fun buildIndexPrompt(): String = skills.values.joinToString("\n") {
        "    - ${it.name}: ${it.description}"
    }
}