package io.github.yeyi.agent

import io.github.yeyi.agent.tool.Tool

/**
 * Agent 插件接口。
 *
 * 插件本身持有配置，通过 [install] 安装到 [AgentPluginContext]。
 *
 * 使用方式：
 * ```kotlin
 * // 1. plugin(MyPlugin()) { config.xxx() }
 * agent {
 *     plugin(MyPlugin()) {
 *         addItem(...)
 *     }
 * }
 *
 * // 2. plugin(MyPlugin(config))
 * val config = MyConfig().apply { addItem(...) }
 * agent {
 *     plugin(MyPlugin(config))
 * }
 * ```
 *
 * 插件只能通过 [context][AgentPluginContext] 添加工具或追加 persona 内容，
 * 无法访问或修改 Agent 的其他配置。
 *
 * @param C 配置类型
 */
public interface AgentPlugin<C : Any> {
    public val id: String
    public val config: C

    /**
     * 将插件安装到 [AgentPluginContext]。
     *
     * 插件只能通过 [AgentPluginContext.registerTool] 注册工具，
     * 或通过 [AgentPluginContext.appendPersona] 追加 persona 内容。
     */
    public fun install(context: AgentPluginContext)
}

/**
 * 插件上下文 — 插件只能通过此接口操作 Agent。
 *
 * 只暴露 [registerTool] 和 [appendPersona] 两个方法，防止插件覆盖 Agent 核心配置。
 */
public interface AgentPluginContext {
    /**
     * 注册一个工具。
     */
    public fun registerTool(tool: Tool)

    /**
     * 向 persona 追加额外提示词。
     *
     * 多次调用会追加多行，在 [build] 时统一合并到 persona。
     */
    public fun appendPersona(label: String, content: String)
}
