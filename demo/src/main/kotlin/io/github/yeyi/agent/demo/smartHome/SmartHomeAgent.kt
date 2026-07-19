package io.github.yeyi.agent.demo.smartHome

import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.bossAgent
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry

/**
 * Smart Home BossAgent 配置。
 */
object SmartHomeAgent {

    fun create(llmProvider: LlmProvider): BossAgent {
        // Quick tools - boss 直接执行
        val quickToolRegistry = ToolRegistry().apply {
            register(GetTimeTool())
            register(GetDateTool())
        }

        // Tools - 独立工具池（不在 toolsets 中）
        val toolRegistry = ToolRegistry().apply {
            register(GetWeatherTool())
            register(GetIndoorTempTool())
        }

        // Toolsets - 相似工具的集合（工具只在此处出现）
        val toolsetRegistry = ToolsetRegistry().apply {
            register(homeControlToolset)
            register(applianceControlToolset)
            register(securityControlToolset)
        }

        // Skills - 按需加载的文档
        val skillRegistry = SkillRegistry().apply {
            register(GoodNightSkill())
            register(GoodMorningSkill())
            register(LeaveHomeSkill())
            register(ComeHomeSkill())
        }

        // Subagents - 子代理
        val subagentRegistry = SubagentRegistry().apply {
            register(SmartHomeSubagents.SecurityExpertSubagent())
            register(SmartHomeSubagents.EnvironmentExpertSubagent())
        }

        return bossAgent {
            memory(InMemoryMemory())
            llmProvider(llmProvider)
            maxIterations(20)
            maxRounds(20)
            quickTools(quickToolRegistry)
            tools(toolRegistry)
            toolsets(toolsetRegistry)
            skills(skillRegistry)
            subagents(subagentRegistry)
        }
    }
}
