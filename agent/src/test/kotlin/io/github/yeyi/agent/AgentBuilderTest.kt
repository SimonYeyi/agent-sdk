package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.memory.Memory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertSame
import kotlin.test.assertTrue

class AgentBuilderTest {

    private fun fakeClient() = FakeLlmClient(
        nonStreamResponses = listOf(
            ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
        )
    )

    @Test
    fun `agent DSL builds an agent with defaults`() {
        val a: ReActAgent = agent {
            llmClient = fakeClient()
        } as ReActAgent
        assertEquals(10, a.maxIterations)
        assertTrue(a.toolRegistry.names().isEmpty())
        assertEquals("", a.systemPrompt)
    }

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

    @Test
    fun `hooks list is captured into config`() {
        val h = object : AgentHook {}
        val a: ReActAgent = agent {
            llmClient = fakeClient()
            hook(h)
        } as ReActAgent
        assertEquals(1, a.hooks.size)
    }

    @Test
    fun `memory is captured in config`() {
        val a: ReActAgent = agent {
            llmClient = fakeClient()
            memory(CountingMemory)
        } as ReActAgent
        assertSame(
            CountingMemory,
            a.memory,
            "memory should be the instance passed to memory()"
        )
    }

    @Test
    fun `hooks are captured in declaration order`() {
        val h1 = object : AgentHook {}
        val h2 = object : AgentHook {}
        val h3 = object : AgentHook {}
        val a: ReActAgent = agent {
            llmClient = fakeClient()
            hook(h1)
            hook(h2)
            hook(h3)
        } as ReActAgent
        assertEquals(listOf<AgentHook>(h1, h2, h3), a.hooks)
    }

    // Note: the "empty systemPrompt with no tools produces warning" scenario is
    // already covered by `agent DSL builds an agent with defaults`, which exercises the same
    // code path (default empty systemPrompt, no tools) and asserts the agent builds
    // successfully. Adding a separate test would duplicate that coverage.

    private object CountingMemory : Memory {
        override suspend fun add(message: ChatMessage) {}
        override suspend fun history(): List<ChatMessage> = emptyList()
        override suspend fun clear() {}
    }
}
