package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder

/**
 * 注册多个 [Skill] 到 Agent。
 *
 * 该扩展函数：
 * 1. 将 [registry] 中的所有 Skill 安装到 AgentBuilder（通过 Capability 框架）
 * 2. 若 registry 含工具，则注册 [SkillToolLoader] 和 [SkillToolCaller]
 *
 * @param registry Skill 注册中心，含所有待注册的 Skill 实例
 * @param enableDelegateAdaptMode 是否启用委托适配模式，默认 true
 */
public fun AgentBuilder.skills(
    registry: SkillRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    plugin(SkillPlugin(registry, enableDelegateAdaptMode))
}
