package io.github.yeyi.agent.mcp

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SseEventTest {
    @Test
    fun `parses single data line`() {
        val raw = "data: hello world"
        val event = SseEventParser.parse(raw)
        assertEquals("hello world", event?.data)
        assertNull(event?.id)
        assertNull(event?.event)
    }

    @Test
    fun `joins multi-line data with newline`() {
        val raw = "data: line1\ndata: line2\ndata: line3"
        val event = SseEventParser.parse(raw)
        assertEquals("line1\nline2\nline3", event?.data)
    }

    @Test
    fun `parses id, event, retry fields`() {
        val raw = """
            id: 42
            event: message
            retry: 5000
            data: payload
        """.trimIndent()
        val event = SseEventParser.parse(raw)
        assertEquals("42", event?.id)
        assertEquals("message", event?.event)
        assertEquals(5000L, event?.retry)
        assertEquals("payload", event?.data)
    }

    @Test
    fun `skips comment lines`() {
        val raw = ": this is a comment\ndata: actual data"
        val event = SseEventParser.parse(raw)
        assertEquals("actual data", event?.data)
    }

    @Test
    fun `handles multiple events`() {
        // Each call parses a single event; caller must split on blank lines
        val raw1 = "data: first"
        val raw2 = "data: second"
        val event1 = SseEventParser.parse(raw1)
        val event2 = SseEventParser.parse(raw2)
        assertEquals("first", event1?.data)
        assertEquals("second", event2?.data)
    }

    @Test
    fun `handles missing trailing blank line`() {
        val raw = "data: only event"
        val event = SseEventParser.parse(raw)
        assertEquals("only event", event?.data)
    }

    @Test
    fun `strips leading space from value`() {
        val raw = "data:    hello"
        val event = SseEventParser.parse(raw)
        assertEquals("hello", event?.data)
    }

    @Test
    fun `handles CRLF line endings`() {
        val raw = "data: hello\r\ndata: world"
        val event = SseEventParser.parse(raw)
        assertEquals("hello\r\nworld", event?.data)
    }

    @Test
    fun `returns null for blank input`() {
        val event = SseEventParser.parse("")
        assertNull(event)
    }

    @Test
    fun `returns null for whitespace-only input`() {
        val event = SseEventParser.parse("   ")
        assertNull(event)
    }
}
