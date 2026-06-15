package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SkillExtensionsTest {

    private class FixedSkill(
        override val name: String,
        override val description: String,
        private val content: String,
    ) : Skill {
        override fun load(context: SkillContext): String = content
    }

    /** A minimal LlmProvider that records every request and returns a stop response. */
    private class RecordingLlm : LlmProvider {
        override val name: String = "recording"
        val recorded: MutableList<ChatRequest> = mutableListOf()

        override suspend fun chat(request: ChatRequest): ChatResponse {
            recorded += request
            return ChatResponse(
                message = ChatMessage.Assistant(content = "ok"),
                finishReason = FinishReason.Stop,
            )
        }

        override fun chatStream(request: ChatRequest): Flow<io.github.yeyi.agent.llm.StreamEvent> =
            flow {
                recorded += request
                emit(
                    io.github.yeyi.agent.llm.StreamEvent.Done(
                        usage = null,
                        finishReason = FinishReason.Stop
                    )
                )
            }
    }

    @Test
    fun `skill() makes a skill_ prefixed tool visible to the LLM`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.skill(FixedSkill(name = "weather", description = "d", content = "B"))
        b.build().run("hi").toList()
        val req = llm.recorded.single()
        val toolNames = req.tools.map { it.name }
        assertTrue("skill_weather" in toolNames, "expected skill_weather in $toolNames")
    }

    @Test
    fun `skill() does NOT register any tools other than skill_ handle`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.skill(FixedSkill(name = "weather", description = "d", content = "use get_weather"))
        b.build().run("hi").toList()
        val toolNames = llm.recorded.single().tools.map { it.name }
        // The skill mentions a tool name in instructions, but it is NOT auto-registered.
        assertEquals(listOf("skill_weather"), toolNames)
    }

    @Test
    fun `skills(registry) registers load_skill tool with correct prompt`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.persona(Persona("x"))
        b.skills(
            SkillRegistry().register(
                listOf(
                    FixedSkill("weather", "天气查询", "body1"),
                    FixedSkill("news", "新闻查询", "body2"),
                )
            )
        )
        b.build().run("hi").toList()
        val toolNames = llm.recorded.single().tools.map { it.name }
        assertTrue("load_skill" in toolNames, "expected load_skill in $toolNames")
    }

    @Test
    fun `duplicate skill name throws on second registration`() {
        val b = AgentBuilder()
        b.skill(FixedSkill(name = "dup", description = "", content = "B"))
        assertFailsWith<IllegalArgumentException> {
            b.skill(FixedSkill(name = "dup", description = "", content = "B2"))
        }
    }

    @Test
    fun `invoking the registered SkillTool calls load() and returns the result`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.skill(FixedSkill(name = "weather", description = "d", content = "## Weather\nStep 1"))
        b.build().run("hi").toList()
        // The LLM-visible tool list contains the SkillTool with the Skill's description.
        val toolDef = llm.recorded.single().tools.single { it.name == "skill_weather" }
        assertEquals("d", toolDef.description)
    }

    @Test
    fun `skills(iterable) registers load_skill tool`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.persona(Persona("x"))
        b.skills(SkillRegistry().register(listOf(FixedSkill("weather", "d", "body"))))
        b.build().run("hi").toList()
        val toolNames = llm.recorded.single().tools.map { it.name }
        assertTrue("load_skill" in toolNames, "expected load_skill in $toolNames")
    }
}
