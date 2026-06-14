package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SkillToolTest {

    private class FixedSkill(
        override val name: String,
        override val description: String,
        private val content: String,
    ) : Skill {
        override fun load(context: SkillContext): String = content
    }

    @Test
    fun `SkillTool name is prefixed with skill_`() {
        val s = FixedSkill(name = "weather", description = "weather", content = "BODY")
        assertEquals("skill_weather", SkillTool(s).name)
    }

    @Test
    fun `SkillTool description matches skill description`() {
        val s = FixedSkill(name = "x", description = "does X", content = "")
        assertEquals("does X", SkillTool(s).description)
    }

    @Test
    fun `SkillTool parameters schema is Empty`() {
        val s = FixedSkill(name = "x", description = "", content = "")
        assertEquals(ToolParameters.Empty, SkillTool(s).parametersSchema)
    }

    @Test
    fun `SkillTool execute returns skill load() as content and isError false`() = runTest {
        val s = FixedSkill(name = "x", description = "d", content = "## My skill body\nStep 1...")
        val result = SkillTool(s).execute(JsonNull, ToolContext(toolCallId = "test-call-id"))
        assertEquals("## My skill body\nStep 1...", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun `SkillTool execute calls load() on each invocation`() = runTest {
        var calls = 0
        val s = object : Skill {
            override val name = "x"
            override val description = "d"
            override fun load(context: SkillContext): String {
                calls++
                return "v$calls"
            }
        }
        val tool = SkillTool(s)
        assertEquals("v1", tool.execute(JsonNull, ToolContext(toolCallId = "test-call-id")).content)
        assertEquals("v2", tool.execute(JsonNull, ToolContext(toolCallId = "test-call-id")).content)
    }

    @Test
    fun `SkillTool execute is independent of args`() = runTest {
        val s = FixedSkill(name = "x", description = "d", content = "body")
        val r1 = SkillTool(s).execute(JsonNull, ToolContext(toolCallId = "test-call-id"))
        val r2 = SkillTool(s).execute(
            kotlinx.serialization.json.JsonPrimitive("ignored"),
            ToolContext(toolCallId = "test-call-id"),
        )
        assertEquals(r1.content, r2.content)
    }
}
