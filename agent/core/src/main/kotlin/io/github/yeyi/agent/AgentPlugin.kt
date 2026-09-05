package io.github.yeyi.agent

/**
 * Agent 插件接口。
 *
 * 插件本身持有配置，通过 [install] 安装到 AgentBuilder。
 *
 * 使用方式：
 * ```kotlin
 * // 1. install(plugin) { config.xxx() }
 * agent {
 *     install(MyPlugin()) {
 *         addItem(...)
 *     }
 * }
 *
 * // 2. install(plugin, existingConfig)
 * val config = MyConfig().apply { addItem(...) }
 * agent {
 *     install(MyPlugin(config))
 * }
 * ```
 *
 * @param C 配置类型
 */
public interface AgentPlugin<C : Any> {
    public val id: String
    public val config: C

    /**
     * 将插件安装到 [builder]。
     *
     * 子类在此方法中：
     * - 通过 `builder.tool(...)` 注册工具
     * - 通过 `builder.persona(...)` 修改 persona
     * - 或其他 builder 提供的扩展点
     */
    public fun install(builder: AgentBuilder)
}
