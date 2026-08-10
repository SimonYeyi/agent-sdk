package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
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

internal class DynamicSubagentTool : Tool {
    override val name: String = "dynamic_subagent"

    override val description: String = """
        |并发派发一组独立子代理；每个 subagent 由 role / context / task / tools 四元组描述，互不影响。
        |适用于需要并行执行多个独立任务的场景（如多角度分析、批量处理、对比实验）。
        |不要用于子代理间通信、子代理间相互依赖、或需要完整主 Agent 上下文的场景。
    """.trimMargin()

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
                    "description": "子代理的角色定位（如'严谨的代码审查员'、'热情的客服'等）。"
                  },
                  "context": {
                    "type": "string",
                    "description": "子代理的上下文背景信息，供子代理理解任务环境。"
                  },
                  "task": {
                    "type": "string",
                    "description": "子代理要执行的具体任务描述。"
                  },
                  "tools": {
                    "type": "array",
                    "items": { "type": "string" },
                    "description": "可选：从主 agent 工具中挑选子集（需排除 dynamic_subagent / load_subagent 等 subagent 相关工具，防递归）。缺省 = 继承过滤后的全部主工具；传 [] = 无工具。"
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
        context: ToolContext
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
        val mainTools = context.agentContext.tools.associateBy { it.name }
        val pairs = supervisorScope {
            subagents.mapIndexed { index, arg ->
                async {
                    val tools = arg.tools?.mapNotNull { mainTools[it] }
                    arg to runOne(index, arg, tools, context.agentContext)
                }
            }.awaitAll()
        }
        val hasError = pairs.any { it.second.isError }
        return ToolExecutionResult(formatResults(pairs), isError = hasError)
    }

    private fun formatResults(pairs: List<Pair<DynamicSubagentArgs, ToolExecutionResult>>): String =
        pairs.mapIndexed { i, (a, r) ->
            val marker = if (r.isError) " — FAILED" else ""
            "[${i + 1}] ${a.task}$marker\n${r.content}"
        }.joinToString("\n\n")

    private suspend fun runOne(
        index: Int,
        args: DynamicSubagentArgs,
        tools: List<Tool>?,
        agentContext: AgentContext,
    ): ToolExecutionResult {
        return try {
            val result = subagent(
                name = "dynamic_$index",
                description = "dynamic ${args.task}",
                instruction = args.role,
                tools = tools,
            ).run(
                SubagentTask(args.task, args.context),
                SubagentContext(agentContext)
            )
            ToolExecutionResult(result)
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
        val tools: List<String>? = null,
    )
}

/**
 * 将静态 Subagent 注册表与可选的动态 Subagent 工具注册到 Agent。
 *
 * 静态部分（[registry] 非 null）通过 [io.github.yeyi.agent.capability.CapabilityAdapter] 注入；动态部分（[dynamic] = true）注册
 * `dynamic_subagent` 工具，允许 LLM 在调用时按 role/context/task/tools 并发派生临时子代理。
 *
 * @param dynamic true 注册 `dynamic_subagent` 工具，false 不注册
 * @param registry 静态 Subagent 注册中心；为 null 时跳过静态注册（仅注册动态）
 */
public fun AgentBuilder.subagents(
    dynamic: Boolean,
    registry: SubagentRegistry? = null
) {
    if (dynamic) tool(DynamicSubagentTool())
    if (registry != null) subagents(registry)
}
