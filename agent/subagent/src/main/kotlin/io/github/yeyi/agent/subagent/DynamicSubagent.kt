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
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal class DynamicSubagentTool : Tool {
    override val name: String = "dynamic_subagent"

    override val description: String = """
        |根据给定的 role、context 和一组 task，动态创建多个并发的临时子代理。
        |每个 task 都会启动一个独立的子代理，使用相同的 role / context / tool_list 配置。
        |适用于需要并行执行多个独立任务的场景（如多角度分析、批量处理、对比实验）。
        |不要用于子代理间通信、子代理间相互依赖、或需要完整主 Agent 上下文的场景。
    """.trimMargin()

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
          "type": "object",
          "properties": {
            "role": {
              "type": "string",
              "description": "子代理的角色定位，如'严谨的代码审查员'、'热情的客服'等。"
            },
            "context": {
              "type": "string",
              "description": "当前任务的上下文背景信息，供子代理理解任务环境。"
            },
            "tasks": {
              "type": "array",
              "items": { "type": "string" },
              "minItems": 1,
              "description": "并发执行的任务列表；每个 task 生成一个独立的临时子代理，使用相同的 role / context / tool_list 配置。"
            },
            "tool_list": {
              "type": "array",
              "items": { "type": "string" },
              "description": "可选：从主 agent 工具中挑选子集（需排除 dynamic_subagent / load_subagent 等 subagent 相关工具，防递归）。缺省 = 继承过滤后的全部主工具；传 [] = 无工具。"
            }
          },
          "required": ["role", "tasks"],
          "additionalProperties": false
        }
    """.trimIndent()
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val task = Json.decodeFromJsonElement(Task.serializer(), arguments)
        if (task.role.isEmpty()) return ToolExecutionResult.error("role is required")
        if (task.tasks.isEmpty()) return ToolExecutionResult.error("tasks must contain at least one task")
        val resolvedTools = resolveToolList(task.toolList, context.agentContext.tools)
        val results = supervisorScope {
            task.tasks.mapIndexed { i, t ->
                async { runOne(i, t, task.role, task.context, resolvedTools, context.agentContext) }
            }.awaitAll()
        }
        val hasError = results.any { it.isError }
        return ToolExecutionResult(formatResults(results), isError = hasError)
    }

    private fun formatResults(results: List<ToolExecutionResult>): String =
        results.mapIndexed { i, r -> "[${i + 1}] ${r.content}" }.joinToString("\n\n")

    private suspend fun runOne(
        index: Int,
        task: String,
        role: String,
        context: String?,
        tools: List<Tool>?,
        agentContext: AgentContext,
    ): ToolExecutionResult {
        val instruction = buildString {
            append("role: $role")
            if (!context.isNullOrEmpty()) append("\n\ncontext: $context")
        }
        return try {
            val sub = subagent(
                name = "dynamic_$index",
                description = "dynamic task $index",
                instruction = instruction,
                tools = tools,
            )
            ToolExecutionResult(sub.run(SubagentTask(task), SubagentContext(agentContext)))
        } catch (e: Exception) {
            log.warn("dynamic subagent execute failed: task=$task", e)
            ToolExecutionResult.error(e.message ?: "<no message>")
        }
    }

    private fun resolveToolList(toolNames: List<String>?, mainTools: List<Tool>): List<Tool>? {
        val mainTools = mainTools.associateBy { it.name }
        return toolNames?.mapNotNull { mainTools[it] }
    }

    @Serializable
    data class Task(
        val role: String,
        val context: String? = null,
        val tasks: List<String>,
        @SerialName("tool_list") val toolList: List<String>? = null,
    )
}

/**
 * 将静态 Subagent 注册表与可选的动态 Subagent 工具注册到 Agent。
 *
 * 静态部分（[registry] 非 null）通过 [io.github.yeyi.agent.capability.CapabilityAdapter] 注入；动态部分（[dynamic] = true）注册
 * `dynamic_subagent` 工具，允许 LLM 在调用时按 role/context/tasks 并发派生临时子代理。
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
