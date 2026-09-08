package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.DelegateTarget
import io.github.yeyi.agent.tool.DelegatingTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 代理执行延迟加载的工具。
 *
 * 同时实现 [DelegatingTool]，使审批等拦截器能穿透委托层，基于目标 Skill 工具的
 * 策略做决策。[execute] 复用 [resolveTarget] 获取目标，避免路由解析逻辑重复。
 */
internal class SkillToolCaller(private val registry: SkillRegistry) :
    Tool, DelegatingTool {

    override val name: String = "skill_tool_caller"

    override val description: String = "Skill 工具调用代理。代理调用动态加载的 Skill 工具。"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "tool_name": {
                    "type": "string",
                    "description": "The name of the target tool of Skill (not the tool name)"
                },
                "arguments": {
                    "type": "object",
                    "description": "Actual parameters schema of the target tool of Skill. Replace this object with the specific parameters required by the tool."
                }
            },
            "required": ["tool_name", "arguments"],
            "additionalProperties": false
        }
    """
    )

    override fun resolveTarget(arguments: JsonElement): DelegateTarget {
        val toolName = arguments.jsonObject["tool_name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing tool_name")
        val toolArgs = arguments.jsonObject["arguments"]
            ?: throw IllegalArgumentException("Missing arguments")
        val target = registry.getTool(toolName)
        return DelegateTarget(target, toolArgs)
    }

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val target = resolveTarget(arguments)
        return target.tool.execute(target.arguments, context)
    }
}
