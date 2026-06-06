package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.StreamEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class OpenAiStreamDecoderTest {

    @Test
    fun `decode SSE lines into ContentDelta sequence`() = runTest {
        val sseLines = listOf(
            """data: {"choices":[{"index":0,"delta":{"content":"hel"}}]}""",
            """data: {"choices":[{"index":0,"delta":{"content":"lo"}}]}""",
            """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            "data: [DONE]"
        )
        val events = decodeOpenAiSseLines(flowOf(*sseLines.toTypedArray())).toList()
        val deltas = events.filterIsInstance<StreamEvent.ContentDelta>().map { it.text }
        assertEquals(listOf("hel", "lo"), deltas)
        assertTrue(events.any { it is StreamEvent.Done })
    }

    @Test
    fun `decode SSE tool call delta`() = runTest {
        val sseLines = listOf(
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"echo","arguments":"{\""}}]}}]}""",
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"text\":\"x\"}"}}]}}]}""",
            "data: [DONE]"
        )
        val events = decodeOpenAiSseLines(flowOf(*sseLines.toTypedArray())).toList()
        val starts = events.filterIsInstance<StreamEvent.ToolCallStart>()
        val deltas = events.filterIsInstance<StreamEvent.ToolCallDelta>()
        // 第一个带 id 的 chunk 先发 ToolCallStart,再发 ToolCallDelta(spec §4.2)
        assertEquals(1, starts.size)
        assertEquals("c1", starts[0].id)
        assertEquals("echo", starts[0].name)
        // 两条 tool_call 都有 ToolCallDelta(continuation delta 的 id 由 decoder 用 seenToolCallIds.lastOrNull() 兜底)
        assertEquals(2, deltas.size)
        assertEquals("c1", deltas[0].id)
        assertEquals("echo", deltas[0].name)
        assertEquals("{\"", deltas[0].argumentsDelta)
        assertEquals("c1", deltas[1].id)
        assertEquals("text\":\"x\"}", deltas[1].argumentsDelta)
    }

    @Test
    fun `first tool_call chunk emits both ToolCallStart and ToolCallDelta with name`() = runTest {
        val lines = flowOf(
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_time","arguments":""}}]},"finish_reason":null}]}""",
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]},"finish_reason":null}]}""",
            """data: [DONE]"""
        )
        val events = decodeOpenAiSseLines(lines).toList()
        assertEquals(
            listOf(
                StreamEvent.ToolCallStart(id = "call_1", name = "get_time"),
                StreamEvent.ToolCallDelta(id = "call_1", name = "get_time", argumentsDelta = ""),
                StreamEvent.ToolCallDelta(id = "call_1", name = null, argumentsDelta = "{}"),
                StreamEvent.Done(usage = null, finishReason = null)
            ),
            events
        )
    }

    @Test
    fun `Done carries finishReason captured from last chunk`() = runTest {
        val lines = flowOf(
            """data: {"choices":[{"index":0,"delta":{"content":"hi"},"finish_reason":null}]}""",
            """data: {"choices":[{"index":0,"delta":{},"finish_reason":"stop"}]}""",
            """data: [DONE]"""
        )
        val events = decodeOpenAiSseLines(lines).toList()
        val done = events.last() as StreamEvent.Done
        assertEquals(FinishReason.Stop, done.finishReason)
    }

    @Test
    fun `multiple distinct tool calls each get exactly one ToolCallStart`() = runTest {
        val lines = flowOf(
            // First chunk: c1 starts with name, c2 starts with name
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calc","arguments":""}},{"index":1,"id":"c2","function":{"name":"time","arguments":""}}]}}]}""",
            // Continuation chunks for both
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1"}},{"index":1,"function":{"arguments":"now"}}]}}]}""",
            """data: [DONE]"""
        )
        val events = decodeOpenAiSseLines(lines).toList()
        val starts = events.filterIsInstance<StreamEvent.ToolCallStart>()
        val deltas = events.filterIsInstance<StreamEvent.ToolCallDelta>()
        assertEquals(2, starts.size)
        assertEquals(setOf("c1", "c2"), starts.map { it.id }.toSet())
        assertEquals(setOf("calc", "time"), starts.map { it.name }.toSet())
        // 2 first-chunk deltas + 2 continuation deltas = 4 total
        assertEquals(4, deltas.size)
        // Continuation deltas have name=null; id backfills to seenToolCallIds.lastOrNull() ("c2")
        val continuationDeltas = deltas.filter { it.name == null }
        assertEquals(2, continuationDeltas.size)
    }

    @Test
    fun `continuation delta backfills id from most recently seen tool call`() = runTest {
        val lines = flowOf(
            // c1 starts first chunk
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f1","arguments":"{"}}]}}]}""",
            // Then c2 starts (mixed chunk)
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"f2","arguments":"["}}]}}]}""",
            // Continuation for c2
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"function":{"arguments":"]"}}]}}]}""",
            "data: [DONE]"
        )
        val events = decodeOpenAiSseLines(lines).toList()
        val deltas = events.filterIsInstance<StreamEvent.ToolCallDelta>()
        assertEquals(3, deltas.size)
        // First two carry their own id (start chunks)
        assertEquals("c1", deltas[0].id)
        assertEquals("c2", deltas[1].id)
        // Continuation delta backfills from last seen (c2)
        assertEquals("c2", deltas[2].id)
        assertEquals("]", deltas[2].argumentsDelta)
    }

    @Test
    fun `decode ignores empty data and comments`() = runTest {
        val sseLines = listOf(
            ":heartbeat",
            "",
            "data: ",
            """data: {"choices":[{"index":0,"delta":{"content":"x"}}]}""",
            "data: [DONE]"
        )
        val events = decodeOpenAiSseLines(flowOf(*sseLines.toTypedArray())).toList()
        val deltas = events.filterIsInstance<StreamEvent.ContentDelta>()
        assertEquals(1, deltas.size)
    }
}
