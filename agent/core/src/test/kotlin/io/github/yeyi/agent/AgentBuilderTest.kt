package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentBuilderTest {

    private fun fakeProvider() = FakeLlmProvider(
        nonStreamResponses = listOf(
            ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
        )
    )

    @Test
    fun `missing llmProvider throws`() {
        assertFailsWith<IllegalArgumentException> {
            agent { persona(Persona("x")) }
        }
    }

    @Test
    fun `agent built via DSL can actually run`() = runTest {
        val a = agent {
            llmProvider(fakeProvider())
        }
        val r = a.run("hi").awaitResult()
        assertEquals("ok", r.message.content)
    }
}
