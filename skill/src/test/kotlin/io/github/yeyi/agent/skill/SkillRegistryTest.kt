package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.ToolContext
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SkillRegistryTest {

    private class FixedSkill(
        override val name: String,
        override val description: String,
        private val content: String,
    ) : Skill {
        override fun load(context: SkillContext): String = content
    }

    private class ContextualSkill(
        override val name: String,
        override val description: String,
        private val baseContent: String,
    ) : Skill {
        override fun load(context: SkillContext): String =
            "$baseContent [toolCallId=${context.toolContext.toolCallId}]"
    }

    private fun emptyContext(): SkillContext = SkillContext(
        arguments = buildJsonObject { },
        toolContext = ToolContext(toolCallId = "test-call-id"),
    )

    @Test
    fun `register adds skill to registry`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "天气查询", "body"))
        assertEquals("body", registry.load("weather", emptyContext()))
    }

    @Test
    fun `register multiple skills`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("a", "d1", "b1"))
        registry.register(FixedSkill("b", "d2", "b2"))
        assertEquals("b1", registry.load("a", emptyContext()))
        assertEquals("b2", registry.load("b", emptyContext()))
    }

    @Test
    fun `register batch of skills`() {
        val registry = SkillRegistry()
        registry.register(listOf(
            FixedSkill("x", "d1", "b1"),
            FixedSkill("y", "d2", "b2"),
        ))
        assertEquals("b1", registry.load("x", emptyContext()))
        assertEquals("b2", registry.load("y", emptyContext()))
    }

    @Test
    fun `duplicate skill name throws`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("dup", "d", "b"))
        assertFailsWith<IllegalArgumentException> {
            registry.register(FixedSkill("dup", "d", "b2"))
        }
    }

    @Test
    fun `load with context returns skill content`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "d", "## weather body"))
        assertEquals("## weather body", registry.load("weather", emptyContext()))
    }

    @Test
    fun `load returns null for unknown skill`() {
        val registry = SkillRegistry()
        assertNull(registry.load("unknown", emptyContext()))
    }

    @Test
    fun `buildDescription formats skills`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "天气查询助手", "body1"))
        registry.register(FixedSkill("news", "新闻查询助手", "body2"))

        val prompt = registry.buildDescription()
        assertTrue("- weather: 天气查询助手" in prompt)
        assertTrue("- news: 新闻查询助手" in prompt)
    }

    @Test
    fun `load with context passes context to skill`() {
        val registry = SkillRegistry()
        registry.register(ContextualSkill("weather", "d", "base"))
        val ctx = SkillContext(
            arguments = buildJsonObject { },
            toolContext = ToolContext(toolCallId = "test-id"),
        )
        val result = registry.load("weather", ctx)
        assertEquals("base [toolCallId=test-id]", result)
    }

    @Test
    fun `load falls back to load without context`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "d", "body"))
        assertEquals("body", registry.load("weather", emptyContext()))
    }
}