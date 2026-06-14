package io.github.yeyi.agent.tool

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
    fun `ToolContext requires toolCallId and defaults metadata to empty`() {
        val context = ToolContext(toolCallId = "call-123")
        assertEquals("call-123", context.toolCallId)
        assertTrue(context.metadata.isEmpty())
    }
}
