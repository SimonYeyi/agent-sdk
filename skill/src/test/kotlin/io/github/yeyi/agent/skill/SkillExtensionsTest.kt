package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.capability.CapabilityAdapter
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
        override suspend fun load(): String = content
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
    fun `skills with OneToOne mode makes skill_ prefixed tool visible to the LLM`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        val registry = SkillRegistry()
        registry.register(FixedSkill(name = "weather", description = "d", content = "B"))
        b.skills(registry, enableDelegateAdaptMode = false)
        b.build().run("hi").toList()
        val req = llm.recorded.single()
        val toolNames = req.tools.map { it.name }
        assertTrue("skill_weather" in toolNames, "expected skill_weather in $toolNames")
    }

    @Test
    fun `skills with OneToOne mode does NOT register any tools other than skill_ handle`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        val registry = SkillRegistry()
        registry.register(FixedSkill(name = "weather", description = "d", content = "use get_weather"))
        b.skills(registry, enableDelegateAdaptMode = false)
        b.build().run("hi").toList()
        val toolNames = llm.recorded.single().tools.map { it.name }
        assertEquals(listOf("skill_weather"), toolNames)
    }

    @Test
    fun `skills with Delegate mode registers load_skill tool with correct prompt`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.persona(Persona("x"))
        val registry = SkillRegistry()
        registry.register(
            listOf(
                FixedSkill("weather", "天气查询", "body1"),
                FixedSkill("news", "新闻查询", "body2"),
            )
        )
        b.skills(registry)
        b.build().run("hi").toList()
        val req = llm.recorded.single()
        val loadSkill = req.tools.single { it.name == "load_skill" }
        assertTrue("weather" in loadSkill.description, "expected 'weather' in load_skill description, got: ${loadSkill.description}")
        assertTrue("天气查询" in loadSkill.description, "expected '天气查询' in load_skill description, got: ${loadSkill.description}")
        assertTrue("news" in loadSkill.description, "expected 'news' in load_skill description, got: ${loadSkill.description}")
        assertTrue("新闻查询" in loadSkill.description, "expected '新闻查询' in load_skill description, got: ${loadSkill.description}")
    }

    @Test
    fun `duplicate skill name throws on second registration`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill(name = "dup", description = "", content = "B"))
        assertFailsWith<IllegalArgumentException> {
            registry.register(FixedSkill(name = "dup", description = "", content = "B2"))
        }
    }

    @Test
    fun `OneToOne mode skill tool description matches skill description`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        val registry = SkillRegistry()
        registry.register(FixedSkill(name = "weather", description = "d", content = "## Weather\nStep 1"))
        b.skills(registry, enableDelegateAdaptMode = false)
        b.build().run("hi").toList()
        val toolDef = llm.recorded.single().tools.single { it.name == "skill_weather" }
        assertEquals("d", toolDef.description)
    }

    @Test
    fun `skills with Delegate mode and iterable registers load_skill tool`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        b.persona(Persona("x"))
        val registry = SkillRegistry()
        registry.register(listOf(FixedSkill("weather", "d", "body")))
        b.skills(registry)
        b.build().run("hi").toList()
        val loadSkill = llm.recorded.single().tools.single { it.name == "load_skill" }
        assertTrue("weather" in loadSkill.description, "expected 'weather' in load_skill description, got: ${loadSkill.description}")
    }

    @Test
    fun `skills with Delegate mode does not require persona`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        val registry = SkillRegistry()
        registry.register(listOf(FixedSkill("weather", "d", "body")))
        b.skills(registry)
        b.build().run("hi").toList()
        val loadSkill = llm.recorded.single().tools.single { it.name == "load_skill" }
        assertTrue("weather" in loadSkill.description, "expected 'weather' in load_skill description, got: ${loadSkill.description}")
    }
}
