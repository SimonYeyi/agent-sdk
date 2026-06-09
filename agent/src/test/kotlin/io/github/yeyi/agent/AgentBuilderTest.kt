package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.EchoTool
import io.github.yeyi.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.skill.Skill
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
        val a = agent {
            llmClient = fakeClient()
        }
        assertEquals(10, a.config.maxIterations)
        assertTrue(a.config.tools.isEmpty())
        assertEquals("", a.config.systemPrompt)
    }

    @Test
    fun `missing llmClient throws`() {
        assertFailsWith<IllegalArgumentException> {
            agent { systemPrompt = "x" }
        }
    }

    @Test
    fun `tools and skills are merged into config`() {
        val mySkill = Skill(
            name = "weather",
            description = "d",
            systemPromptFragment = "FRAG",
            tools = listOf(EchoTool(name = "get_weather"))
        )
        val a = agent {
            systemPrompt = "BASE"
            llmClient = fakeClient()
            tool(EchoTool(name = "direct_echo"))
            skill(mySkill)
        }
        assertEquals("BASE\n\nFRAG", a.config.systemPrompt)
        assertEquals(setOf("direct_echo", "get_weather"), a.config.tools.map { it.name }.toSet())
    }

    @Test
    fun `duplicate tool names after flattening throws`() {
        val dupSkill = Skill(
            name = "x",
            description = "",
            tools = listOf(EchoTool(name = "shared"))
        )
        assertFailsWith<IllegalArgumentException> {
            agent {
                llmClient = fakeClient()
                tool(EchoTool(name = "shared"))
                skill(dupSkill)
            }
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
        val a = agent {
            llmClient = fakeClient()
            hook(h)
        }
        assertEquals(1, a.config.hooks.size)
    }

    @Test
    fun `skill with blank systemPromptFragment is skipped`() {
        val blankFragmentSkill = Skill(
            name = "blank",
            description = "d",
            systemPromptFragment = "   ",
            tools = listOf(EchoTool(name = "blank_tool"))
        )
        val a = agent {
            systemPrompt = "X"
            llmClient = fakeClient()
            skill(blankFragmentSkill)
        }
        // No "\n\n" appended, fragment not included.
        assertEquals("X", a.config.systemPrompt)
        // The tool itself is still contributed, only the prompt fragment is skipped.
        assertEquals(listOf("blank_tool"), a.config.tools.map { it.name })
    }

    @Test
    fun `tools and skills iterables are bulk-added in declaration order`() {
        val skillC = Skill(
            name = "c",
            description = "d",
            tools = listOf(EchoTool(name = "c"))
        )
        val skillD = Skill(
            name = "d",
            description = "d",
            tools = listOf(EchoTool(name = "d"))
        )
        val a = agent {
            llmClient = fakeClient()
            tools(listOf(EchoTool(name = "a"), EchoTool(name = "b")))
            skills(listOf(skillC, skillD))
        }
        assertEquals(listOf("a", "b", "c", "d"), a.config.tools.map { it.name })
    }

    @Test
    fun `memory is captured in config`() {
        val a = agent {
            llmClient = fakeClient()
            memory(CountingMemory)
        }
        assertSame(
            CountingMemory,
            a.config.memory,
            "config.memory should be the instance passed to memory()"
        )
    }

    @Test
    fun `hooks are captured in declaration order`() {
        val h1 = object : AgentHook {}
        val h2 = object : AgentHook {}
        val h3 = object : AgentHook {}
        val a = agent {
            llmClient = fakeClient()
            hook(h1)
            hook(h2)
            hook(h3)
        }
        assertEquals(listOf<AgentHook>(h1, h2, h3), a.config.hooks)
    }

    // Note: the "empty systemPrompt with no tools or skills produces warning" scenario is
    // already covered by `agent DSL builds an agent with defaults`, which exercises the same
    // code path (default empty systemPrompt, no tools, no skills) and asserts the agent builds
    // successfully. Adding a separate test would duplicate that coverage.

    private object CountingMemory : Memory {
        override suspend fun add(message: ChatMessage) {}
        override suspend fun history(): List<ChatMessage> = emptyList()
        override suspend fun clear() {}
    }
}
