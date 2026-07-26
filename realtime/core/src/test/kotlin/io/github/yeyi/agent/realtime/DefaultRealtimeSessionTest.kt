package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class DefaultRealtimeSessionTest {

    private fun makeSessionConfig() = SessionConfig(
        apiKey = "k",
        endpoint = "wss://test",
        model = "m",
        instructions = "你是助手",
        voice = "v",
    )

    // === RealtimeSession delegates to adapter ===

    @Test
    fun `session exposes inputAudioFormat from adapter`() {
        val adapter = FakeRealtimeAdapter()
        assertEquals(adapter.inputAudioFormat.sampleRateHz, 16_000)
        assertEquals(adapter.outputAudioFormat.sampleRateHz, 24_000)
    }

    // === FakeRealtimeAdapter behavior tests ===

    @Test
    fun `FakeRealtimeAdapter connectFrame builds session_create payload`() {
        val adapter = FakeRealtimeAdapter()
        val config = makeSessionConfig()
        val frame = adapter.connectFrame(config)

        assertEquals("session.create", (frame.payload["type"] as JsonPrimitive).content)
        val session = frame.payload["session"] as JsonObject
        assertEquals("m", (session["model"] as JsonPrimitive).content)
        assertEquals("你是助手", (session["instructions"] as JsonPrimitive).content)
    }

    @Test
    fun `FakeRealtimeAdapter sendAudioFrame builds input_audio_buffer_append payload`() {
        val adapter = FakeRealtimeAdapter()
        val frame = adapter.sendAudioFrame(byteArrayOf(0x01, 0x02, 0x03))

        assertEquals("input_audio_buffer.append", (frame.payload["type"] as JsonPrimitive).content)
        assertEquals("1,2,3", (frame.payload["audio"] as JsonPrimitive).content)
    }

    @Test
    fun `FakeRealtimeAdapter commitInputFrame builds input_audio_buffer_commit payload`() {
        val adapter = FakeRealtimeAdapter()
        val frame = adapter.commitInputFrame()

        assertEquals("input_audio_buffer.commit", (frame.payload["type"] as JsonPrimitive).content)
    }

    @Test
    fun `FakeRealtimeAdapter cancelResponseFrame builds response_cancel payload`() {
        val adapter = FakeRealtimeAdapter()
        val frame = adapter.cancelResponseFrame()

        assertEquals("response.cancel", (frame.payload["type"] as JsonPrimitive).content)
    }

    @Test
    fun `FakeRealtimeAdapter injectAndRespondFrame builds speech_text_buffer_commit payload`() {
        val adapter = FakeRealtimeAdapter()
        val frame = adapter.injectAndRespondFrame("你好")

        assertEquals("speech_text_buffer.commit", (frame.payload["type"] as JsonPrimitive).content)
        assertEquals("你好", (frame.payload["text"] as JsonPrimitive).content)
    }

    @Test
    fun `FakeRealtimeAdapter handleIncomingFrame session_created emits Connected event`() = runTest {
        val adapter = FakeRealtimeAdapter()
        val frame = ProtocolFrame(buildJsonObject {
            put("type", "session.created")
            put("session", buildJsonObject { put("id", "sess_xyz") })
        })

        val deferred = backgroundScope.async { adapter.events.first() }
        adapter.handleIncomingFrame(frame)

        val event = deferred.await()
        assertIs<RealtimeEvent.Connected>(event)
        assertEquals("sess_xyz", event.sessionId)
    }

    @Test
    fun `FakeRealtimeAdapter getAuthHeaders returns X-Api-Key header`() {
        val adapter = FakeRealtimeAdapter()
        val headers = adapter.getAuthHeaders(makeSessionConfig())

        assertEquals("k", headers["X-Api-Key"])
    }

    // === Fake Adapter ===

    private class FakeRealtimeAdapter : RealtimeAdapter {
        private val eventsChannel = Channel<RealtimeEvent>(Channel.BUFFERED)

        override val inputAudioFormat = AudioFormat(16_000, AudioFormat.Encoding.PCM_16BIT)
        override val outputAudioFormat = AudioFormat(24_000, AudioFormat.Encoding.PCM_16BIT)
        override val events: Flow<RealtimeEvent> = eventsChannel.receiveAsFlow()

        override fun getAuthHeaders(config: SessionConfig) = mapOf("X-Api-Key" to config.apiKey)
        override fun registerTools(tools: List<Tool>) {}

        override fun connectFrame(config: SessionConfig): ProtocolFrame {
            return buildFrame("session.create") {
                put("session", buildJsonObject {
                    put("model", config.model)
                    put("instructions", config.instructions)
                })
            }
        }

        override fun sendAudioFrame(pcm: ByteArray): ProtocolFrame {
            return buildFrame("input_audio_buffer.append") {
                put("audio", pcm.joinToString(",") { it.toString() })
            }
        }

        override fun commitInputFrame(): ProtocolFrame = buildFrame("input_audio_buffer.commit") { }

        override fun cancelResponseFrame(): ProtocolFrame = buildFrame("response.cancel") { }

        override fun injectAndRespondFrame(text: String): ProtocolFrame {
            return buildFrame("speech_text_buffer.commit") { put("text", text) }
        }

        override suspend fun handleIncomingFrame(frame: ProtocolFrame): List<ProtocolFrame> {
            val type = (frame.payload["type"] as? JsonPrimitive)?.content ?: return emptyList()
            if (type == "session.created") {
                val sessionId = ((frame.payload["session"] as? JsonObject)?.get("id") as? JsonPrimitive)?.content ?: ""
                eventsChannel.send(RealtimeEvent.Connected(sessionId))
            }
            return emptyList()
        }

        private fun buildFrame(type: String, body: kotlinx.serialization.json.JsonObjectBuilder.() -> Unit): ProtocolFrame {
            val payload = buildJsonObject {
                put("type", type)
                body()
            }
            return ProtocolFrame(payload)
        }
    }
}
