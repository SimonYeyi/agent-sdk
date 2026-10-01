package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.AgentBuilder

/**
 * 注册多个 [LazyTool] 到 Agent。
 *
 * 根据 [LazyTool.level] 分流：
 * - [LazyTool.Level.SCHEMA] → [SchemaLazyPlugin]
 * - [LazyTool.Level.TOOL] → [ToolSearchPlugin]
 *
 * 两个 plugin 共用同一个 [ToolCaller] 实例。
 *
 * @param registry LazyTool 注册中心，含所有待注册的 LazyTool 实例
 */
public fun AgentBuilder.lazyTools(registry: LazyToolRegistry) {
    val lazyTools = registry.all()
    val schemaLazyTools = lazyTools.filter { it.level == LazyTool.Level.SCHEMA }
    val toolSearchTools = lazyTools.filter { it.level == LazyTool.Level.TOOL }

    val toolCaller = ToolCaller(registry)  // 全量 registry

    // SCHEMA level → 过滤后的 registry
    if (schemaLazyTools.isNotEmpty()) {
        val schemaLazyRegistry = LazyToolRegistry()
        schemaLazyTools.forEach { schemaLazyRegistry.register(it) }
        plugin(SchemaLazyPlugin(schemaLazyRegistry, toolCaller))
    }

    // TOOL level → 过滤后的 registry
    if (toolSearchTools.isNotEmpty()) {
        val toolSearchRegistry = LazyToolRegistry()
        toolSearchTools.forEach { toolSearchRegistry.register(it) }
        plugin(ToolSearchPlugin(toolSearchRegistry, toolCaller))
    }
}
