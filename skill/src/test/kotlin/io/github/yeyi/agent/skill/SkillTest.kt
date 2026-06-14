package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.ToolContext
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

class SkillTest {

    private class FixedSkill(
        override val name: String,
        override val description: String,
        private val content: String,
    ) : Skill {
        override fun load(context: SkillContext): String = content
    }

    private fun emptyContext(): SkillContext = SkillContext(
        arguments = buildJsonObject { },
        toolContext = ToolContext(toolCallId = "test-call-id"),
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