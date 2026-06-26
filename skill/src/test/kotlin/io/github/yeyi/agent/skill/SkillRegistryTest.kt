package io.github.yeyi.agent.skill

import kotlinx.coroutines.test.runTest
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

    private fun emptyContext(): SkillContext = SkillContext()

    @Test
    fun `registry capabilityName is skill`() {
        val r = SkillRegistry()
        assertEquals(Skill.CAPABILITY_NAME, r.capabilityName)
        assertEquals("skill", r.capabilityName)
    }

    @Test
    fun `register adds skill to registry`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "天气查询", "body"))
        val names = registry.all().map { it.name }.toSet()
        assertTrue("weather" in names)
    }

    @Test
    fun `register multiple skills`() {
        val registry = SkillRegistry()
        registry.register(FixedSkill("a", "d1", "b1"))
        registry.register(FixedSkill("b", "d2", "b2"))
        val names = registry.all().map { it.name }.toSet()
        assertEquals(setOf("a", "b"), names)
    }

    @Test
    fun `register iterable of skills`() {
        val registry = SkillRegistry()
        registry.register(listOf(
            FixedSkill("x", "d1", "b1"),
            FixedSkill("y", "d2", "b2"),
        ))
        val names = registry.all().map { it.name }.toSet()
        assertEquals(setOf("x", "y"), names)
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
    fun `all returns all registered skills`() {
        val registry = SkillRegistry()
        val s1 = FixedSkill("weather", "d", "body1")
        val s2 = FixedSkill("news", "d", "body2")
        registry.register(s1)
        registry.register(s2)
        val all = registry.all()
        assertEquals(2, all.size)
        assertTrue(all.any { it.name == "weather" })
        assertTrue(all.any { it.name == "news" })
    }

    @Test
    fun `skill can be loaded via all and activate`() = runTest {
        val registry = SkillRegistry()
        registry.register(FixedSkill("weather", "d", "## weather body"))
        val skill = registry.all().find { it.name == "weather" }
        assertEquals("## weather body", skill?.activate(null, emptyContext()))
    }

    @Test
    fun `unknown skill returns null when searching via all`() {
        val registry = SkillRegistry()
        assertNull(registry.all().find { it.name == "unknown" })
    }
}
