package io.github.yeyi.agent.config

import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.memory.InMemoryMemory
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertFailsWith

/**
 * spawn 产物的关闭后契约 —— 覆盖 [AgentScopeTest] 中 spawnedAgent 用例未涉及的
 * 「收集后全路径拒绝」：shutdown 后 run / runStream / steer 一律抛 ISE。
 */
class SpawnedAgentShutdownTest {

    @Test
    fun `spawned shutdown is idempotent and rejects runSteerRunStream afterwards`() = runTest {
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
        assertFailsWith<IllegalStateException> { agent.steer(AgentQuery.text("late")) }
        assertFailsWith<IllegalStateException> { agent.runStream(AgentQuery.text("later")) }
    }
}
