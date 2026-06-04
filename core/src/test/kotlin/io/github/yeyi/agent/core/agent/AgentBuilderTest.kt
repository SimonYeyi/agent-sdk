package io.github.yeyi.agent.core.agent

import io.github.yeyi.agent.core.agent.fakes.EchoTool
import io.github.yeyi.agent.core.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.core.llm.ChatMessage
import io.github.yeyi.agent.core.llm.ChatResponse
import io.github.yeyi.agent.core.llm.FinishReason
import io.github.yeyi.agent.core.memory.InMemoryMemory
import io.github.yeyi.agent.core.skill.Skill
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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
        val r = a.run("hi")
        assertEquals("ok", r.finalMessage.content)
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
}
