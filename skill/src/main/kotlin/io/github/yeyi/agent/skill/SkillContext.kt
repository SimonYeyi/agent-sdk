package io.github.yeyi.agent.skill

import io.github.yeyi.agent.capability.CapabilityContext
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.tool.ToolContext

public class SkillContext : CapabilityContext

internal class SkillContextFactory : CapabilityContextFactory<SkillContext> {
    override fun create(context: ToolContext): SkillContext = SkillContext()
}
