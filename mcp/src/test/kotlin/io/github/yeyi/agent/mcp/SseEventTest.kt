package io.github.yeyi.agent.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseEventTest {
    @Test
    fun `parses single data line`() {
        val body = "data: hello world\n\n"
        val events = SseEvent.parseAll(body)
        assertEquals(1, events.size)
        assertEquals("hello world", events[0].data)
        assertNull(events[0].id)
        assertNull(events[0].event)
    }

    @Test
    fun `joins multi-line data with newline`() {
        val body = "data: line1\ndata: line2\ndata: line3\n\n"
        val events = SseEvent.parseAll(body)
        assertEquals(1, events.size)
        assertEquals("line1\nline2\nline3", events[0].data)
    }

    @Test
    fun `parses id, event, retry fields`() {
        val body = """
            id: 42
            event: message
            retry: 5000
            data: payload

        """.trimIndent() + "\n"
        val events = SseEvent.parseAll(body)
        assertEquals(1, events.size)
        assertEquals("42", events[0].id)
        assertEquals("message", events[0].event)
        assertEquals(5000L, events[0].retry)
        assertEquals("payload", events[0].data)
    }

    @Test
    fun `skips comment lines`() {
        val body = ": this is a comment\ndata: actual data\n\n"
        val events = SseEvent.parseAll(body)
        assertEquals(1, events.size)
        assertEquals("actual data", events[0].data)
    }

    @Test
    fun `handles multiple events`() {
        val body = "data: first\n\ndata: second\n\n"
        val events = SseEvent.parseAll(body)
        assertEquals(2, events.size)
        assertEquals("first", events[0].data)
        assertEquals("second", events[1].data)
    }

    @Test
    fun `handles missing trailing blank line`() {
        val body = "data: only event"
        val events = SseEvent.parseAll(body)
        assertEquals(1, events.size)
        assertEquals("only event", events[0].data)
    }

    @Test
    fun `strips leading space from value`() {
        val body = "data:    hello\n\n"
        val events = SseEvent.parseAll(body)
        assertEquals(1, events.size)
        assertEquals("hello", events[0].data)
    }

    @Test
    fun `normalizes CRLF line endings`() {
        val body = "data: hello\r\n\r\n"
        val events = SseEvent.parseAll(body)
        assertEquals(1, events.size)
        assertEquals("hello", events[0].data)
    }
}
