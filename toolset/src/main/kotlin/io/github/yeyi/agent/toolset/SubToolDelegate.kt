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
 * 共享代理 Tool — 接收 LLM 的 `{toolset_name, sub_tool_name, sub_tool_arguments}` 调用，
 * 转发到指定 [Toolset] 的对应子 Tool。
 */
internal class SubToolDelegate(private val registry: ToolsetRegistry) : Tool {
    override val name: String = "sub_tool_delegate"

    override val description: String =
        "调用指定 toolset 内的子工具：传入 toolset_name + sub_tool_name + sub_tool_arguments"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "toolset_name": { "type": "string", "description": "子 Tool 所属 toolset 名" },
                "sub_tool_name": { "type": "string", "description": "子 Tool 名" },
                "sub_tool_arguments": { "type": "object", "description": "子 Tool 参数" }
            },
            "required": ["toolset_name", "sub_tool_name"]
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
        val toolName = argsObj["sub_tool_name"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error("Missing 'sub_tool_name'")
        val toolArgs = argsObj["sub_tool_arguments"] ?: JsonNull
        return registry.get(toolsetName).dispatch(toolName, toolArgs, context)
    }
}
