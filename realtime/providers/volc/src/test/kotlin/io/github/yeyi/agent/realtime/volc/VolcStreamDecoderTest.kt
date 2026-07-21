package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.ResponseStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolcStreamDecoderTest {

    @Test
    fun `transcription completed maps to UserTranscriptCompleted`() {
        val json = """
            {"type":"conversation.item.input_audio_transcription.completed","transcript":"你好"}
        """.trimIndent()
        val events = VolcStreamDecoder.decode(json)
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.UserTranscriptCompleted("你好"), events[0])
    }

    @Test
    fun `output_text delta maps to AssistantTextDelta`() {
        val json = """{"type":"response.output_text.delta","delta":"<|DELEGATE_TO_BOSS|>"}"""
        val events = VolcStreamDecoder.decode(json)
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.AssistantTextDelta("<|DELEGATE_TO_BOSS|>"), events[0])
    }

    @Test
    fun `output_audio delta maps to AssistantAudioDelta with base64 decoded PCM`() {
        val json = """
            {"type":"response.output_audio.delta","item_id":"i1","delta":"AQI="}
        """.trimIndent()
        val events = VolcStreamDecoder.decode(json)
        assertEquals(1, events.size)
        val e = events[0] as RealtimeEvent.AssistantAudioDelta
        assertEquals("i1", e.itemId)
        assertEquals(byteArrayOf(1, 2).toList(), e.pcm.toList())
    }

    @Test
    fun `response done canceled maps to ResponseDone CANCELED`() {
        val json = """
            {"type":"response.done","response_id":"r1","status":"canceled"}
        """.trimIndent()
        val events = VolcStreamDecoder.decode(json)
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.ResponseDone("r1", ResponseStatus.CANCELED), events[0])
    }

    @Test
    fun `unknown event type yields empty list`() {
        val events = VolcStreamDecoder.decode("""{"type":"conversation.item.added","item":{"id":"x"}}""")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `session created maps to Connected with session id`() {
        val events = VolcStreamDecoder.decode("""{"type":"session.created","session":{"id":"s1"}}""")
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.Connected("s1"), events[0])
    }
}