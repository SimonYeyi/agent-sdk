package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.RealtimeSession
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.Tool
import io.github.yeyi.agent.realtime.TurnDetection
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.CancellationException
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
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
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
    private val toolsByName: MutableMap<String, Tool> = mutableMapOf()

    override val events: Flow<RealtimeEvent> get() = emitter.asSharedFlow()

    override suspend fun connect(config: SessionConfig) {
        val session = client.webSocketSession(
            urlString = config.endpoint,
            block = { header("X-Api-Key", config.apiKey) },
        )
        wsRef.set(session)
        toolsByName.clear()
        config.tools.forEach { toolsByName[it.name] = it }
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
            tools = config.tools.map { tool ->
                buildJsonObject {
                    put("type", "function")
                    put("name", tool.name)
                    put("description", tool.description)
                    put("parameters", tool.parametersSchema)
                }
            },
        )
        val asrExtra = buildJsonObject {
            (config.turnDetection as? TurnDetection.ServerVad)?.thresholdMs?.let {
                put("enable_custom_vad", true)
                put("end_smooth_window_ms", it)
            }
        }
        val dialogExtra = buildJsonObject {
            put(
                "audit_response",
                "抱歉，这个问题我无法回答，你可以换个其他话题，我会尽力为你提供帮助。"
            )
            put("enable_loudness_norm", true)
            put("enable_music", false)
            if (config.turnDetection is TurnDetection.Manual) {
                put("input_mod", "push_to_talk")
            }
        }
        val extensionConfig = VolcExtensionConfig(
            asr = VolcExtensionSide(extra = asrExtra),
            tts = VolcExtensionSide(extra = buildJsonObject { }),
            dialog = VolcExtensionDialog(
                location = buildJsonObject { },
                extra = dialogExtra,
            ),
        )
        sendRawFrame("session.create") {
            put("session", json.encodeToJsonElement(VolcSessionConfig.serializer(), sessionConfig))
            put(
                "extension",
                json.encodeToJsonElement(VolcExtensionConfig.serializer(), extensionConfig)
            )
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
            if (frame !is Frame.Text) continue
            val text = frame.readText()
            if (tryHandleToolCall(text)) continue
            VolcStreamDecoder.decode(text).forEach { emitter.emit(it) }
        }
        emitter.emit(RealtimeEvent.Disconnected(null))
    }

    /**
     * 拦截 response.function_call_arguments.done 帧 — 找本地 tool 执行, 然后通过
     * conversation.item.create 回传结果. 返回 true 表示该帧已被 FC 路径消费.
     */
    private suspend fun tryHandleToolCall(text: String): Boolean {
        val evt = try {
            json.decodeFromString(VolcEvent.serializer(), text)
        } catch (_: Throwable) {
            return false
        }
        if (evt.type != "response.function_call_arguments.done") return false
        val call = evt.functionCall
        val callId = call?.callId ?: "function_call.call_id is missing"
        val arguments: JsonElement = call?.arguments
            ?.takeIf { it.isNotBlank() }
            ?.let { json.parseToJsonElement(it) }
            ?: JsonObject(emptyMap())
        val output = try {
            if (call?.name == null) error("function_call.name missing")
            val tool = toolsByName[call.name] ?: error("tool not registered: ${call.name}")
            tool.execute(arguments)
        } catch (t: CancellationException) {
            throw t
        } catch (t: Throwable) {
            "工具调用失败: $call : ${t.message ?: t.toString()}"
        }
        sendToolResult(callId, output)
        return true
    }

    private suspend fun sendToolResult(callId: String, output: String) {
        val item = VolcConversationItem(
            type = "message",
            role = "tool",
            callId = callId,
            content = buildJsonArray {
                add(buildJsonObject {
                    put("type", "input_text")
                    put("text", output)
                })
            },
        )
        sendRawFrame("conversation.item.create") {
            put(
                "items",
                json.encodeToJsonElement(
                    ListSerializer(VolcConversationItem.serializer()),
                    listOf(item)
                ),
            )
        }
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
        speechTextCommit(text)
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

    /**
     * speech_text_buffer.* 协议族 — 仅 speech_text_buffer.commit 在 Volc 协议文档中有定义
     * (字段: type/event_id/text), 用于客户端向服务端注入文本发言, 服务端按"用户说了一句话"处理,
     * 自动触发 ASR + 模型回复 + TTS 播报. 其余三个 (append / replacement.*) 协议未定义, 暂作 private 备用.
     */

    /** speech_text_buffer.append — 流式推送用户文本输入 (协议未定义, 仅作备用). */
    private suspend fun speechTextAppend(speechId: String, text: String) {
        sendRawFrame("speech_text_buffer.append") {
            put("speech_id", speechId)
            put("text", text)
        }
    }

    /** speech_text_buffer.commit — 注入文本发言, 服务端按"用户说了一句话"处理, 触发 ASR + 模型回复 + TTS. */
    private suspend fun speechTextCommit(text: String) {
        sendRawFrame("speech_text_buffer.commit") {
            put("text", text)
        }
    }

    /** speech_text_buffer.replacement.append — 流式打断替换输入. */
    private suspend fun speechTextReplacementAppend(speechId: String, text: String) {
        sendRawFrame("speech_text_buffer.replacement.append") {
            put("speech_id", speechId)
            put("text", text)
        }
    }

    /** speech_text_buffer.replacement.commit — 提交打断替换. */
    private suspend fun speechTextReplacementCommit(speechId: String, text: String) {
        sendRawFrame("speech_text_buffer.replacement.commit") {
            put("speech_id", speechId)
            put("text", text)
        }
    }

    /** conversation.item.create — 注入多条历史 item. */
    private suspend fun conversationItemCreate(items: List<VolcConversationItem>) {
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
    private suspend fun conversationItemUpdate(items: List<VolcConversationItem>) {
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
    private suspend fun conversationItemRetrieve(items: List<VolcConversationItem> = emptyList()) {
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
    private suspend fun conversationItemDelete(items: List<VolcConversationItem>) {
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