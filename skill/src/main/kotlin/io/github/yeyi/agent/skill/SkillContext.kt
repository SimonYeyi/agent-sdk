package io.github.yeyi.agent.skill

import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.ToolContext

/**
 * Skill 执行时的上下文，继承自 [io.github.yeyi.agent.capability.CapabilityContext]。
 *
 * 当前为空标记类，保留扩展余地。
 */
public class SkillContext : CapabilityContext

internal class SkillContextFactory : CapabilityContextFactory<SkillContext> {
    override fun create(context: ToolContext): SkillContext = SkillContext()
}
