package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeEvent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolcStreamDecoderTest {
    private val json = Json { ignoreUnknownKeys = true }

    private fun decode(frame: String): List<RealtimeEvent> =
        VolcStreamDecoder.toRealtimeEvents(json.decodeFromString(VolcEvent.serializer(), frame))

    @Test
    fun `transcription completed maps to UserTranscriptCompleted`() {
        val json = """
            {"type":"conversation.item.input_audio_transcription.completed","text":"你好"}
        """.trimIndent()
        val events = decode(json)
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.UserTranscriptCompleted("你好"), events[0])
    }

    @Test
    fun `output_text delta maps to AssistantTextDelta`() {
        val events = decode("""{"type":"response.output_text.delta","delta":"<|TASK|>"}""")
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.AssistantTextDelta("<|TASK|>"), events[0])
    }

    @Test
    fun `output_audio delta maps to AssistantAudioDelta with base64 decoded PCM`() {
        val json = """
            {"type":"response.output_audio.delta","item_id":"i1","delta":"AQI="}
        """.trimIndent()
        val events = decode(json)
        assertEquals(1, events.size)
        val e = events[0] as RealtimeEvent.AssistantAudioDelta
        assertEquals(byteArrayOf(1, 2).toList(), e.pcm.toList())
    }

    @Test
    fun `response done canceled maps to ResponseDone CANCELED`() {
        val json = """
            {"type":"response.done","response_id":"r1","status":"canceled"}
        """.trimIndent()
        val events = decode(json)
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.ResponseDone, events[0])
    }

    @Test
    fun `unknown event type yields empty list`() {
        val events = decode("""{"type":"some.future.event","foo":"bar"}""")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `session created maps to Connected with session id`() {
        val events = decode("""{"type":"session.created","session":{"id":"s1"}}""")
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.Connected("s1"), events[0])
    }

    @Test
    fun `session updated maps to Connected with session id`() {
        val events = decode("""{"type":"session.updated","session":{"id":"s2"}}""")
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.Connected("s2"), events[0])
    }

    @Test
    fun `session closed maps to Disconnected with session closed reason`() {
        val events = decode("""{"type":"session.closed"}""")
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.Disconnected("session closed"), events[0])
    }

    @Test
    fun `input_audio_buffer committed is dropped`() {
        val events = decode("""{"type":"input_audio_buffer.committed"}""")
        assertTrue(events.isEmpty())
    }

    @Test
    fun `transcription started maps to UserTranscriptStarted`() {
        val events = decode(
            """{"type":"conversation.item.input_audio_transcription.started","item_id":"asr1"}""",
        )
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.UserTranscriptStarted("asr1"), events[0])
    }

    @Test
    fun `transcription delta maps to UserTranscriptDelta`() {
        val events = decode(
            """{"type":"conversation.item.input_audio_transcription.delta","item_id":"asr1","delta":"你"}""",
        )
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.UserTranscriptDelta("你"), events[0])
    }

    @Test
    fun `transcription failed maps to Error transcription_failed`() {
        val events = decode(
            """{"type":"conversation.item.input_audio_transcription.failed","error":{"message":"bad audio"}}""",
        )
        assertEquals(1, events.size)
        val e = events[0] as RealtimeEvent.Error
        assertEquals("transcription_failed", e.code)
        assertEquals("bad audio", e.message)
        assertEquals(false, e.isFatal)
    }

    @Test
    fun `response canceled maps to ResponseDone with empty id and CANCELED`() {
        val events = decode("""{"type":"response.canceled"}""")
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.ResponseCanceled, events[0])
    }

    @Test
    fun `function_call_arguments done is dropped (marker protocol used)`() {
        val events = decode(
            """{"type":"response.function_call_arguments.done","function_call":{"call_id":"c1","name":"x","arguments":"{}"}}""",
        )
        assertTrue(events.isEmpty())
    }

    @Test
    fun `conversation item added is dropped`() {
        val events = decode("""{"type":"conversation.item.added","items":[]}""")
        assertTrue(events.isEmpty())
    }
}
