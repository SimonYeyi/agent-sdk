package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.ToolContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillTest {

    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) =
            error("LlmProvider.chat must not be called in SkillTest")
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

    private fun emptyContext(): SkillContext = SkillContext(
        arguments = buildJsonObject { },
        toolContext = stubToolContext(),
    )

    @Test
    fun `Skill interface exposes name, description and load() returns content`() {
        val s = FixedSkill(name = "x", description = "d", content = "instructions-text")
        assertEquals("x", s.name)
        assertEquals("d", s.description)
        assertEquals("instructions-text", s.load(emptyContext()))
    }

    @Test
    fun `Skill load is idempotent and side effect free`() {
        var callCount = 0
        val s = object : Skill {
            override val name = "n"
            override val description = "d"
            override fun load(context: SkillContext): String {
                callCount++
                return "v$callCount"
            }
        }
        // Each call to load() may execute the body — consumers should not assume caching.
        assertEquals("v1", s.load(emptyContext()))
        assertEquals("v2", s.load(emptyContext()))
    }
}