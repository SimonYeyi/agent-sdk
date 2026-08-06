package io.github.yeyi.agent.demo.team.smartCockpit

import io.github.yeyi.agent.hook.HookPipeline
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.bossAgent
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry

/**
 * Smart Cockpit BossAgent 配置。
 */
object CockpitAgent {

    fun create(llmProvider: LlmProvider): BossAgent {
        // Quick tools - boss 直接执行
        val quickToolRegistry = ToolRegistry().apply {
            register(GetTimeTool())
            register(GetDateTool())
        }

        // Tools - 独立工具池（不在 toolsets 中）
        val toolRegistry = ToolRegistry().apply {
            register(GetCarStatusTool())
            register(GetEnergyTool())
        }

        // Toolsets - 相似工具的集合（工具只在此处出现）
        val toolsetRegistry = ToolsetRegistry().apply {
            register(comfortControlToolset)
            register(cabinEnvironmentToolset)
            register(drivingAssistToolset)
        }

        // Skills - 按需加载的文档
        val skillRegistry = SkillRegistry().apply {
            register(DrivingModeSkill())
            register(RestModeSkill())
            register(MovieModeSkill())
            register(GoHomeSkill())
        }

        // Subagents - 子代理
        val subagentRegistry = SubagentRegistry().apply {
            register(CockpitSubagents.MediaExpertSubagent())
            register(CockpitSubagents.NavigationExpertSubagent())
        }

        return bossAgent {
            memory(InMemoryMemory(), 40)
            llmProvider(llmProvider)
            maxIterations(40)
            quickTools(quickToolRegistry)
            tools(toolRegistry)
            toolsets(toolsetRegistry)
            skills(skillRegistry)
            subagents(subagentRegistry)
            hook(HookPipeline(logging = true))
        }
    }
}
