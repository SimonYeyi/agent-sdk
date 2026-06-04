package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.core.llm.StreamEvent
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
        val toolDeltas = events.filterIsInstance<StreamEvent.ToolCallDelta>()
        assertEquals(2, toolDeltas.size)
        assertEquals("c1", toolDeltas[0].id)
        assertEquals("echo", toolDeltas[0].name)
        assertEquals("{\"", toolDeltas[0].argumentsDelta)
        assertEquals("text\":\"x\"}", toolDeltas[1].argumentsDelta)
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
