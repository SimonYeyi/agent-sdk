package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentPlugin
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject

/**
 * 动态子代理插件 —— 注册 `dynamic_subagent` 工具。
 *
 * 触发条件写在工具描述里：工具描述是模型决定是否调用该工具的直接依据，
 * 放进 system prompt 反而多一层间接。
 */
private class DynamicSubagentPlugin : AgentPlugin<Unit> {
    private val tool = DynamicSubagentTool()
    override val id: String = tool.name

    override fun configure(block: Unit.() -> Unit) {}

    override fun install(context: AgentPluginContext) {
        context.registerTool(tool)
    }
}

internal class DynamicSubagentTool : Tool {
    override val name: String = "dynamic_subagent"

    override val description: String =
        "当需要同时推进多个互不依赖的子任务时（如多角度分析、批量处理、对比实验），MUST 调用 dynamic_subagent 工具。而不是使用多个 function calling 直接发起工具调用，因为它们将会被串行处理。"

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
          "type": "object",
          "properties": {
            "subagents": {
              "type": "array",
              "minItems": 1,
              "description": "并发派发的子代理列表，每项独立描述一个 subagent。",
              "items": {
                "type": "object",
                "properties": {
                  "role": {
                    "type": "string",
                    "description": "子代理的角色定位（如'你是一位代码审查员'、'你是一名作家'等）。"
                  },
                  "context": {
                    "type": "string",
                    "description": "子代理的上下文背景信息，供子代理理解任务环境。"
                  },
                  "task": {
                    "type": "string",
                    "description": "子代理要执行的具体任务描述。"
                  }
                },
                "required": ["role", "task"],
                "additionalProperties": false
              }
            }
          },
          "required": ["subagents"],
          "additionalProperties": false
        }
    """.trimIndent()
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val subagentsJson = arguments.jsonObject["subagents"] as? JsonArray
            ?: return ToolExecutionResult.error("subagents must be an array")
        val subagents = Json.decodeFromJsonElement(
            ListSerializer(DynamicSubagentArgs.serializer()),
            subagentsJson
        )
        if (subagents.isEmpty()) return ToolExecutionResult.error("subagents must contain at least one subagent")
        subagents.forEachIndexed { i, a ->
            if (a.role.isEmpty()) return ToolExecutionResult.error("subagents[$i].role is required")
            if (a.task.isEmpty()) return ToolExecutionResult.error("subagents[$i].task is required")
        }
        val pairs = supervisorScope {
            subagents.mapIndexed { index, arg ->
                async { arg to runOne(index, arg, context.agentContext) }
            }.awaitAll()
        }
        val hasError = pairs.any { it.second.isError }
        val aggregatedParts = buildList {
            pairs.forEachIndexed { i, (a, r) ->
                val marker = if (r.isError) " — FAILED" else ""
                add(ContentPart.Text("[${i + 1}] ${a.task}$marker"))
                addAll(r.parts)
                if (i < pairs.lastIndex) add(ContentPart.Text(""))
            }
        }
        return ToolExecutionResult(parts = aggregatedParts, isError = hasError)
    }

    private suspend fun runOne(
        index: Int,
        args: DynamicSubagentArgs,
        agentContext: AgentContext,
    ): ToolExecutionResult {
        return try {
            val result = subagent(
                name = "dynamic_$index",
                description = "dynamic ${args.task}",
                instruction = args.role,
            ).run(SubagentTask(args.task, args.context), SubagentContext(agentContext))
            ToolExecutionResult.success(result)
        } catch (e: Exception) {
            log.warn("dynamic subagent execute failed: task=${args.task}", e)
            ToolExecutionResult.error(e.message ?: "<no message>")
        }
    }

    @Serializable
    private data class DynamicSubagentArgs(
        val role: String,
        val context: String? = null,
        val task: String,
    )
}

/**
 * 将静态 Subagent 注册表与可选的动态 Subagent 工具注册到 Agent。
 *
 * 静态部分（[registry] 非 null）通过 [io.github.yeyi.agent.capability.CapabilityAdapter] 注入；动态部分（[dynamic] = true）注册
 * `dynamic_subagent` 工具，允许 LLM 在调用时按 role/context/task 并发派生临时子代理。
 *
 * 动态子代理恒定继承主代理的全部工具（subagent 类工具由 [Subagent.run] 过滤防递归），
 * 不做按名挑选 —— 工具治理手段（`load_toolset` / `load_lazy_tool` / `load_skill` 等 loader）
 * 背后藏着大量成员工具，且 skill 的依赖无法静态圈定，挑错即无法纠正。
 *
 * @param dynamic true 注册 `dynamic_subagent` 工具，false 不注册
 * @param registry 静态 Subagent 注册中心；为 null 时跳过静态注册（仅注册动态）
 */
public fun AgentBuilder.subagents(
    dynamic: Boolean,
    registry: SubagentRegistry? = null
) {
    if (dynamic) plugin(DynamicSubagentPlugin())
    if (registry != null) subagents(registry)
}
