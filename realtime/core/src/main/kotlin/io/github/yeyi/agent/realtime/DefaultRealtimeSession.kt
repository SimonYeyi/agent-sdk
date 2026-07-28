package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

private class DefaultRealtimeSession(
    private val client: HttpClient,
    private val adapter: RealtimeAdapter,
) : RealtimeSession {
    private var ws: WebSocketSession? = null
    private var scope: CoroutineScope? = null
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()
    private val disconnectedEvent = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 1)

    override val inputAudioFormat: AudioFormat get() = adapter.inputAudioFormat
    override val outputAudioFormat: AudioFormat get() = adapter.outputAudioFormat
    override val events: Flow<RealtimeEvent>
        get() = merge(
            disconnectedEvent,
            adapter.events.filter { it !is RealtimeEvent.Disconnected }
        )

    override suspend fun connect(config: SessionConfig) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        ws = client.webSocketSession(urlString = config.endpoint) {
            adapter.getAuthHeaders(config).forEach { (k, v) -> header(k, v) }
        }

        adapter.registerTools(config.tools)

        sendFrame(adapter.createSessionFrame(config))

        startReadLoop()

        waitConnected()
    }

    override fun close() {
        ws?.cancel()
        ws = null
        scope?.cancel()
        scope = null
    }

    override suspend fun sendAudio(pcm: ByteArray) {
        val frame = adapter.sendAudioFrame(pcm)
        sendFrame(frame)
    }

    override suspend fun commitAudio() {
        val frame = adapter.commitAudioFrame()
        sendFrame(frame)
    }

    override suspend fun cancelResponse() {
        val frame = adapter.cancelResponseFrame()
        sendFrame(frame)
    }

    override suspend fun injectAndRespond(text: String) {
        adapter.commitSpeechTextFrame(text).forEach { sendFrame(it) }
    }

    private suspend fun sendFrame(frame: ProtocolFrame) {
        writeLock.withLock {
            ws!!.send(Frame.Text(json.encodeToString(JsonObject.serializer(), frame.payload)))
        }
    }

    private fun startReadLoop() {
        scope?.launch {
            try {
                for (frame in ws!!.incoming) {
                    if (frame !is Frame.Text) continue
                    val payload = json.decodeFromString(JsonObject.serializer(), frame.readText())
                    val replyFrames = adapter.handleIncomingFrame(ProtocolFrame(payload))
                    replyFrames.forEach { scope?.launch { sendFrame(it) } }
                }
            } finally {
                disconnectedEvent.emit(RealtimeEvent.Disconnected("connection closed"))
            }
        }
    }

    private suspend fun waitConnected() {
        adapter.events.onEach { event ->
            if (event is RealtimeEvent.Error) {
                error("Session create failed: ${event.code} - ${event.message}")
            }
        }.filterIsInstance<RealtimeEvent.Connected>().first()
    }
}

public fun RealtimeSession(client: HttpClient, adapter: RealtimeAdapter): RealtimeSession {
    return DefaultRealtimeSession(client, adapter)
}
