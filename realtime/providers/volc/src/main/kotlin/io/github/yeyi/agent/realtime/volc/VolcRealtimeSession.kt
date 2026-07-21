package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.RealtimeSession
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VolcRealtimeSession(
    private val client: HttpClient,
) : RealtimeSession {

    private val emitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    private val wsRef = AtomicReference<WebSocketSession?>(null)
    private val writeLock = Mutex()
    private var readJob: Job? = null
    private var readScope: CoroutineScope? = null
    private val eventSeq = AtomicInteger(0)

    override val events: Flow<RealtimeEvent> get() = emitter.asSharedFlow()

    override suspend fun connect(config: SessionConfig) {
        val session = client.webSocketSession(
            urlString = config.endpoint,
            block = { header("X-Api-Key", config.apiKey) },
        )
        wsRef.set(session)
        sendSessionCreate(config)
        waitForSessionCreated(session)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        readScope = scope
        readJob = scope.launch { readLoop(session) }
    }

    private suspend fun sendSessionCreate(config: SessionConfig) {
        val sessionId = UUID.randomUUID().toString()
        sendRawFrame("session.create") {
            put("session", buildJsonObject {
                put("id", sessionId)
                put("model", config.model)
                put("instructions", config.instructions)
                put("audio", buildJsonObject {
                    put("input", buildJsonObject {
                        put("format", config.inputFormat.toVolcFormat())
                    })
                    put("output", buildJsonObject {
                        put("format", config.outputFormat.toVolcFormat())
                        put("voice", config.voice)
                    })
                })
                put("tools", buildJsonArray { })
            })
            put("extension", buildJsonObject {
                put("asr", buildJsonObject { put("extra", buildJsonObject { }) })
                put("tts", buildJsonObject { put("extra", buildJsonObject { }) })
                put("dialog", buildJsonObject {
                    put("location", buildJsonObject { })
                    put("extra", buildJsonObject {
                        put(
                            "audit_response",
                            "抱歉，这个问题我无法回答，你可以换个其他话题，我会尽力为你提供帮助。",
                        )
                        put("enable_loudness_norm", true)
                        put("enable_music", false)
                    })
                })
            })
        }
    }

    private fun AudioFormat.toVolcFormat(): JsonObject = buildJsonObject {
        val type = if (encoding == AudioFormat.Encoding.PCM_SIGNED_LE && sampleBits == 16) {
            "pcm_s16le"
        } else {
            "pcm"
        }
        put("type", type)
        put("rate", sampleRateHz)
    }

    private suspend fun waitForSessionCreated(session: WebSocketSession) {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            val events = VolcStreamDecoder.decode(text)
            for (evt in events) {
                when (evt) {
                    is RealtimeEvent.Connected -> {
                        emitter.emit(evt)
                        return
                    }
                    is RealtimeEvent.Error -> {
                        emitter.emit(evt)
                        if (evt.isFatal) return
                    }
                    else -> emitter.emit(evt)
                }
            }
        }
        emitter.emit(RealtimeEvent.Disconnected(null))
    }

    private suspend fun readLoop(session: WebSocketSession) {
        for (frame in session.incoming) {
            if (frame is Frame.Text) {
                val text = frame.readText()
                VolcStreamDecoder.decode(text).forEach { emitter.emit(it) }
            }
        }
        emitter.emit(RealtimeEvent.Disconnected(null))
    }

    override suspend fun sendAudio(pcm: ByteArray) {
        val encoded = java.util.Base64.getEncoder().encodeToString(pcm)
        sendRawFrame("input_audio_buffer.append") {
            put("audio", encoded)
        }
    }

    override suspend fun commitInput() {
        sendRawFrame("input_audio_buffer.commit") { }
    }

    override suspend fun cancelResponse() {
        sendRawFrame("response.cancel") { }
    }

    override suspend fun injectAndRespond(text: String) {
        sendRawFrame("conversation.item.create") {
            put("items", buildJsonArray {
                add(buildJsonObject {
                    put("type", "message")
                    put("role", "user")
                    put("content", buildJsonArray {
                        add(buildJsonObject {
                            put("type", "input_text")
                            put("text", text)
                        })
                    })
                })
            })
        }
        sendRawFrame("response.create") { }
    }

    override fun close() {
        readJob?.cancel()
        readScope?.cancel()
        readScope = null
        wsRef.get()?.cancel()
        wsRef.set(null)
    }

    private fun nextEventId(): String = "event_${eventSeq.incrementAndGet()}"

    private suspend fun sendRawFrame(type: String, body: JsonObjectBuilder.() -> Unit) {
        val payload = buildJsonObject {
            put("type", type)
            put("event_id", nextEventId())
            body()
        }
        writeLock.withLock {
            wsRef.get()?.send(Frame.Text(payload.toString()))
        }
    }
}