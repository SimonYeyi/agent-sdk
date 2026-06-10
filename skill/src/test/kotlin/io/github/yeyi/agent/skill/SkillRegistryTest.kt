package io.github.yeyi.agent.skill

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SkillRegistryTest {

    private fun s(name: String, body: String = "b") = Skill(name = name, description = "d", body = body)

    @Test
    fun `empty registry has no skills`() {
        val r = SkillRegistry()
        assertTrue(r.names().isEmpty())
        assertTrue(r.all().isEmpty())
    }

    @Test
    fun `register adds skill accessible by name`() {
        val r = SkillRegistry()
        val skill = s("x")
        r.register(skill)
        assertSame(skill, r.get("x"))
    }

    @Test
    fun `register preserves insertion order`() {
        val r = SkillRegistry()
        r.register(s("a"))
        r.register(s("b"))
        r.register(s("c"))
        assertEquals(listOf("a", "b", "c"), r.names())
        assertEquals(listOf("a", "b", "c"), r.all().map { it.name })
    }

    @Test
    fun `registerAll adds each in iteration order`() {
        val r = SkillRegistry()
        r.registerAll(listOf(s("a"), s("b"), s("c")))
        assertEquals(listOf("a", "b", "c"), r.names())
    }

    @Test
    fun `duplicate name throws`() {
        val r = SkillRegistry()
        r.register(s("dup"))
        assertFailsWith<IllegalArgumentException> {
            r.register(s("dup"))
        }
    }

    @Test
    fun `get on missing name returns null`() {
        val r = SkillRegistry()
        assertNull(r.get("missing"))
    }
}
