package io.github.yeyi.agent.core.tool

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ToolParametersTest {
    @Test
    fun `Empty is a singleton object`() {
        val a: ToolParameters = ToolParameters.Empty
        val b: ToolParameters = ToolParameters.Empty
        assertTrue(a === b)
    }

    @Test
    fun `JsonSchema holds schema string`() {
        val s = ToolParameters.JsonSchema("{\"type\":\"object\"}")
        assertEquals("{\"type\":\"object\"}", s.schema)
    }

    @Test
    fun `ToolExecutionResult defaults isError false`() {
        val r = ToolExecutionResult("done")
        assertEquals("done", r.content)
        assertEquals(false, r.isError)
    }

    @Test
    fun `ToolContext generates id and empty metadata by default`() {
        val ctx = ToolContext()
        assertTrue(ctx.invocationId.isNotEmpty())
        assertTrue(ctx.metadata.isEmpty())
    }
}
