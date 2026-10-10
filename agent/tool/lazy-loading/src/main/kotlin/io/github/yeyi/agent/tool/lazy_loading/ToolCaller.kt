package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.tool.DelegateTarget
import io.github.yeyi.agent.tool.DelegateTool
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * LazyTool 工具调用代理，代理调用延迟加载的工具。
 *
 * 同时实现 [DelegateTool]，使审批等拦截器能穿透委托层，基于目标工具的
 * 策略做决策。[execute] 复用 [resolveTarget] 获取目标，避免路由解析逻辑重复。
 */
internal class ToolCaller(private val registry: LazyToolRegistry) : Tool, DelegateTool {

    override val name: String = "tool_caller"

    override val description: String = ""

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "tool_name": {
                    "type": "string",
                    "description": "The name of the target LazyTool (not the internal tool name)"
                },
                "arguments": {
                    "type": "object",
                    "description": "Actual parameters schema of the target tool by 'search_tool' or 'load_lazy_tool'."
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
        val lazyTool = registry.all().find { it.name == toolName }
            ?: throw AgentException.ToolNotFound(toolName, registry.all().map { it.name })
        return DelegateTarget(lazyTool.tool, toolArgs)
    }

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val target = resolveTarget(arguments)
        return target.tool.execute(target.arguments, context)
    }
}
