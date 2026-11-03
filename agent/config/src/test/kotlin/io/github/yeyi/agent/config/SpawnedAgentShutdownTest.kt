package io.github.yeyi.agent.config

import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.memory.InMemoryMemory
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

/**
 * spawn 产物的关闭后契约 —— 覆盖 [AgentScopeTest] 中 spawnedAgent 用例未涉及的
 * 「shutdown 后的能力行为」：run / runStream 因 delegate 的 runTurn 守卫抛 ISE，
 * steer 跟随 delegate 原生语义返回 false。
 */
class SpawnedAgentShutdownTest {

    @Test
    fun `spawned shutdown is idempotent and runStream run reject steer false afterwards`() = runTest {
        val definition = AgentDefinition(
            name = "t",
            provider = AgentDefinition.Provider.Anthropic(
                baseUrl = "http://localhost",
                model = "m",
                apiKey = "k"
            )
        )
        val agent: SpawnedAgent = definition.spawn(AgentScope(memory = InMemoryMemory()))

        agent.shutdown()
        agent.shutdown() // 幂等：第二次无副作用

        assertFailsWith<IllegalStateException> { agent.run(AgentQuery.text("again")) }
        assertFalse(agent.steer(AgentQuery.text("late"))) // 跟随 delegate：无活跃回合返回 false
        assertFailsWith<IllegalStateException> { agent.runStream(AgentQuery.text("later")) }
    }
}
