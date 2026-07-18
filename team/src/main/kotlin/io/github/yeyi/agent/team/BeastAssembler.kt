package io.github.yeyi.agent.team

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

internal class BeastAssembler(
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry?,
    private val skillRegistry: SkillRegistry?,
    private val subagentRegistry: SubagentRegistry?,
    private val toolsetRegistry: ToolsetRegistry?,
    private val baseRole: String,
    private val maxIterations: Int,
    private val maxRounds: Int,
) {
    suspend fun assemble(selections: List<Selection>): Beast {
        return try {
            assembleHorse(selections)
        } catch (_: IllegalStateException) {
            buildOx()
        }
    }

    private suspend fun assembleHorse(selections: List<Selection>): Horse {
        if (selections.isEmpty()) error("assembleHorse: selections is empty")
        if (selections.any { it is Selection.Subagent }) {
            error("assembleHorse: subagent not supported")
        }

        val skillTexts = mutableListOf<String>()
        val tools = mutableListOf<Tool>()

        for (s in selections) {
            when (s) {
                is Selection.Skill -> {
                    val skill = skillRegistry?.all()?.firstOrNull { it.name == s.name }
                        ?: error("assembleHorse: skill not found: ${s.name}")
                    val text = skill.load()
                    skillTexts += text
                    skillRegistry.allTools().forEach { tool ->
                        val pattern = Regex("\\b" + Regex.escape(tool.name) + "\\b")
                        if (pattern.containsMatchIn(text)) tools += tool
                    }
                }
                is Selection.Toolset -> {
                    val toolset = toolsetRegistry?.all()?.firstOrNull { it.name == s.name }
                        ?: error("assembleHorse: toolset not found: ${s.name}")
                    tools += toolset.all()
                }
                is Selection.Tool -> {
                    val tool = toolRegistry?.all()?.firstOrNull { it.name == s.name }
                        ?: error("assembleHorse: tool not found: ${s.name}")
                    tools += tool
                }
                is Selection.Subagent -> { /* unreachable */
                }
            }
        }

        val persona = Persona(
            buildString {
                append(baseRole)
                skillTexts.forEach { append("\n\n").append(it) }
            }
        )

        return Horse(llmProvider, persona, tools, maxIterations, maxRounds)
    }

    private fun buildOx(): Ox = Ox(
        llmProvider = llmProvider,
        persona = Persona(baseRole),
        toolRegistry = toolRegistry,
        skillRegistry = skillRegistry,
        subagentRegistry = subagentRegistry,
        toolsetRegistry = toolsetRegistry,
        maxIterations = maxIterations,
        maxRounds = maxRounds,
    )
}
