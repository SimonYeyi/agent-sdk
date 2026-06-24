package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SkillToolTest {

    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) =
            error("LlmProvider.chat must not be called in SkillToolTest")
        override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Error(IllegalStateException("not used")))
    }

    private fun stubToolContext(): ToolContext = ToolContext(
        toolCallId = "test-call-id",
        agentContext = AgentContext(
            persona = Persona(""),
            maxIterations = 1,
            currentIteration = 1,
            memory = InMemoryMemory(),
            llmProvider = StubLlm,
            tools = emptyList(),
            maxRounds = 20,
        ),
    )

    private class FixedSkill(
        override val name: String,
        override val description: String,
        private val content: String,
    ) : Skill {
        override fun load(context: SkillContext): String = content
    }

    @Test
    fun `SkillTool name is prefixed with skill_`() {
        val s = FixedSkill(name = "weather", description = "weather", content = "BODY")
        assertEquals("skill_weather", SkillTool(s).name)
    }

    @Test
    fun `SkillTool description matches skill description`() {
        val s = FixedSkill(name = "x", description = "does X", content = "")
        assertEquals("does X", SkillTool(s).description)
    }

    @Test
    fun `SkillTool parameters schema is Empty`() {
        val s = FixedSkill(name = "x", description = "", content = "")
        assertEquals(ToolParameters.Empty, SkillTool(s).parametersSchema)
    }

    @Test
    fun `SkillTool execute returns skill load() as content and isError false`() = runTest {
        val s = FixedSkill(name = "x", description = "d", content = "## My skill body\nStep 1...")
        val result = SkillTool(s).execute(JsonNull, stubToolContext())
        assertEquals("## My skill body\nStep 1...", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun `SkillTool execute calls load() on each invocation`() = runTest {
        var calls = 0
        val s = object : Skill {
            override val name = "x"
            override val description = "d"
            override fun load(context: SkillContext): String {
                calls++
                return "v$calls"
            }
        }
        val tool = SkillTool(s)
        assertEquals("v1", tool.execute(JsonNull, stubToolContext()).content)
        assertEquals("v2", tool.execute(JsonNull, stubToolContext()).content)
    }

    @Test
    fun `SkillTool execute is independent of args`() = runTest {
        val s = FixedSkill(name = "x", description = "d", content = "body")
        val r1 = SkillTool(s).execute(JsonNull, stubToolContext())
        val r2 = SkillTool(s).execute(
            kotlinx.serialization.json.JsonPrimitive("ignored"),
            stubToolContext(),
        )
        assertEquals(r1.content, r2.content)
    }
}
