package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonNull
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkillToolTest {

    @Test
    fun `SkillTool name is prefixed with skill_`() {
        val s = Skill(name = "weather", description = "weather", body = "BODY")
        assertEquals("skill_weather", SkillTool(s).name)
    }

    @Test
    fun `SkillTool description matches skill description`() {
        val s = Skill(name = "x", description = "does X", body = "")
        assertEquals("does X", SkillTool(s).description)
    }

    @Test
    fun `SkillTool parameters schema is Empty`() {
        val s = Skill(name = "x", description = "", body = "")
        assertEquals(ToolParameters.Empty, SkillTool(s).parametersSchema)
    }

    @Test
    fun `SkillTool execute returns skill body as content and isError false`() = runTest {
        val s = Skill(name = "x", description = "d", body = "## My skill body\nStep 1...")
        val result = SkillTool(s).execute(JsonNull, ToolContext())
        assertEquals("## My skill body\nStep 1...", result.content)
        assertFalse(result.isError)
    }

    @Test
    fun `SkillTool execute is independent of args`() = runTest {
        val s = Skill(name = "x", description = "d", body = "body")
        val r1 = SkillTool(s).execute(JsonNull, ToolContext())
        val r2 = SkillTool(s).execute(
            kotlinx.serialization.json.JsonPrimitive("ignored"),
            ToolContext(),
        )
        assertEquals(r1.content, r2.content)
    }

    @Test
    fun `SkillTool can be invoked multiple times returning same body`() = runTest {
        val s = Skill(name = "x", description = "d", body = "payload")
        val tool = SkillTool(s)
        assertEquals("payload", tool.execute(JsonNull, ToolContext()).content)
        assertEquals("payload", tool.execute(JsonNull, ToolContext()).content)
    }
}

private fun assertSame(expected: Any?, actual: Any?) {
    assertTrue(expected === actual, "expected same reference, got $expected vs $actual")
}
