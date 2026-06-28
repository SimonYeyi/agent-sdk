package io.github.yeyi.agent.skill

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.capability.CapabilityRegistry
import io.github.yeyi.agent.capability.DefaultCapabilityRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolDispatcher
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement

/**
 * Skill 的注册中心，复用 [DefaultCapabilityRegistry] 的逻辑。
 */
public class SkillRegistry :
    ToolDispatcher, CapabilityRegistry<SkillContext, Skill, Unit> by DefaultCapabilityRegistry(
    capabilityName = Skill.CAPABILITY_NAME
) {
    private val tools: MutableMap<String, Tool> = mutableMapOf()

    override suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        return tools[name]?.execute(arguments, context)
            ?: throw AgentException.ToolNotFound(name, tools.keys)
    }

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

    internal fun hasTools() = tools.isNotEmpty()

    internal fun toolsList(toolNames: List<String>): String {
        val definitions = toolNames.mapNotNull { tools[it] }
            .joinToString(",\n") { tool ->
                val schemaStr = when (val params = tool.parametersSchema) {
                    is ToolParameters.JsonSchema -> params.schema
                    is ToolParameters.Empty -> "{}"
                }
                """
                {
                    "name": "${tool.name}",
                    "description": "${tool.description.replace("\"", "\\\"")}",
                    "parameters_schema": $schemaStr
                }
                """.trimIndent()
            }
        return "[$definitions]"
    }
}
