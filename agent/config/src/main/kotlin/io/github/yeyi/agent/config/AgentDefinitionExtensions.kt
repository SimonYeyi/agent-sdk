package io.github.yeyi.agent.config

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.mcp.mcps
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.subagents
import io.github.yeyi.agent.tool.lazy_loading.lazyTools
import io.github.yeyi.agent.toolset.toolsets

/**
 * 从 AgentDefinition 和 AgentScope 构建 Agent 实例。
 *
 * @param scope 运行时上下文
 * @return Agent 实例
 */
public fun AgentDefinition.spawn(scope: AgentScope): Agent {
    val resolveInfo = scope.resolver().resolve(this@spawn)
    return agent {
        persona(Persona(persona))
        llmProvider(resolveInfo.llmProvider)
        memory(scope.memory, maxMemoryRounds)
        maxIterations(maxIterations)
        resolveInfo.toolRegistry?.let { tools(it) }
        resolveInfo.lazyToolRegistry?.let { lazyTools(it) }
        resolveInfo.toolsetRegistry?.let { toolsets(it) }
        resolveInfo.skillRegistry?.let { skills(it) }
        resolveInfo.subagentRegistry?.let { subagents(it) }
        resolveInfo.mcpRegistry?.let { mcps(it) }
    }
}
