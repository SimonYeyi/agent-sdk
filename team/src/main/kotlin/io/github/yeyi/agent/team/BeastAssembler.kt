package io.github.yeyi.agent.team

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry

internal class BeastAssembler(
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry?,
    private val lazyToolRegistry: LazyToolRegistry?,
    private val toolsetRegistry: ToolsetRegistry?,
    private val skillRegistry: SkillRegistry?,
    private val subagentRegistry: SubagentRegistry?,
    private val baseRole: String,
    private val maxIterations: Int,
    private val maxRounds: Int,
) {
    suspend fun assemble(selection: Selection): Beast {
        return try {
            assembleHorse(selection)
        } catch (_: IllegalStateException) {
            buildOx()
        }
    }

    private suspend fun assembleHorse(selection: Selection): Horse {
        var instruction: String? = null
        var tools: List<Tool>? = null

        when (selection) {
            is Selection.Tool -> {
                val tool = lazyToolRegistry?.all()?.firstOrNull { it.name == selection.name }?.tool
                    ?: error("assembleHorse: tool not found: ${selection.name}")
                tools = listOf(tool)
            }

            is Selection.Toolset -> {
                val toolset = toolsetRegistry?.all()?.firstOrNull { it.name == selection.name }
                    ?: error("assembleHorse: toolset not found: ${selection.name}")
                tools = toolset.all()
            }

            is Selection.Skill -> {
                val skill = skillRegistry?.all()?.firstOrNull { it.name == selection.name }
                    ?: error("assembleHorse: skill not found: ${selection.name}")
                instruction = skill.load()
                if (skill.standalone) tools = emptyList()
            }

            is Selection.Subagent -> {
                val subagent = subagentRegistry?.all()?.firstOrNull { it.name == selection.name }
                    ?: error("assembleHorse: subagent not found: ${selection.name}")
                instruction = subagent.load()
                tools = subagent.tools
            }
        }

        val persona = Persona(
            buildString {
                append(baseRole)
                instruction?.let { append("\n\n").append(it) }
            }
        )

        // tools == null 表示无法预处理工具
        return Horse(
            llmProvider,
            persona,
            if (tools == null) toolRegistry else ToolRegistry().apply { register(tools) },
            if (tools == null) lazyToolRegistry else null,
            if (tools == null) toolsetRegistry else null,
            maxIterations,
            maxRounds
        )
    }

    private fun buildOx(): Ox = Ox(
        llmProvider = llmProvider,
        persona = Persona(baseRole),
        toolRegistry = toolRegistry,
        lazyToolRegistry = lazyToolRegistry,
        toolsetRegistry = toolsetRegistry,
        skillRegistry = skillRegistry,
        subagentRegistry = subagentRegistry,
        maxIterations = maxIterations,
        maxRounds = maxRounds,
    )
}
