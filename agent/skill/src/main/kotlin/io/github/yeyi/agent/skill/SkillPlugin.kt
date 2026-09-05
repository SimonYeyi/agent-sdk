package io.github.yeyi.agent.skill

import io.github.yeyi.agent.capability.CapabilityArguments
import io.github.yeyi.agent.capability.CapabilityPlugin
import io.github.yeyi.agent.tool.Tool

/**
 * Skill 的接线模板 —— override contextFactory + arguments（null）+ auxiliaryTools（条件返回）。
 *
 * 仅供 Skill 模块内部 `skills(registry, ...)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数,不感知本类。
 */
internal class SkillPlugin(
    private val registry: SkillRegistry, enableDelegateAdaptMode: Boolean = true
) : CapabilityPlugin<Skill, Unit, SkillContext>(registry, enableDelegateAdaptMode) {

    override fun contextFactory(): SkillContextFactory = SkillContextFactory()

    override fun arguments(): CapabilityArguments<Unit>? = null

    override fun auxiliaryTools(): List<Tool> {
        return if (registry.allTools().isEmpty()) emptyList()
        else listOf(SkillToolLoader(registry), SkillToolCaller(registry))
    }
}
