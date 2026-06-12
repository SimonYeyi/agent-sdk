package io.github.yeyi.agent.skill

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
        override fun load(): String = content
    }

    @Test
    fun `register adds skill to registry`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "天气查询", "body"))
        assertEquals("body", registry.load("weather"))
    }

    @Test
    fun `register multiple skills`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("a", "d1", "b1"))
        registry.register(FixedSkill("b", "d2", "b2"))
        assertEquals("b1", registry.load("a"))
        assertEquals("b2", registry.load("b"))
    }

    @Test
    fun `register batch of skills`() {
        val registry = SkillRegistry()
        registry.register(listOf(
            FixedSkill("x", "d1", "b1"),
            FixedSkill("y", "d2", "b2"),
        ))
        assertEquals("b1", registry.load("x"))
        assertEquals("b2", registry.load("y"))
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
    fun `load returns skill content`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "d", "## weather body"))
        assertEquals("## weather body", registry.load("weather"))
    }

    @Test
    fun `load returns null for unknown skill`() {
        val registry = SkillRegistry()
        assertNull(registry.load("unknown"))
    }

    @Test
    fun `buildIndexPrompt formats skills`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "天气查询助手", "body1"))
        registry.register(FixedSkill("news", "新闻查询助手", "body2"))
        val prompt = registry.buildIndexPrompt()
        assertTrue("    - weather: 天气查询助手" in prompt)
        assertTrue("    - news: 新闻查询助手" in prompt)
    }
}