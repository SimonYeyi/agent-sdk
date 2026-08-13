package io.github.yeyi.agent.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChatResponseEventTest {
    @Test
    fun `ContentDelta holds text`() {
        val e = ChatResponseEvent.ContentDelta("hi")
        assertEquals("hi", e.text)
    }

    @Test
    fun `ToolCallDelta allows nullable id and name`() {
        val e = ChatResponseEvent.ToolCallDelta(id = null, name = null, argumentsDelta = "{")
        assertNull(e.id)
        assertNull(e.name)
        assertEquals("{", e.argumentsDelta)
    }

    @Test
    fun `Done allows nullable usage and requires non-null finishReason`() {
        val e = ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
        assertNull(e.usage)
    }

    @Test
    fun `Error wraps a Throwable`() {
        val cause = RuntimeException("boom")
        val e = ChatResponseEvent.Error(cause)
        assertEquals(cause, e.cause)
    }
}
