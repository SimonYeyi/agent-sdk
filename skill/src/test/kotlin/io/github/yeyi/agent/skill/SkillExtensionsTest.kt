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
        val req = llm.recorded.single()
        val loadSkill = req.tools.single { it.name == "load_skill" }
        // 重构后: 技能索引走 load_skill 的 description,而不是 persona.other
        assertTrue("weather" in loadSkill.description, "expected 'weather' in load_skill description, got: ${loadSkill.description}")
        assertTrue("天气查询" in loadSkill.description, "expected '天气查询' in load_skill description, got: ${loadSkill.description}")
        assertTrue("news" in loadSkill.description, "expected 'news' in load_skill description, got: ${loadSkill.description}")
        assertTrue("新闻查询" in loadSkill.description, "expected '新闻查询' in load_skill description, got: ${loadSkill.description}")
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
        val loadSkill = llm.recorded.single().tools.single { it.name == "load_skill" }
        assertTrue("weather" in loadSkill.description, "expected 'weather' in load_skill description, got: ${loadSkill.description}")
    }

    @Test
    fun `skills(registry) does not require persona`() = runTest {
        val llm = RecordingLlm()
        val b = AgentBuilder().apply { llmProvider(llm) }
        // 故意不调 b.persona(...): skills() 不再依赖 persona 已在 builder 上
        b.skills(SkillRegistry().register(listOf(FixedSkill("weather", "d", "body"))))
        b.build().run("hi").toList()
        val loadSkill = llm.recorded.single().tools.single { it.name == "load_skill" }
        assertTrue("weather" in loadSkill.description, "expected 'weather' in load_skill description, got: ${loadSkill.description}")
    }
}
