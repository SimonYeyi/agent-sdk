package io.github.yeyi.agent.capability

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolDuplicateException

/**
 * Capability 包的接线契约 —— 模块实现者继承本抽象类并 override 接线部件 = 完成一个能力。
 *
 * 单一契约,无外部助手类:
 * - 抽象方法 [registry]: 调用方 new 后传入,plugin 持有并可继续 register
 * - 抽象方法 [contextFactory] / [arguments]: 必须 override
 * - 开放方法 [auxiliaryTools]: 默认空,需要 Loader/Caller/Delegate 等辅助 tool 时覆写
 *
 * **面向能力模块作者,不面向调用方。** 外部仍按现状
 * `XxxRegistry().apply { register(...) }` + `AgentBuilder.xxxs(registry)`。
 *
 * @param C capability 类型
 * @param T arguments 类型
 * @param Ctx capability context 类型
 */
public abstract class CapabilityPlugin<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext> {

    /** 调用方 new 后传入;plugin 持有并可继续 register。 */
    protected abstract fun registry(): CapabilityRegistry<C, T, Ctx>

    /** 把 ToolContext 装成能力专属 context 的工厂。 */
    protected abstract fun contextFactory(): CapabilityContextFactory<Ctx>

    /** arguments schema + serializer;无 arguments 传 null。 */
    protected abstract fun arguments(): CapabilityArguments<T>?

    /** 框架自带辅助 tool —— 默认空。Skill/Toolset 等需要补 Loader/Caller/Delegate 时覆写。 */
    protected open fun auxiliaryTools(): List<Tool> = emptyList()

    /**
     * 安装到 [AgentBuilder]。
     *
     * 工具名冲突抛 [InstallException](cause 链保留 [ToolDuplicateException])。
     *
     * @param enableDelegateAdaptMode true 委托模式（单一 Delegate Tool），
     *                                false 一一映射模式（每个 Capability 一个 Tool）。
     */
    public fun installOn(
        agentBuilder: AgentBuilder,
        enableDelegateAdaptMode: Boolean = true,
    ) {
        try {
            CapabilityAdapter.of(registry(), contextFactory(), arguments(), enableDelegateAdaptMode)
                .installOn(agentBuilder)
            auxiliaryTools().forEach { agentBuilder.tool(it) }
        } catch (e: ToolDuplicateException) {
            throw InstallException("Capability installation failed", e)
        }
    }

    /**
     * Capability 安装阶段冲突 —— 见 [installOn] 的捕获逻辑。
     *
     * cause 链保留原始异常信息(冲突 tool 名及已存在列表)。
     */
    public class InstallException(msg: String, cause: Throwable? = null) :
        IllegalStateException(msg, cause)
}
