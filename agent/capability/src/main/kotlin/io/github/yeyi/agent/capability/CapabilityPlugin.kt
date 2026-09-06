package io.github.yeyi.agent.capability

import io.github.yeyi.agent.AgentPlugin
import io.github.yeyi.agent.AgentPluginContext
import io.github.yeyi.agent.tool.Tool

/**
 * Capability 包的接线契约 —— 模块实现者继承本抽象类并 override 接线部件 = 完成一个能力。
 *
 * 单一契约,无外部助手类:
 * - 构造器参数 [registry] 和 [enableDelegateAdaptMode]: 调用方传入
 * - 抽象方法 [contextFactory] / [arguments]: 必须 override
 * - 开放方法 [auxiliaryTools]: 默认空,需要 Loader/Caller/Delegate 等辅助 tool 时覆写
 *
 * **面向能力模块作者,不面向调用方。** 外部使用方式:
 * ```kotlin
 * // 通过 plugin() DSL
 * agent {
 *     plugin(MyCapabilityPlugin()) {
 *         register(...)
 *     }
 * }
 * ```
 *
 * @param C capability 类型
 * @param T arguments 类型
 * @param Ctx capability context 类型
 * @param registry 能力注册中心
 * @param enableDelegateAdaptMode true 委托模式（单一 Delegate Tool），
 *                                false 一一映射模式（每个 Capability 一个 Tool）
 */
public abstract class CapabilityPlugin<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    private val registry: CapabilityRegistry<C, T, Ctx>,
    private val enableDelegateAdaptMode: Boolean
) : AgentPlugin<CapabilityRegistry<C, T, Ctx>> {

    /** 插件 ID，等于 registry.capabilityType。 */
    final override val id: String get() = registry.capabilityType

    /** 把 ToolContext 装成能力专属 context 的工厂。 */
    protected abstract fun contextFactory(): CapabilityContextFactory<Ctx>

    /** arguments schema + serializer;无 arguments 传 null。 */
    protected abstract fun arguments(): CapabilityArguments<T>?

    /** 框架自带辅助 tool —— 默认空。Skill/Toolset 等需要补 Loader/Caller/Delegate 等辅助 tool 时覆写。 */
    protected open fun auxiliaryTools(): List<Tool> = emptyList()

    final override fun configure(block: CapabilityRegistry<C, T, Ctx>.() -> Unit) {
        registry.block()
    }

    final override fun install(context: AgentPluginContext) {
        CapabilityAdapter.of(registry, contextFactory(), arguments(), enableDelegateAdaptMode)
            .adapt().forEach { context.registerTool(it) }
        auxiliaryTools().forEach { context.registerTool(it) }
    }
}
