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
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoadSkillToolTest {

    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest) =
            error("LlmProvider.chat must not be called in LoadSkillToolTest")
        override fun chatStream(request: ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Error(IllegalStateException("not used")))
    }

    private fun stubContext(): ToolContext = ToolContext(
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
    fun `LoadSkillTool NAME is load_skill`() {
        val registry = SkillRegistry()
        assertEquals("load_skill", LoadSkillTool(registry).name)
    }

    @Test
    fun `LoadSkillTool description`() {
        val registry = SkillRegistry()
        assertContains(LoadSkillTool(registry).description, "当需要加载以下技能时，调用本工具")
    }

    @Test
    fun `LoadSkillTool description includes registered skills`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "天气查询助手", "body"))
        registry.register(FixedSkill("news", "新闻查询助手", "body2"))
        val desc = LoadSkillTool(registry).description
        assertTrue("weather" in desc, "expected skill name 'weather' in description, got: $desc")
        assertTrue("天气查询助手" in desc, "expected skill description '天气查询助手' in description, got: $desc")
        assertTrue("news" in desc, "expected skill name 'news' in description, got: $desc")
        assertTrue("新闻查询助手" in desc, "expected skill description '新闻查询助手' in description, got: $desc")
    }

    @Test
    fun `LoadSkillTool parameters schema has skill_name`() {
        val registry = SkillRegistry()
        val schema = (LoadSkillTool(registry).parametersSchema as ToolParameters.JsonSchema).schema
        assertTrue("skill_name" in schema)
    }

    @Test
    fun `execute loads skill content`() = runTest {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "d", "## weather body"))
        val tool = LoadSkillTool(registry)
        val result = tool.execute(
            buildJsonObject { put("skill_name", kotlinx.serialization.json.JsonPrimitive("weather")) },
            stubContext(),
        )
        assertEquals("## weather body", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun `execute returns error for unknown skill`() = runTest {
        val registry = SkillRegistry()
        val tool = LoadSkillTool(registry)
        val result = tool.execute(
            buildJsonObject { put("skill_name", kotlinx.serialization.json.JsonPrimitive("unknown")) },
            stubContext(),
        )
        assertTrue(result.isError)
        assertTrue("Skill not found" in result.content)
    }

    @Test
    fun `execute returns error for missing skill_name`() = runTest {
        val registry = SkillRegistry()
        val tool = LoadSkillTool(registry)
        val result = tool.execute(buildJsonObject { }, stubContext())
        assertTrue(result.isError)
        assertTrue("Missing skill_name" in result.content)
    }
}