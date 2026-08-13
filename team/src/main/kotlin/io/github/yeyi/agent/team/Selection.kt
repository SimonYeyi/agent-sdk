package io.github.yeyi.agent.team

internal sealed interface Selection {
    val type: String
        get() = when (this) {
            is Tool -> Type.Tool.value
            is Toolset -> Type.Toolset.value
            is Skill -> Type.Skill.value
            is Subagent -> Type.Subagent.value
        }
    val name: String

    enum class Type(val value: String) {
        Tool("tool"),
        Toolset("toolset"),
        Skill("skill"),
        Subagent("subagent"),
    }

    data class Tool(override val name: String) : Selection
    data class Toolset(override val name: String) : Selection
    data class Skill(override val name: String) : Selection
    data class Subagent(override val name: String) : Selection

    companion object {
        val FACTORIES: Map<String, (String) -> Selection> = mapOf(
            Type.Tool.value to ::Tool,
            Type.Toolset.value to ::Toolset,
            Type.Skill.value to ::Skill,
            Type.Subagent.value to ::Subagent,
        )
    }
}
