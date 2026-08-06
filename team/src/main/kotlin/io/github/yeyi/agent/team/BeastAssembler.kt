package io.github.yeyi.agent.team

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry

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

        selections.filterIsInstance<Selection.Subagent>().takeIf { it.size > 1 }?.let {
            error("assembleHorse: at most one subagent per task, got ${it.size}")
        }

        val skillTexts = mutableListOf<String>()
        val tools = mutableListOf<Tool>()

        for (s in selections) {
            when (s) {
                is Selection.Tool -> {
                    val tool = toolRegistry?.all()?.firstOrNull { it.name == s.name }
                        ?: error("assembleHorse: tool not found: ${s.name}")
                    tools += tool
                }

                is Selection.Toolset -> {
                    val toolset = toolsetRegistry?.all()?.firstOrNull { it.name == s.name }
                        ?: error("assembleHorse: toolset not found: ${s.name}")
                    tools += toolset.all()
                }

                is Selection.Skill -> {
                    val skill = skillRegistry?.all()?.firstOrNull { it.name == s.name }
                        ?: error("assembleHorse: skill not found: ${s.name}")
                    if (!skill.standalone) error("assembleHorse: skill '${skill.name}' is not standalone")
                    val text = skill.load()
                    skillTexts += text
                    tools += extractTools(text)
                }

                is Selection.Subagent -> {
                    val subagent = subagentRegistry?.all()?.firstOrNull { it.name == s.name }
                        ?: error("assembleHorse: subagent not found: ${s.name}")
                    if (subagent.tools == null) {
                        error("assembleHorse: subagent '${s.name}' requires global tools")
                    } else {
                        tools += subagent.tools!!
                    }
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

    /**
     * 从 Skill.load() 返回的文本中扫描工具名,自动绑定 Skill 实际依赖的 Tool —
     * Skill 只声明人话描述, 描述里提到了哪些工具就拉哪些, 不需要 Skill 自己持有工具列表.
     *
     * 池子来源: toolRegistry / toolsetRegistry / skillRegistry.allTools() 的顶层 name.
     *
     * 匹配规则: `\b<name>\b` 全词匹配 (防 "fetcher" 命中 "fetch").
     *
     * 例子 (tool 池): Skill.load() 返回 "用 fetch_url 抓页面, parse_json 提取字段",
     * 扫描后会把 fetch_url / parse_json 对应的 Tool 实例拉进 Horse 的 tools 列表.
     *
     * 例子 (toolset 池): 池里有 Toolset("weather", ...) 持有 GetWeather / GetForecast,
     * 文本里提到 "weather" → 整个 Toolset 展开 (GetWeather + GetForecast) 一起累入.
     * 但文本提 "GetWeather" 这种子 Tool 名不会触发 — 池子第一层是 Toolset 名字, 不是子 Tool 名字.
     *
     * @return 返回 skill 需要使用的 Tool 列表。同名工具不去重，交由调用方处理
     */
    internal fun extractTools(text: String): List<Tool> {
        val providers: List<Pair<String, () -> List<Tool>>> = buildList {
            toolRegistry?.all()?.forEach { add(it.name to { listOf(it) }) }
            toolsetRegistry?.all()?.forEach { add(it.name to { it.all() }) }
            skillRegistry?.allTools()?.forEach { add(it.name to { listOf(it) }) }
        }
        if (providers.isEmpty()) return emptyList()

        val names = providers.joinToString(separator = "\\b|\\b") { Regex.escape(it.first) }
        val pattern = Regex("\\b$names\\b")
        val matched = pattern.findAll(text).map { it.value }
        return providers.filter { it.first in matched }.flatMap { it.second() }
    }
}
