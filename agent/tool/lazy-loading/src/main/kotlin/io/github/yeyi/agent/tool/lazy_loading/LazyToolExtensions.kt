package io.github.yeyi.agent.tool.lazy_loading

import io.github.yeyi.agent.AgentBuilder

/**
 * 注册多个 [LazyTool] 到 Agent。
 *
 * 该扩展函数：
 * 1. 将 [registry] 中的所有 LazyTool 安装到 AgentBuilder（通过 Capability 框架）
 * 2. 注册 [LazyToolCaller]
 *
 * @param registry LazyTool 注册中心，含所有待注册的 LazyTool 实例
 * @param enableDelegateAdaptMode 是否启用委托适配模式，默认 true
 */
public fun AgentBuilder.lazyTools(
    registry: LazyToolRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    plugin(LazyToolPlugin(registry, enableDelegateAdaptMode))
}
