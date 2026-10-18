package io.github.yeyi.agent.team

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.config.AgentDefinition
import io.github.yeyi.agent.config.AgentScope

/**
 * 从 AgentDefinition 构建 BossAgent。
 *
 * @param scope 运行时上下文
 * @return BossAgent 实例
 */
public fun AgentDefinition.spawnBoss(scope: AgentScope): BossAgent {
    val resolveInfo = scope.resolver().resolve(this@spawnBoss)
    return bossAgent {
        memory(scope.memory, maxMemoryRounds)
        llmProvider(resolveInfo.llmProvider)
        maxIterations(maxIterations)
        persona(Persona(persona))
        resolveInfo.toolRegistry?.let { tools(it) }
        resolveInfo.lazyToolRegistry?.let { lazyTools(it) }
        resolveInfo.toolsetRegistry?.let { toolsets(it) }
        resolveInfo.skillRegistry?.let { skills(it) }
        resolveInfo.subagentRegistry?.let { subagents(it) }
        resolveInfo.mcpRegistry?.let { mcps(it) }
    }
}
