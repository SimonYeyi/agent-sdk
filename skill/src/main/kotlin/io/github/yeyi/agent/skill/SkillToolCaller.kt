package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolDispatcher
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 代理执行延迟加载的工具。
 */
public class SkillToolCaller(private val toolDispatcher: ToolDispatcher) :
    Tool {

    override val name: String = "skill_tool_caller"

    override val description: String = "Skill 工具调用代理。代理调用动态注册的 Skill 工具。"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "tool_name": {
                    "type": "string",
                    "description": "The name of the target Skill tool (not the tool name)"
                },
                "arguments": {
                    "type": "object",
                    "description": "Actual parameters schema of the target Skill tool. Replace this object with the specific parameters required by the tool."
                }
            },
            "required": ["tool_name", "arguments"],
            "additionalProperties": false
        }
    """
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val toolName = arguments.jsonObject["tool_name"]
            ?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error("Missing tool_name")

        val toolArgs = arguments.jsonObject["arguments"]
            ?: return ToolExecutionResult.error("Missing arguments")

        return toolDispatcher.dispatch(toolName, toolArgs, context)
    }
}
