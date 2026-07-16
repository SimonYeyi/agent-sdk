package io.github.yeyi.agent.skill

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillTest {

    private class FixedSkill(
        override val name: String,
        override val description: String,
        private val content: String,
    ) : Skill {
        override fun load(): String = content
    }

    private fun emptyContext(): SkillContext = SkillContext()

    @Test
    fun `Skill interface exposes name, description and load() returns content`() {
        val s = FixedSkill(name = "x", description = "d", content = "instructions-text")
        assertEquals("x", s.name)
        assertEquals("d", s.description)
        assertEquals("instructions-text", s.load())
    }

    @Test
    fun `Skill load is idempotent and side effect free`() {
        var callCount = 0
        val s = object : Skill {
            override val name = "n"
            override val description = "d"
            override fun load(): String {
                callCount++
                return "v$callCount"
            }
        }
        assertEquals("v1", s.load())
        assertEquals("v2", s.load())
    }

    @Test
    fun `Skill activate delegates to load`() = runTest {
        val s = FixedSkill(name = "x", description = "d", content = "hello")
        val result = s.activate(null, emptyContext())
        assertEquals("hello", result)
    }

    @Test
    fun `Skill has CAPABILITY_NAME constant`() {
        assertEquals("skill", Skill.CAPABILITY_NAME)
    }
}
