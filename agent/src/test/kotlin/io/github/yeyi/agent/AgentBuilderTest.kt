package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentBuilderTest {

    private fun fakeClient() = FakeLlmClient(
        nonStreamResponses = listOf(
            ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
        )
    )

    @Test
    fun `missing llmClient throws`() {
        assertFailsWith<IllegalArgumentException> {
            agent { systemPrompt = "x" }
        }
    }

    @Test
    fun `agent built via DSL can actually run`() = runTest {
        val a = agent {
            llmClient = fakeClient()
        }
        val r = a.run("hi").awaitResult()
        assertEquals("ok", r.message.content)
    }
}
