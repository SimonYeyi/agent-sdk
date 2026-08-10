package io.github.yeyi.agent.team

internal sealed interface Selection {
    val type: String
    val name: String

    data class Skill(override val name: String) : Selection {
        override val type: String get() = TYPE
        companion object { const val TYPE: String = "skill" }
    }
    data class Toolset(override val name: String) : Selection {
        override val type: String get() = TYPE
        companion object { const val TYPE: String = "toolset" }
    }
    data class Subagent(override val name: String) : Selection {
        override val type: String get() = TYPE
        companion object { const val TYPE: String = "subagent" }
    }
    data class Tool(override val name: String) : Selection {
        override val type: String get() = TYPE
        companion object { const val TYPE: String = "tool" }
    }

    companion object {
        val FACTORIES: Map<String, (String) -> Selection> = mapOf(
            Skill.TYPE to ::Skill,
            Toolset.TYPE to ::Toolset,
            Subagent.TYPE to ::Subagent,
            Tool.TYPE to ::Tool,
        )
    }
}
