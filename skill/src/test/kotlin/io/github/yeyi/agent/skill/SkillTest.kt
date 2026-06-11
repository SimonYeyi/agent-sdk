package io.github.yeyi.agent.skill

import kotlin.test.Test
import kotlin.test.assertEquals

class SkillTest {

    @Test
    fun `Skill holds all three fields`() {
        val s = Skill(name = "x", description = "d", instructions = "i")
        assertEquals("x", s.name)
        assertEquals("d", s.description)
        assertEquals("i", s.instructions)
    }
}
