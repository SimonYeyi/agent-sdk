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
    private val maxIterations: Int,
    private val maxRounds: Int,
) {
    private val basePersona: Persona
        get() = Persona("你是一个牛马，需努力完成任务，不可弄虚作假").personality("寡言少语")

    suspend fun assemble(selection: Selection): Beast {
        return try {
            assembleHorse(selection)
        } catch (_: IllegalStateException) {
            buildOx()
        }
    }

    private suspend fun assembleHorse(selection: Selection): Horse {
        var instructions: String? = null
        var tools: List<Tool>? = null

        val notFoundError =
            { error("assembleHorse: ${selection.type} not found: ${selection.name}") }
        when (selection) {
            is Selection.Tool -> {
                val tool = lazyToolRegistry?.all()?.firstOrNull { it.name == selection.name }?.tool
                    ?: notFoundError.invoke()
                tools = listOf(tool)
            }

            is Selection.Toolset -> {
                val toolset = toolsetRegistry?.all()?.firstOrNull { it.name == selection.name }
                    ?: notFoundError.invoke()
                tools = toolset.all()
            }

            is Selection.Skill -> {
                val skill = skillRegistry?.all()?.firstOrNull { it.name == selection.name }
                    ?: notFoundError.invoke()
                instructions = skill.load()
                if (skill.standalone) tools = emptyList()
            }

            is Selection.Subagent -> {
                val subagent = subagentRegistry?.all()?.firstOrNull { it.name == selection.name }
                    ?: notFoundError.invoke()
                instructions = subagent.load()
                tools = subagent.tools
            }
        }

        val persona = basePersona.apply {
            instructions?.let { instructions(instructions) }
        }

        // tools == null 表示无法预处理工具
        return Horse(
            llmProvider,
            persona,
            if (tools == null) toolRegistry.copy() else ToolRegistry().apply { register(tools) },
            if (tools == null) lazyToolRegistry else null,
            if (tools == null) toolsetRegistry else null,
            maxIterations,
            maxRounds
        )
    }

    private fun buildOx(): Ox = Ox(
        llmProvider = llmProvider,
        persona = basePersona,
        toolRegistry = toolRegistry.copy(),
        lazyToolRegistry = lazyToolRegistry,
        toolsetRegistry = toolsetRegistry,
        skillRegistry = skillRegistry,
        subagentRegistry = subagentRegistry,
        maxIterations = maxIterations,
        maxRounds = maxRounds,
    )

    private fun ToolRegistry?.copy(): ToolRegistry? =
        this?.let { ToolRegistry().apply { register(it.all()) } }
}
