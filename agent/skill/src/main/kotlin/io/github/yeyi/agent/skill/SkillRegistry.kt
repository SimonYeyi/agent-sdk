package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.capability.DefaultCapabilityRegistry
import io.github.yeyi.agent.tool.Tool
import kotlinx.serialization.json.JsonElement

/**
 * Skill 的注册中心，复用 [DefaultCapabilityRegistry] 的逻辑。
 *
 * Skill 内部工具不通过 [io.github.yeyi.agent.tool.ToolDispatcher] 调度，
 * 而是由 [SkillToolCaller]（[io.github.yeyi.agent.tool.DelegatingTool]）
 * 在 resolveTarget 时直接查 [allTools] 并转发执行。
 */
public class SkillRegistry :
    CapabilityRegistry<Skill, Unit, SkillContext> by DefaultCapabilityRegistry(
    capabilityType = Skill.CAPABILITY_TYPE
) {
    private val tools: MutableMap<String, Tool> = mutableMapOf()

    /**
     * 注册延迟加载的工具。
     * @throws IllegalArgumentException 同名工具已注册时抛出
     */
    public fun registerTools(toolList: Iterable<Tool>) {
        toolList.forEach { tool ->
            require(tool.name !in tools) { "Duplicate tool name: ${tool.name}" }
            tools[tool.name] = tool
        }
    }

    /** 返回所有注册的 Skill 相关工具。 */
    public fun allTools(): List<Tool> = tools.values.toList()

    /** 按名称查找工具，找不到抛 [AgentException.ToolNotFound]。 */
    public fun getTool(name: String): Tool = tools[name]
        ?: throw AgentException.ToolNotFound(name, tools.keys)
}
