package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * 工具集 — 自身作为一个 Tool 暴露给 LLM，执行时返回子 Tool 的完整 JSON Schema；
 * LLM 再通过统一的代理 Tool `call_<toolset_name>` 调用具体子 Tool。
 *
 * 子 Tool **不**注册到 [io.github.yeyi.agent.tool.ToolRegistry]，只能通过对应 Toolset
 * 的 [dispatch] 调用 — 这是节省首轮上下文开销的根本保证。
 *
 * @see toolset 顶级工厂
 * @see callTool 扩展方法获取代理 Tool
 */
public interface Toolset : Tool {
    override val parametersSchema: ToolParameters get() = ToolParameters.Empty

    /** 添加单个子 Tool。重复名抛 [IllegalArgumentException]。 */
    public fun add(tool: Tool)

    /** 批量添加子 Tool。 */
    public fun add(tools: Iterable<Tool>)

    /**
     * 把 LLM 生成的子 Tool 调用转发给对应子 Tool 执行。
     * 子 Tool 不存在时返回 [ToolExecutionResult.error]，不抛异常。
     */
    public suspend fun dispatch(
        toolName: String,
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult
}

/**
 * 顶级工厂 — 创建一个默认本地实现的 [Toolset]。
 *
 * 用法：
 * ```kotlin
 * val weatherToolset = toolset("weather", "天气相关工具集").apply {
 *     add(GetWeatherTool())
 *     add(GetForecastTool())
 * }
 * ```
 */
public fun toolset(name: String, description: String): Toolset =
    DefaultToolset(name, description)

/**
 * DSL — 将 [Toolset] 注册到 [AgentBuilder]。
 * 同时注册 Toolset 自身作为 Tool + 代理 Tool (`<toolset.name>_tool_delegate`)。
 */
public fun AgentBuilder.toolset(toolset: Toolset) {
    tool(toolset)
    tool(SubToolDelegate(toolset))
}

/**
 * 默认本地实现 — 持有 subTools Map，提供 add / dispatch / execute 的完整实现。
 */
private class DefaultToolset(
    override val name: String,
    override val description: String,
) : Toolset {
    private val subTools: MutableMap<String, Tool> = LinkedHashMap()

    override fun add(tool: Tool) {
        require(tool.name !in subTools) {
            "Tool '${tool.name}' already in toolset '$name'"
        }
        subTools[tool.name] = tool
    }

    override fun add(tools: Iterable<Tool>) {
        tools.forEach(::add)
    }

    override suspend fun dispatch(
        toolName: String,
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val tool = subTools[toolName]
            ?: return ToolExecutionResult.error(
                AgentException.ToolNotFound(
                    toolName,
                    subTools.keys
                ).message
            )
        return tool.execute(arguments, context)
    }

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val definitions = buildJsonArray {
            subTools.values.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parametersSchema", parametersSchemaOf(tool.parametersSchema))
                })
            }
        }
        return ToolExecutionResult.success(
            "Toolset '$name' 包含以下子工具 (完整 schema):\n$definitions"
        )
    }

    private fun parametersSchemaOf(params: ToolParameters): JsonElement = when (params) {
        is ToolParameters.Empty -> JsonObject(emptyMap())
        is ToolParameters.JsonSchema -> Json.parseToJsonElement(params.schema)
    }
}

/**
 * 代理 Tool — 接收 LLM 的 `{name, arguments}` 调用，转发给对应子 Tool。
 */
internal class SubToolDelegate(private val toolset: Toolset) : Tool {
    override val name: String = "${toolset.name}_tool_delegate"
    override val description: String = "调用 ${toolset.name} 工具集内的子工具"
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        """
        {
            "type": "object",
            "properties": {
                "name": { "type": "string", "description": "子 Tool 名" },
                "arguments": { "type": "object", "description": "子 Tool 参数" }
            },
            "required": ["name", "arguments"]
        }
        """.trimIndent()
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val argsObj = arguments.jsonObject
        val toolName = argsObj["name"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error("Missing 'name'")
        val toolArgs = argsObj["arguments"] ?: JsonNull
        return toolset.dispatch(toolName, toolArgs, context)
    }
}