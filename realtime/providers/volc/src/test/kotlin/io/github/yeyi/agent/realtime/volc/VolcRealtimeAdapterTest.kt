@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.ProtocolFrame
import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.Tool
import io.github.yeyi.agent.realtime.TurnDetection
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.util.Base64
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class VolcRealtimeAdapterTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun newAdapter() = VolcRealtimeAdapter()

    private fun makeConfig(
        tools: List<Tool> = emptyList(),
        turnDetection: TurnDetection = TurnDetection.ServerVad(),
    ) = SessionConfig(
        apiKey = "appid:accesskey",
        endpoint = "wss://test",
        model = "model",
        instructions = "你是一个助手",
        voice = "zh-CN",
        tools = tools,
        turnDetection = turnDetection,
    )

    // === 帧构造测试 ===

    @Test
    fun `createSessionFrame has correct payload structure`() {
        val adapter = newAdapter()
        val config = makeConfig()
        val frame = adapter.createSessionFrame(config)
        val payload = frame.payload

        assertEquals("session.create", (payload["type"] as JsonPrimitive).content)
        assertTrue(payload.containsKey("event_id"))

        val session = payload["session"] as JsonObject
        assertEquals("model", (session["model"] as JsonPrimitive).content)
        assertEquals("你是一个助手", (session["instructions"] as JsonPrimitive).content)

        val audio = session["audio"] as JsonObject
        val inputFormat = (audio["input"] as JsonObject)["format"] as JsonObject
        assertEquals("pcm_s16le", (inputFormat["type"] as JsonPrimitive).content)
        assertEquals(16000, (inputFormat["rate"] as JsonPrimitive).content.toInt())

        val outputFormat = (audio["output"] as JsonObject)["format"] as JsonObject
        assertEquals("pcm_s16le", (outputFormat["type"] as JsonPrimitive).content)
        assertEquals(24000, (outputFormat["rate"] as JsonPrimitive).content.toInt())
    }

    @Test
    fun `createSessionFrame without tools has empty tools array`() {
        val adapter = newAdapter()
        val config = makeConfig()
        val frame = adapter.createSessionFrame(config)
        val payload = frame.payload
        val session = payload["session"] as JsonObject
        val tools = session["tools"]
        assertTrue(tools is kotlinx.serialization.json.JsonArray && tools.isEmpty())
    }

    @Test
    fun `sendAudioFrame encodes pcm as base64`() {
        val adapter = newAdapter()
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val frame = adapter.sendAudioFrame(pcm)
        val payload = frame.payload

        assertEquals("input_audio_buffer.append", (payload["type"] as JsonPrimitive).content)
        val encoded = (payload["audio"] as JsonPrimitive).content
        assertEquals(Base64.getEncoder().encodeToString(pcm), encoded)
    }

    @Test
    fun `commitAudioFrame has correct type`() {
        val adapter = newAdapter()
        val frame = adapter.commitAudioFrame()
        assertEquals("input_audio_buffer.commit", (frame.payload["type"] as JsonPrimitive).content)
    }

    @Test
    fun `cancelResponseFrame has correct type`() {
        val adapter = newAdapter()
        val frame = adapter.cancelResponseFrame()
        assertEquals("response.cancel", (frame.payload["type"] as JsonPrimitive).content)
    }

    @Test
    fun `commitSpeechTextFrame returns append and commit frames`() {
        val adapter = newAdapter()
        val frames = adapter.commitSpeechTextFrame("你好")
        assertEquals(2, frames.size)

        val append = frames[0].payload
        assertEquals("speech_text_buffer.append", (append["type"] as JsonPrimitive).content)
        assertEquals("你好", (append["text"] as JsonPrimitive).content)

        val commit = frames[1].payload
        assertEquals("speech_text_buffer.commit", (commit["type"] as JsonPrimitive).content)
        assertEquals("null", (commit["tts_prompt"] as JsonPrimitive).content)
    }

    // === 事件解析测试 ===

    @Test
    fun `session_created emits Connected event`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        adapter.handleIncomingFrame(buildIncomingFrame("""
            {"type": "session.created", "session": {"id": "sess_123"}}
        """))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        val connected = collected[0] as RealtimeEvent.Connected
        assertEquals("sess_123", connected.sessionId)
    }

    @Test
    fun `session_closed emits Disconnected event`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        adapter.handleIncomingFrame(buildIncomingFrame("""{"type": "session.closed"}"""))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertIs<RealtimeEvent.Disconnected>(collected[0])
    }

    @Test
    fun `conversation_item_input_audio_transcription_delta emits UserTranscriptDelta`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        adapter.handleIncomingFrame(buildIncomingFrame("""{"type": "conversation.item.input_audio_transcription.delta", "delta": "今天"}"""))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertEquals("今天", (collected[0] as RealtimeEvent.UserTranscriptDelta).text)
    }

    @Test
    fun `conversation_item_input_audio_transcription_completed emits UserTranscriptCompleted`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        adapter.handleIncomingFrame(buildIncomingFrame("""{"type": "conversation.item.input_audio_transcription.completed", "text": "今天天气真好"}"""))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertEquals("今天天气真好", (collected[0] as RealtimeEvent.UserTranscriptCompleted).text)
    }

    @Test
    fun `response_output_audio_delta emits AssistantAudioDelta with decoded pcm`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        val pcm = byteArrayOf(0x01, 0x02, 0x03, 0x04)
        val encoded = Base64.getEncoder().encodeToString(pcm)
        adapter.handleIncomingFrame(buildIncomingFrame("""{"type": "response.output_audio.delta", "delta": "$encoded"}"""))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertEquals(pcm.toList(), (collected[0] as RealtimeEvent.AssistantAudioDelta).pcm.toList())
    }

    @Test
    fun `response_output_audio_started emits AssistantAudioStarted`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        adapter.handleIncomingFrame(buildIncomingFrame("""{"type": "response.output_audio.started"}"""))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertIs<RealtimeEvent.AssistantAudioStarted>(collected[0])
    }

    @Test
    fun `response_output_audio_done emits AssistantAudioDone`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        adapter.handleIncomingFrame(buildIncomingFrame("""{"type": "response.output_audio.done"}"""))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertIs<RealtimeEvent.AssistantAudioDone>(collected[0])
    }

    @Test
    fun `response_output_text_delta emits AssistantTextDelta`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        adapter.handleIncomingFrame(buildIncomingFrame("""{"type": "response.output_text.delta", "delta": "你好"}"""))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertEquals("你好", (collected[0] as RealtimeEvent.AssistantTextDelta).text)
    }

    @Test
    fun `response_canceled emits ResponseCanceled`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        adapter.handleIncomingFrame(buildIncomingFrame("""{"type": "response.canceled"}"""))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertIs<RealtimeEvent.ResponseCanceled>(collected[0])
    }

    @Test
    fun `response_done emits ResponseDone`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        adapter.handleIncomingFrame(buildIncomingFrame("""{"type": "response.done"}"""))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        assertIs<RealtimeEvent.ResponseDone>(collected[0])
    }

    @Test
    fun `error event emits Error event`() = runTest {
        val adapter = newAdapter()
        val collected = mutableListOf<RealtimeEvent>()
        val job = launch { adapter.events.toList(collected) }
        runCurrent()
        adapter.handleIncomingFrame(buildIncomingFrame("""{"type": "error", "error": {"code": "ERR_001", "message": "bad request"}}"""))
        runCurrent()
        job.cancel()

        assertEquals(1, collected.size)
        val errorEvent = collected[0] as RealtimeEvent.Error
        assertEquals("ERR_001", errorEvent.code)
        assertEquals("bad request", errorEvent.message)
    }

    // === 工具调用测试 ===

    @Test
    fun `function_call_arguments_done returns tool result frame`() = runTest {
        val adapter = newAdapter()
        val fakeTool = object : Tool {
            override val name = "get_weather"
            override val description = "获取天气"
            override val parametersSchema = JsonObject(emptyMap())
            override suspend fun execute(arguments: kotlinx.serialization.json.JsonElement) = """{"city": "北京"}"""
        }
        adapter.registerTools(listOf(fakeTool))

        val frame = buildIncomingFrame("""
            {
                "type": "response.function_call_arguments.done",
                "items": [{"call_id": "call_001", "name": "get_weather", "arguments": "{}"}]
            }
        """)
        val replyFrames = adapter.handleIncomingFrame(frame)

        assertEquals(1, replyFrames.size)
        val replyPayload = replyFrames[0].payload
        assertEquals("conversation.item.create", (replyPayload["type"] as JsonPrimitive).content)
    }

    @Test
    fun `function_call with unregistered tool returns error result`() = runTest {
        val adapter = newAdapter()
        val frame = buildIncomingFrame("""
            {
                "type": "response.function_call_arguments.done",
                "items": [{"call_id": "call_002", "name": "unknown_tool", "arguments": "{}"}]
            }
        """)
        val replyFrames = adapter.handleIncomingFrame(frame)

        assertEquals(1, replyFrames.size)
        val replyPayload = replyFrames[0].payload
        assertEquals("conversation.item.create", (replyPayload["type"] as JsonPrimitive).content)
    }

    // === auth header 测试 ===

    @Test
    fun `getAuthHeaders splits apiKey by colon`() {
        val adapter = newAdapter()
        val config = makeConfig()
        val headers = adapter.getAuthHeaders(config)

        assertEquals("appid", headers["X-Api-App-ID"])
        assertEquals("accesskey", headers["X-Api-Access-Key"])
    }

    @Test
    fun `getAuthHeaders with single part uses X-Api-Key`() {
        val adapter = newAdapter()
        val config = SessionConfig(
            apiKey = "singlekey",
            endpoint = "wss://test",
            model = "m",
            instructions = "i",
            voice = "v",
        )
        val headers = adapter.getAuthHeaders(config)

        assertEquals("singlekey", headers["X-Api-Key"])
    }

    // === 私有 helper ===

    private fun buildIncomingFrame(jsonStr: String): ProtocolFrame {
        val payload = json.parseToJsonElement(jsonStr) as JsonObject
        return ProtocolFrame(payload)
    }
}
