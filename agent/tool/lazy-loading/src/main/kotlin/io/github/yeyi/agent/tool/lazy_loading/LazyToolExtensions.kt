package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityRegistry

/**
 * 注册多个 [LazyTool] 到 Agent。
 *
 * 按 [LazyTool.level] 分流：
 * - [LazyTool.Level.SCHEMA] → [SchemaLazyPlugin]
 * - [LazyTool.Level.TOOL] → [ToolSearchPlugin]
 *
 * 不拷贝数据：[registry] 是唯一事实源，两个 plugin 持有它的
 * level 过滤视图（[LazyToolLevelView]），[DynamicToolCaller] 持有 [registry] 本体 ——
 * 之后向 [registry] 增删 LazyTool，`search_tool` / `load_lazy_tool` / `dynamic_tool_caller`
 * 三条路径全部实时生效，无需重建 Agent。
 *
 * 插件安装按 DSL 调用时的内容判定：某 level 在调用时为空则不安装对应 plugin，
 * 之后即使向该 level 动态添加工具也没有入口（不会动态长出插件）。
 *
 * 两个 plugin 共用同一个 [DynamicToolCaller] 实例。
 *
 * @param registry LazyTool 注册中心，含所有待注册的 LazyTool 实例
 */
public fun AgentBuilder.lazyTools(registry: LazyToolRegistry) {
    val dynamicToolCaller = DynamicToolCaller(registry)  // 全量 registry

    val schemaLazyTools = LazyToolLevelView(registry, LazyTool.Level.SCHEMA)
    val toolSearchTools = LazyToolLevelView(registry, LazyTool.Level.TOOL)

    if (!schemaLazyTools.isEmpty()) {
        plugin(SchemaLazyPlugin(schemaLazyTools, dynamicToolCaller))
    }
    if (!toolSearchTools.isEmpty()) {
        plugin(ToolSearchPlugin(toolSearchTools, dynamicToolCaller))
    }
}

/**
 * [LazyTool] 的 level 读取视图 —— 不持有数据拷贝，[all]/[isEmpty] 实时过滤底层 registry。
 *
 * 非对称契约：仅 [all]/[isEmpty] 带 level 语义，其余方法直接委托底层 registry，
 * 不做 level 过滤或校验（如 register 任意 level 均成功，但 [all] 仅返回匹配项）。
 * 消费方只经由 [all] 与 [isEmpty]。
 */
private class LazyToolLevelView(
    private val source: LazyToolRegistry,
    private val level: LazyTool.Level,
) : CapabilityRegistry<LazyTool, Unit, SchemaLazyContext> by source {

    private fun scoped(): List<LazyTool> = source.all().filter { it.level == level }

    override fun all(): List<LazyTool> = scoped()

    override fun isEmpty(): Boolean = scoped().isEmpty()
}
