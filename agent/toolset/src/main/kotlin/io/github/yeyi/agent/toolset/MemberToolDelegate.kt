package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 共享代理 Tool — 接收 LLM 的 `{toolset_name, tool_name, tool_arguments}` 调用，
 * 转发到指定 [Toolset] 的对应成员 Tool。
 */
internal class MemberToolDelegate(private val registry: ToolsetRegistry) : Tool {
    override val name: String = "member_tool_delegate"

    override val description: String =
        "调用指定 toolset 内的成员 Tool：传入 toolset_name + tool_name + tool_arguments"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "toolset_name": { "type": "string", "description": "Tool 所属 toolset 名" },
                "tool_name": { "type": "string", "description": "Tool 名" },
                "tool_arguments": { "type": "object", "description": "Tool 参数" }
            },
            "required": ["toolset_name", "tool_name"]
        }
        """.trimIndent()
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val argsObj = arguments.jsonObject
        val toolsetName = argsObj["toolset_name"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error("Missing 'toolset_name'")
        val toolName = argsObj["tool_name"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error("Missing 'tool_name'")
        val toolArgs = argsObj["tool_arguments"] ?: JsonNull
        return registry.get(toolsetName).dispatch(toolName, toolArgs, context)
    }
}
