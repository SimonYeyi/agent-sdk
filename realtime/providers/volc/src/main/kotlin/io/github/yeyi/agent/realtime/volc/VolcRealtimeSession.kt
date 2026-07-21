package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.RealtimeSession
import io.github.yeyi.agent.realtime.SessionConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicReference

class VolcRealtimeSession(
    private val client: HttpClient,
) : RealtimeSession {

    private val emitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    private val wsRef = AtomicReference<WebSocketSession?>(null)
    private val writeLock = Mutex()
    private var readJob: Job? = null

    override val events: Flow<RealtimeEvent> get() = emitter.asSharedFlow()

    override suspend fun connect(config: SessionConfig) {
        val session = client.webSocketSession(
            urlString = config.endpoint,
            block = { header("Authorization", "Bearer; ${config.apiKey}") },
        )
        wsRef.set(session)
        sendSessionCreate(config)
        readJob = CoroutineScope(Dispatchers.Default).launch { readLoop(session) }
    }

    private suspend fun sendSessionCreate(config: SessionConfig) {
        val payload = buildJsonObject {
            put("type", "session.create")
            put("model", config.model)
            put("instructions", config.instructions)
            put("voice", config.voice)
            put("input_format", "pcm_s16le")
            put("output_format", "pcm_s16le")
        }
        writeLock.withLock {
            wsRef.get()?.send(Frame.Text(payload.toString()))
        }
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
        val payload = buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("delta", encoded)
        }
        sendRawFrame(payload.toString())
    }

    override suspend fun commitInput() {
        sendRawFrame("""{"type":"input_audio_buffer.commit"}""")
    }

    override suspend fun cancelResponse() {
        sendRawFrame("""{"type":"response.cancel"}""")
    }

    override suspend fun injectAndRespond(text: String) {
        sendRawFrame(buildJsonObject {
            put("type", "conversation.item.create")
            put("item", buildJsonObject {
                put("type", "message")
                put("role", "assistant")
                put("content", buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
        }.toString())
        sendRawFrame("""{"type":"response.create"}""")
    }

    private suspend fun sendRawFrame(text: String) {
        writeLock.withLock {
            wsRef.get()?.send(Frame.Text(text))
        }
    }

    override fun close() {
        readJob?.cancel()
        wsRef.get()?.cancel()
        wsRef.set(null)
    }
}
