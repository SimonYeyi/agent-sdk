package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.capability.Capability
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolDispatcher
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.toDefinition
import kotlinx.serialization.json.JsonElement

/**
 * 工具集 — 成员 Tool 的容器，自身同时是 [Capability] / [ToolDispatcher]：
 * - 作为 [Capability] 由能力框架自动适配为 `load_toolset`（委托模式）或 `toolset_<name>`（一一映射模式）
 * - 作为 [ToolDispatcher] 把 LLM 生成的成员 Tool 调用转发给对应成员 Tool
 *
 * 成员 Tool **不**注册到 [io.github.yeyi.agent.tool.ToolRegistry]，只能通过 [dispatch] 调用。
 *
 * 多 Toolset 统一管理请用 [ToolsetRegistry] + [toolsets] DSL。
 *
 * @see io.github.yeyi.agent.toolset.toolsets 一次性注册多个 Toolset
 */
public interface Toolset : Capability<Unit, ToolsetContext>, ToolDispatcher {
    /** 添加单个成员 Tool。重复名抛 [IllegalArgumentException]。 */
    public fun add(tool: Tool)

    /** 批量添加成员 Tool。 */
    public fun add(tools: Iterable<Tool>)

    /** 返回当前 Toolset 持有的所有成员 Tool 快照。 */
    public fun all(): List<Tool>

    /**
     * 默认实现:把 [all] 渲染为简明文本返回给 LLM —— 不预设 wire 格式,
     * MCP / LLM provider 等消费者可按需覆写本方法(例如嵌 JSON schema)。
     */
    public override suspend fun activate(
        arguments: Unit?,
        context: ToolsetContext,
    ): String = "Toolset '$name' 包含以下成员 Tool（通过 member_tool_delegate 调用它们）:\n${all().map { it.toDefinition() }}"

    public companion object {
        /** 能力框架中的路由类别名，生成工具名 `load_toolset`、路由字段 `toolset_name`。 */
        public const val CAPABILITY_TYPE: String = "toolset"
    }
}

/**
 * 默认本地实现 — 持有 memberTools Map，提供 add / dispatch / activate 的完整实现。
 */
private class DefaultToolset(
    override val name: String,
    override val description: String,
) : Toolset {
    private val memberTools: MutableMap<String, Tool> = LinkedHashMap()

    override fun add(tool: Tool) {
        require(tool.name !in memberTools) {
            "Tool '${tool.name}' already in toolset '$name'"
        }
        memberTools[tool.name] = tool
    }

    override fun add(tools: Iterable<Tool>) {
        tools.forEach(::add)
    }

    override fun all(): List<Tool> = memberTools.values.toList()


    override suspend fun dispatch(
        name: String,
        arguments: JsonElement,
        context: ToolContext
    ): ToolExecutionResult {
        val tool = memberTools[name]
            ?: return ToolExecutionResult.error(
                AgentException.ToolNotFound(
                    name,
                    memberTools.keys
                ).message
            )
        return tool.execute(arguments, context)
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
