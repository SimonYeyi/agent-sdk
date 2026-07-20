@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.team

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.memory.InMemoryMemory
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.test.assertTrue

class BossAgentBuilderTest {

    @Test
    fun `bossAgent DSL builds successfully`() = runTest {
        val boss = bossAgent {
            llmProvider(
                FakeLlmProvider(
                    nonStreamResponses = listOf(
                        ChatResponse(
                            message = ChatMessage.Assistant(content = "test"),
                            finishReason = FinishReason.Stop,
                        )
                    )
                )
            )
            memory(InMemoryMemory(), 20)
            maxIterations(1)
        }

        val events = boss.run("hello").toList()
        assertTrue(events.isNotEmpty())
        boss.shutdown()
    }
}