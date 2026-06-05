package io.github.yeyi.agent.skill

import io.github.yeyi.agent.fakes.EchoTool
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

    @Test
    fun `skill DSL preserves order of multiple tools`() {
        val s = skill("multi") {
            description = "d"
            tool(EchoTool(name = "first"))
            tool(EchoTool(name = "second"))
            tool(EchoTool(name = "third"))
        }
        assertEquals(3, s.tools.size)
        assertEquals(listOf("first", "second", "third"), s.tools.map { it.name })
    }
}
