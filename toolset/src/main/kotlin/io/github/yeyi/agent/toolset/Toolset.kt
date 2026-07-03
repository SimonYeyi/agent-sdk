package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.capability.Capability
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolDispatcher
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 工具集 — 子 Tool 的容器，自身同时是 [Capability] / [ToolDispatcher]：
 * - 作为 [Capability] 由能力框架自动适配为 `load_toolset`（委托模式）或 `toolset_<name>`（一一映射模式）
 * - 作为 [ToolDispatcher] 把 LLM 生成的子 Tool 调用转发给对应子 Tool
 *
 * 子 Tool **不**注册到 [io.github.yeyi.agent.tool.ToolRegistry]，只能通过 [dispatch] 调用。
 *
 * 多 Toolset 统一管理请用 [ToolsetRegistry] + [toolsets] DSL。
 *
 * @see io.github.yeyi.agent.toolset.toolsets 一次性注册多个 Toolset
 */
public interface Toolset : Capability<Unit, ToolsetContext>, ToolDispatcher {
    /** 添加单个子 Tool。重复名抛 [IllegalArgumentException]。 */
    public fun add(tool: Tool)

    /** 批量添加子 Tool。 */
    public fun add(tools: Iterable<Tool>)

    public fun definitions(): JsonElement

    /**
     * 默认实现:把 [definitions] 拼上 "Toolset '$name' 包含以下子工具..." 前缀返回给 LLM。
     * 子类通常只需重写 [definitions] 即可;若需自定义文案再覆写本方法。
     */
    public override suspend fun activate(
        arguments: Unit?,
        context: ToolsetContext,
    ): String = "Toolset '$name' 包含以下子工具 (完整 schema):\n${definitions()}"

    public companion object {
        /** 能力框架中的路由类别名，生成工具名 `load_toolset`、路由字段 `toolset_name`。 */
        public const val CAPABILITY_NAME: String = "toolset"
    }
}

/**
 * 默认本地实现 — 持有 subTools Map，提供 add / dispatch / activate 的完整实现。
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

    override fun definitions(): JsonElement {
        return buildJsonArray {
            subTools.values.forEach { tool ->
                add(buildJsonObject {
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parametersSchema", parametersSchemaOf(tool.parametersSchema))
                })
            }
        }
    }

    override suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val tool = subTools[name]
            ?: return ToolExecutionResult.error(
                AgentException.ToolNotFound(
                    name,
                    subTools.keys
                ).message
            )
        return tool.execute(arguments, context)
    }

    private fun parametersSchemaOf(params: ToolParameters): JsonElement = when (params) {
        is ToolParameters.Empty -> JsonObject(emptyMap())
        is ToolParameters.JsonSchema -> Json.parseToJsonElement(params.schema)
    }
}

/**
 * 顶级工厂 — 创建一个默认本地实现的 [Toolset]。
 *
 * 用法：
 * ```kotlin
 * val weatherToolset = Toolset("weather", "天气相关工具集").apply {
 *     add(GetWeatherTool())
 *     add(GetForecastTool())
 * }
 * ```
 */
public fun Toolset(name: String, description: String): Toolset =
    DefaultToolset(name, description)
