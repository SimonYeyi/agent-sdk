package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ChatResponseEvent
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith

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
        val deltas = events.filterIsInstance<ChatResponseEvent.ContentDelta>().map { it.text }
        assertEquals(listOf("hel", "lo"), deltas)
        assertTrue(events.any { it is ChatResponseEvent.Done })
    }

    @Test
    fun `decode SSE tool call delta`() = runTest {
        val sseLines = listOf(
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"echo","arguments":"{\""}}]}}]}""",
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"text\":\"x\"}"}}]}}]}""",
            "data: [DONE]"
        )
        val events = decodeOpenAiSseLines(flowOf(*sseLines.toTypedArray())).toList()
        val deltas = events.filterIsInstance<ChatResponseEvent.ToolCallDelta>()
        // 首帧 delta 携带 name 标记开始;续帧 name = null(id 由 decoder 按 index 回填)
        assertEquals(2, deltas.size)
        assertEquals("c1", deltas[0].id)
        assertEquals("echo", deltas[0].name)
        assertEquals("{\"", deltas[0].argumentsDelta)
        assertEquals("c1", deltas[1].id)
        assertEquals(null, deltas[1].name)
        assertEquals("text\":\"x\"}", deltas[1].argumentsDelta)
    }

    @Test
    fun `first tool_call delta carries name marking the start`() = runTest {
        val lines = flowOf(
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"call_1","function":{"name":"get_time","arguments":""}}]},"finish_reason":null}]}""",
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"{}"}}]},"finish_reason":null}]}""",
            """data: [DONE]"""
        )
        val events = decodeOpenAiSseLines(lines).toList()
        assertEquals(
            listOf(
                ChatResponseEvent.ToolCallDelta(id = "call_1", name = "get_time", argumentsDelta = ""),
                ChatResponseEvent.ToolCallDelta(id = "call_1", name = null, argumentsDelta = "{}"),
                ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
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
        val done = events.last() as ChatResponseEvent.Done
        assertEquals(FinishReason.Stop, done.finishReason)
    }

    @Test
    fun `multiple distinct tool calls each start with a delta carrying its own name`() = runTest {
        val lines = flowOf(
            // First chunk: c1 starts with name, c2 starts with name
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"calc","arguments":""}},{"index":1,"id":"c2","function":{"name":"time","arguments":""}}]}}]}""",
            // Continuation chunks for both
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"1"}},{"index":1,"function":{"arguments":"now"}}]}}]}""",
            """data: [DONE]"""
        )
        val events = decodeOpenAiSseLines(lines).toList()
        val deltas = events.filterIsInstance<ChatResponseEvent.ToolCallDelta>()
        // 4 个 delta = 2 个首帧(带 name) + 2 个续帧(name=null)
        assertEquals(4, deltas.size)
        // 首帧各自携带 name,标识两个 tool call 开始
        assertEquals(setOf("calc", "time"), deltas.filter { it.name != null }.map { it.name }.toSet())
        // 续帧的 name=null;id 按 index 回填(toolCallIdByIndex[1] = "c2")
        val continuationDeltas = deltas.filter { it.name == null }
        assertEquals(2, continuationDeltas.size)
    }

    @Test
    fun `continuation delta backfills id by tool-call index`() = runTest {
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
        val deltas = events.filterIsInstance<ChatResponseEvent.ToolCallDelta>()
        assertEquals(3, deltas.size)
        // First two carry their own id (start chunks)
        assertEquals("c1", deltas[0].id)
        assertEquals("c2", deltas[1].id)
        // Continuation delta backfills by index (toolCallIdByIndex[1] = c2)
        assertEquals("c2", deltas[2].id)
        assertEquals("]", deltas[2].argumentsDelta)
    }

    @Test
    fun `continuation delta with no id backfills its own index even after another tool call`() = runTest {
        // 网关只在每个 tool call 的首帧带 id,续帧只带 index + arguments:
        // c1 的续帧出现在 c2 之后,必须回填 c1 而非最近出现的 c2
        val lines = flowOf(
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f1","arguments":"{"}}]}}]}""",
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":1,"id":"c2","function":{"name":"f2","arguments":"["}}]}}]}""",
            // c1 的续帧(无 id,排在 c2 之后)
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"arguments":"x"}}]}}]}""",
            "data: [DONE]"
        )
        val events = decodeOpenAiSseLines(lines).toList()
        val deltas = events.filterIsInstance<ChatResponseEvent.ToolCallDelta>()
        assertEquals(3, deltas.size)
        assertEquals("c1", deltas[0].id)
        assertEquals("c2", deltas[1].id)
        assertEquals("c1", deltas[2].id)
        assertEquals(null, deltas[2].name)
        assertEquals("x", deltas[2].argumentsDelta)
    }

    @Test
    fun `repeated id in later chunks is ignored, first id wins`() = runTest {
        // 异常网关在同一 index 的续帧里重复/变更 id:delta 必须继续挂首次 id,而非本帧 id
        val lines = flowOf(
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c1","function":{"name":"f1","arguments":""}}]}}]}""",
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"id":"c2","function":{"arguments":"{\"x\":1}"}}]}}]}""",
            "data: [DONE]"
        )
        val events = decodeOpenAiSseLines(lines).toList()
        val deltas = events.filterIsInstance<ChatResponseEvent.ToolCallDelta>()
        // 首帧 delta 挂首次 id;续帧的重复 id 不作数,delta 仍挂首次 id
        assertEquals(2, deltas.size)
        assertEquals("c1", deltas[0].id)
        assertEquals("c1", deltas[1].id)
        assertEquals("{\"x\":1}", deltas[1].argumentsDelta)
    }

    @Test
    fun `tool_call first chunk without id fails fast`() = runTest {
        // 协议保证每个 tool call 首帧必带 id;缺失即违约,decoder 用 !! 直接失败而非匿名 delta
        val lines = flowOf(
            """data: {"choices":[{"index":0,"delta":{"tool_calls":[{"index":0,"function":{"name":"f1","arguments":"{"}}]}}]}""",
            "data: [DONE]"
        )
        assertFailsWith<NullPointerException> {
            decodeOpenAiSseLines(lines).toList()
        }
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
        val deltas = events.filterIsInstance<ChatResponseEvent.ContentDelta>()
        assertEquals(1, deltas.size)
    }
}
