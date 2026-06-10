package io.github.yeyi.agent.skill

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class SkillTest {

    private class FakeTool(override val name: String) : Tool {
        override val description: String = ""
        override val parametersSchema = ToolParameters.Empty
        override suspend fun execute(args: JsonElement, ctx: ToolContext): ToolExecutionResult =
            ToolExecutionResult(content = "ok", isError = false)
    }

    @Test
    fun `Skill holds all four fields`() {
        val t = FakeTool("t")
        val s = Skill(name = "x", description = "d", body = "b", tools = listOf(t))
        assertEquals("x", s.name)
        assertEquals("d", s.description)
        assertEquals("b", s.body)
        assertEquals(1, s.tools.size)
        assertSame(t, s.tools[0])
    }

    @Test
    fun `Skill defaults tools to empty list`() {
        val s = Skill(name = "n", description = "d", body = "b")
        assertTrue(s.tools.isEmpty())
    }
}
