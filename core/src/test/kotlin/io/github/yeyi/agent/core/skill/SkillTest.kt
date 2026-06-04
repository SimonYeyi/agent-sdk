package io.github.yeyi.agent.core.skill

import io.github.yeyi.agent.core.agent.fakes.EchoTool
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SkillTest {
    @Test
    fun `Skill data class holds all fields`() {
        val s = Skill(
            name = "x",
            description = "d",
            systemPromptFragment = "p",
            tools = listOf(EchoTool())
        )
        assertEquals("x", s.name)
        assertEquals("d", s.description)
        assertEquals("p", s.systemPromptFragment)
        assertEquals(1, s.tools.size)
    }

    @Test
    fun `Skill defaults are empty`() {
        val s = Skill(name = "n", description = "d")
        assertTrue(s.systemPromptFragment.isEmpty())
        assertTrue(s.tools.isEmpty())
    }

    @Test
    fun `skill DSL constructs Skill`() {
        val s = skill("weather") {
            description = "weather assistant"
            systemPromptFragment = "you check weather"
            tool(EchoTool(name = "get_weather"))
        }
        assertEquals("weather", s.name)
        assertEquals("weather assistant", s.description)
        assertEquals("you check weather", s.systemPromptFragment)
        assertEquals(1, s.tools.size)
        assertEquals("get_weather", s.tools[0].name)
    }
}
