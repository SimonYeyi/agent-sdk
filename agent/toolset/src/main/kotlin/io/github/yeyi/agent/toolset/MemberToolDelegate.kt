package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.tool.DelegateTarget
import io.github.yeyi.agent.tool.DelegatingTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 共享代理 Tool — 接收 LLM 的 `{toolset_name, tool_name, tool_arguments}` 调用，
 * 转发到指定 [Toolset] 的对应成员 Tool。
 *
 * 同时实现 [DelegatingTool]，使审批等拦截器能穿透委托层，基于成员 Tool 的策略
 * 做决策。[execute] 复用 [resolveTarget] 获取目标，避免路由解析逻辑重复。
 */
internal class MemberToolDelegate(private val registry: ToolsetRegistry) : Tool, DelegatingTool {
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

    override fun resolveTarget(arguments: JsonElement): DelegateTarget {
        val argsObj = arguments.jsonObject
        val toolsetName = argsObj["toolset_name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'toolset_name'")
        val toolName = argsObj["tool_name"]?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'tool_name'")
        val toolArgs = argsObj["tool_arguments"] ?: JsonNull
        val target = registry.get(toolsetName).get(toolName)
        return DelegateTarget(target, toolArgs)
    }

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val target = resolveTarget(arguments)
        return target.tool.execute(target.arguments, context)
    }
}
