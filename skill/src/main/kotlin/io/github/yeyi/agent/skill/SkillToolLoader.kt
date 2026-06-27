package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 加载延迟工具到可用列表，返回工具定义供 LLM 发现。
 */
internal class SkillToolLoader(private val registry: SkillRegistry) : Tool {
    override val name: String = "skill_tool_loader"

    override val description: String =
        "Skill 工具加载器。当 Skill 中需要使用的工具还未注册时，调用本工具获取 Skill 所需工具的完整声明。"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "tool_names": {
                    "type": "array",
                    "items": { "type": "string" },
                    "description": "工具名称列表"
                }
            },
            "required": ["tool_names"]
        }
    """
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val toolNames = arguments.jsonObject["tool_names"]
            ?.jsonArray
            ?.map { it.jsonPrimitive.content }
            ?: return ToolExecutionResult.error("Missing tool_names")

        val toolsList = registry.toolsList(toolNames)
        return ToolExecutionResult.success("发现以下可用 Skill 工具：\n$toolsList")
    }
}
