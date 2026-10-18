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
    val resolver = scope.resolver()
    val toolsetRegistry = resolver.resolveToolsets(this@spawnBoss)
    val (toolRegistry, lazyToolRegistry) = resolver.resolveTools(this@spawnBoss)
    return bossAgent {
        memory(scope.memory, maxMemoryRounds)
        llmProvider(resolver.resolveProvider(this@spawnBoss))
        maxIterations(maxIterations)
        persona(Persona(persona))
        toolRegistry?.let { tools(it) }
        lazyToolRegistry?.let { lazyTools(it) }
        toolsetRegistry?.let { toolsets(it) }
        resolver.resolveSkills(this@spawnBoss)?.let { skills(it) }
        resolver.resolveSubagents(this@spawnBoss)?.let { subagents(it) }
        resolver.resolveMcps(this@spawnBoss, toolsetRegistry)?.let { mcps(it) }
    }
}
