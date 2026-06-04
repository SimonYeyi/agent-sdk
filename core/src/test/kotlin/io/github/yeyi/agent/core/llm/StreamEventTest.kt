package io.github.yeyi.agent.core.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class StreamEventTest {
    @Test
    fun `ContentDelta holds text`() {
        val e = StreamEvent.ContentDelta("hi")
        assertEquals("hi", e.text)
    }

    @Test
    fun `ToolCallDelta allows nullable id and name`() {
        val e = StreamEvent.ToolCallDelta(id = null, name = null, argumentsDelta = "{")
        assertNull(e.id)
        assertNull(e.name)
        assertEquals("{", e.argumentsDelta)
    }

    @Test
    fun `Done holds optional usage`() {
        val e = StreamEvent.Done(null)
        assertNull(e.usage)
    }

    @Test
    fun `Error wraps a Throwable`() {
        val cause = RuntimeException("boom")
        val e = StreamEvent.Error(cause)
        assertEquals(cause, e.cause)
    }
}
