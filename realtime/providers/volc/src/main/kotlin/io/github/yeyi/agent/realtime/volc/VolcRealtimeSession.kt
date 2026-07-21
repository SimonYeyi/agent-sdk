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
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class VolcRealtimeSession(
    private val client: HttpClient,
) : RealtimeSession {

    private val json = Json { ignoreUnknownKeys = true }
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
        val sessionConfig = VolcSessionConfig(
            id = sessionId,
            model = config.model,
            instructions = config.instructions,
            audio = VolcAudioConfig(
                input = VolcAudioSideConfig(format = config.inputFormat.toVolcFormatConfig()),
                output = VolcAudioSideConfig(
                    format = config.outputFormat.toVolcFormatConfig(),
                    voice = config.voice,
                ),
            ),
            tools = emptyList(),
        )
        val dialogExtra = buildJsonObject {
            put("audit_response", "抱歉，这个问题我无法回答，你可以换个其他话题，我会尽力为你提供帮助。")
            put("enable_loudness_norm", true)
            put("enable_music", false)
        }
        val extensionConfig = VolcExtensionConfig(
            asr = VolcExtensionSide(extra = buildJsonObject { }),
            tts = VolcExtensionSide(extra = buildJsonObject { }),
            dialog = VolcExtensionDialog(
                location = buildJsonObject { },
                extra = dialogExtra,
            ),
        )
        sendRawFrame("session.create") {
            put("session", json.encodeToJsonElement(VolcSessionConfig.serializer(), sessionConfig))
            put("extension", json.encodeToJsonElement(VolcExtensionConfig.serializer(), extensionConfig))
        }
    }

    private fun AudioFormat.toVolcFormatConfig(): VolcFormatConfig = VolcFormatConfig(
        type = if (encoding == AudioFormat.Encoding.PCM_SIGNED_LE && sampleBits == 16) {
            "pcm_s16le"
        } else {
            "pcm"
        },
        rate = sampleRateHz,
    )

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
        val encoded = Base64.getEncoder().encodeToString(pcm)
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
        val item = VolcConversationItem(
            type = "message",
            role = "user",
            content = buildJsonArray {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", text)
                })
            },
        )
        sendRawFrame("conversation.item.create") {
            put(
                "items",
                json.encodeToJsonElement(ListSerializer(VolcConversationItem.serializer()), listOf(item)),
            )
        }
        sendRawFrame("response.create") { }
    }

    /**
     * session.update — 更新会话参数 (不重建连接)。session.id 若为 null，服务端会保留原会话 id。
     */
    internal suspend fun sessionUpdate(
        session: VolcSessionConfig,
        extension: VolcExtensionConfig? = null
    ) {
        sendRawFrame("session.update") {
            put("session", json.encodeToJsonElement(VolcSessionConfig.serializer(), session))
            if (extension != null) {
                put(
                    "extension",
                    json.encodeToJsonElement(VolcExtensionConfig.serializer(), extension)
                )
            }
        }
    }

    /** session.close — 通知服务端关闭会话 (server will respond with session.closed). */
    internal suspend fun sessionClose() {
        sendRawFrame("session.close") { }
    }

    /** speech_text_buffer.append — 流式推送用户文本输入 (用于 TTS 直接播放 + ASR 模拟). */
    internal suspend fun speechTextAppend(speechId: String, text: String) {
        sendRawFrame("speech_text_buffer.append") {
            put("speech_id", speechId)
            put("text", text)
        }
    }

    /** speech_text_buffer.commit — 提交完整 speech buffer. */
    internal suspend fun speechTextCommit(speechId: String, text: String) {
        sendRawFrame("speech_text_buffer.commit") {
            put("speech_id", speechId)
            put("text", text)
        }
    }

    /** speech_text_buffer.replacement.append — 流式打断替换输入. */
    internal suspend fun speechTextReplacementAppend(speechId: String, text: String) {
        sendRawFrame("speech_text_buffer.replacement.append") {
            put("speech_id", speechId)
            put("text", text)
        }
    }

    /** speech_text_buffer.replacement.commit — 提交打断替换. */
    internal suspend fun speechTextReplacementCommit(speechId: String, text: String) {
        sendRawFrame("speech_text_buffer.replacement.commit") {
            put("speech_id", speechId)
            put("text", text)
        }
    }

    /** conversation.item.create — 注入多条历史 item. */
    internal suspend fun conversationItemCreate(items: List<VolcConversationItem>) {
        sendRawFrame("conversation.item.create") {
            put(
                "items", json.encodeToJsonElement(
                    ListSerializer(VolcConversationItem.serializer()),
                    items,
                )
            )
        }
    }

    /** conversation.item.update — 更新已存在 item. */
    internal suspend fun conversationItemUpdate(items: List<VolcConversationItem>) {
        sendRawFrame("conversation.item.update") {
            put(
                "items", json.encodeToJsonElement(
                    ListSerializer(VolcConversationItem.serializer()),
                    items,
                )
            )
        }
    }

    /** conversation.item.retrieve — 拉取 item (空 items 表示拉取全部). */
    internal suspend fun conversationItemRetrieve(items: List<VolcConversationItem> = emptyList()) {
        sendRawFrame("conversation.item.retrieve") {
            put(
                "items", json.encodeToJsonElement(
                    ListSerializer(VolcConversationItem.serializer()),
                    items,
                )
            )
        }
    }

    /** conversation.item.delete — 删除 item. */
    internal suspend fun conversationItemDelete(items: List<VolcConversationItem>) {
        sendRawFrame("conversation.item.delete") {
            put(
                "items", json.encodeToJsonElement(
                    ListSerializer(VolcConversationItem.serializer()),
                    items,
                )
            )
        }
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