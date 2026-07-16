package io.github.yeyi.agent.team

internal sealed interface Selection {
    val type: String

    data class Skill(val name: String) : Selection {
        override val type: String get() = TYPE
        companion object { const val TYPE: String = "skill" }
    }
    data class Toolset(val name: String) : Selection {
        override val type: String get() = TYPE
        companion object { const val TYPE: String = "toolset" }
    }
    data class Subagent(val name: String) : Selection {
        override val type: String get() = TYPE
        companion object { const val TYPE: String = "subagent" }
    }
    data class Tool(val name: String) : Selection {
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
