package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.capability.Capability
import io.github.yeyi.agent.toDefinition
import io.github.yeyi.agent.tool.Tool

/**
 * 延迟加载的工具封装，自身是一个 [Capability]。
 *
 * - 作为 [Capability] 由能力框架自动适配为 `load_lazy_tool`（委托模式）
 * - 实际工具调用通过 [ToolCaller] 统一代理
 *
 * @see io.github.yeyi.agent.tool.lazy_loading.lazyTools DSL
 */
public interface LazyTool : Capability<Unit, SchemaLazyContext> {
    /** 被包装的实际工具。 */
    public val tool: Tool

    /** 延迟加载级别。 */
    public val level: Level

    public override val name: String get() = tool.name
    public override val description: String get() = tool.description

    /**
     * 默认实现：返回被包装工具的参数 schema。
     */
    public override suspend fun activate(
        arguments: Unit?,
        context: SchemaLazyContext,
    ): String =
        "LazyTool '${name}' 参数 schema：${tool.toDefinition().parametersSchema}（通过 tool_caller 调用）"

    public companion object {
        /** 能力框架中的路由类型，生成工具名 `load_lazy_tool`、路由字段 `tool_name`。 */
        public const val CAPABILITY_TYPE: String = "lazy_tool"
    }

    /** 延迟加载级别。 */
    public enum class Level {
        /** 延迟参数 schema，激活时返回工具的 schema。 */
        SCHEMA,

        /** 延迟整个工具，按描述搜索发现工具后执行。 */
        TOOL
    }
}

/** 工厂函数：将 [Tool] 包装为 [LazyTool]，默认 level 为 [LazyTool.Level.SCHEMA]。 */
public fun LazyTool(tool: Tool, level: LazyTool.Level = LazyTool.Level.SCHEMA): LazyTool =
    object : LazyTool {
        override val tool: Tool = tool
        override val level: LazyTool.Level = level
    }
