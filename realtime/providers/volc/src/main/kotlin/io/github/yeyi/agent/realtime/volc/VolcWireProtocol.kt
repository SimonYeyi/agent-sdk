package io.github.yeyi.agent.realtime.volc

import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicInteger

/**
 * Volc realtime wire 协议封装层 — 每个 public/internal 方法对应一个 Volc C->S type,
 * 接收侧把 raw text frame 解析 + emit 为 VolcEvent.
 *
 * 公共 API 表面:
 *
 * - [events]: 接收 S->C 协议事件流 ([VolcEvent]); wire **不接触 SDK 抽象
 *   [io.github.yeyi.agent.realtime.RealtimeEvent]**, 由 [VolcRealtimeSession] 订阅后适配.
 * - [handleIncoming]: 把 raw text frame 解析为 VolcEvent 并 emit 到 [events],
 *   供 [VolcRealtimeSession] 的读循环在收到下行消息时委派调用.
 * - 14 个 Volc C->S type 映射: [sessionCreate] / [sessionUpdate] / [sessionClose] /
 *   [inputAudioBufferAppend] / [inputAudioBufferCommit] / [responseCancel] /
 *   [speechTextBufferAppend] / [speechTextBufferCommit] /
 *   [speechTextBufferReplacementAppend] / [speechTextBufferReplacementCommit] /
 *   [conversationItemCreate] / [conversationItemUpdate] /
 *   [conversationItemRetrieve] / [conversationItemDelete]
 *
 * 构造时接收已建联的 [WebSocketSession]; wire 内部持有 [VolcEvent] emitter,
 * 通过 [events] 暴露. WebSocket 建联/关闭、读循环、SDK 编排
 * ([io.github.yeyi.agent.realtime.SessionConfig] -> Volc DTO 组装 / base64 /
 * 工具查找与执行 / FC 拦截 / VolcEvent -> RealtimeEvent 适配) 全部由
 * [VolcRealtimeSession] 处理. wire 不接触
 * [io.github.yeyi.agent.realtime.SessionConfig] / [io.github.yeyi.agent.realtime.Tool] /
 * PCM ByteArray / [io.github.yeyi.agent.realtime.RealtimeEvent].
 */
internal class VolcWireProtocol(private val session: WebSocketSession) {
    private val json = Json { ignoreUnknownKeys = true }
    private val writeLock = Mutex()
    private val eventSeq = AtomicInteger(0)
    private val eventEmitter = MutableSharedFlow<VolcEvent>(extraBufferCapacity = 64)

    val events: Flow<VolcEvent> get() = eventEmitter.asSharedFlow()

    /**
     * 解析 raw text frame 为 [VolcEvent] 并 emit 到 [eventEmitter], 返回解析结果.
     * 供 [VolcRealtimeSession] 读循环在收到下行消息时委派调用. 解析失败抛
     * SerializationException (与原 VolcStreamDecoder.decode 行为一致).
     */
    suspend fun handleIncoming(text: String): VolcEvent {
        val evt = json.decodeFromString(VolcEvent.serializer(), text)
        eventEmitter.emit(evt)
        return evt
    }

    /** `session.create` — 发起会话, 携带初始 session 配置. */
    internal suspend fun sessionCreate(
        session: VolcSessionConfig,
        extension: VolcSessionExtensionConfig? = null,
    ) {
        sendRawFrame("session.create") {
            put("session", json.encodeToJsonElement(VolcSessionConfig.serializer(), session))
            if (extension != null) {
                put(
                    "extension",
                    json.encodeToJsonElement(VolcSessionExtensionConfig.serializer(), extension)
                )
            }
        }
    }

    /** `session.update` — 更新已有会话参数. */
    internal suspend fun sessionUpdate(
        session: VolcSessionConfig,
        extension: VolcSessionExtensionConfig? = null,
    ) {
        sendRawFrame("session.update") {
            put("session", json.encodeToJsonElement(VolcSessionConfig.serializer(), session))
            if (extension != null) {
                put(
                    "extension",
                    json.encodeToJsonElement(VolcSessionExtensionConfig.serializer(), extension)
                )
            }
        }
    }

    /** `session.close` — 通知服务端关闭会话 (server 会回 `session.closed`). */
    internal suspend fun sessionClose() {
        sendRawFrame("session.close") { }
    }

    /** `input_audio_buffer.append` — 追加一段已 base64 编码的 PCM. base64 由调用方负责. */
    internal suspend fun inputAudioBufferAppend(audio: String) {
        sendRawFrame("input_audio_buffer.append") {
            put("audio", audio)
        }
    }

    /** `input_audio_buffer.commit` — 提交当前 buffer, 触发 ASR. */
    internal suspend fun inputAudioBufferCommit() {
        sendRawFrame("input_audio_buffer.commit") { }
    }

    /** `response.cancel` — 打断模型当前回复. */
    internal suspend fun responseCancel() {
        sendRawFrame("response.cancel") { }
    }

    /** `speech_text_buffer.append` — 流式追加文本输入片段. */
    internal suspend fun speechTextBufferAppend(text: String) {
        sendRawFrame("speech_text_buffer.append") {
            put("text", text)
        }
    }

    /**
     * `speech_text_buffer.commit` — 注入文本发言. 服务端按"用户说了一句话"处理,
     * 自动触发 ASR + 模型回复 + TTS 播报.
     */
    internal suspend fun speechTextBufferCommit(text: String) {
        sendRawFrame("speech_text_buffer.commit") {
            put("tts_prompt", "原文播报")
            put("text", text)
        }
    }

    /** `speech_text_buffer.replacement.append` — 流式追加打断替换文本片段. */
    internal suspend fun speechTextBufferReplacementAppend(text: String) {
        sendRawFrame("speech_text_buffer.replacement.append") {
            put("text", text)
        }
    }

    /** `speech_text_buffer.replacement.commit` — 提交打断替换. */
    internal suspend fun speechTextBufferReplacementCommit(text: String) {
        sendRawFrame("speech_text_buffer.replacement.commit") {
            put("text", text)
        }
    }

    /** `conversation.item.create` — 注入多条历史 item. */
    internal suspend fun conversationItemCreate(items: List<VolcConversationItem>) {
        sendRawFrame("conversation.item.create") {
            put(
                "items",
                json.encodeToJsonElement(ListSerializer(VolcConversationItem.serializer()), items),
            )
        }
    }

    /** `conversation.item.update` — 更新已存在 item. */
    internal suspend fun conversationItemUpdate(items: List<VolcConversationItem>) {
        sendRawFrame("conversation.item.update") {
            put(
                "items",
                json.encodeToJsonElement(ListSerializer(VolcConversationItem.serializer()), items),
            )
        }
    }

    /** `conversation.item.retrieve` — 拉取 item; 空 list 表示拉取全部. */
    internal suspend fun conversationItemRetrieve(items: List<VolcConversationItem> = emptyList()) {
        sendRawFrame("conversation.item.retrieve") {
            put(
                "items",
                json.encodeToJsonElement(ListSerializer(VolcConversationItem.serializer()), items),
            )
        }
    }

    /** `conversation.item.delete` — 删除 item. */
    internal suspend fun conversationItemDelete(items: List<VolcConversationItem>) {
        sendRawFrame("conversation.item.delete") {
            put(
                "items",
                json.encodeToJsonElement(ListSerializer(VolcConversationItem.serializer()), items),
            )
        }
    }

    private suspend fun sendRawFrame(type: String, body: JsonObjectBuilder.() -> Unit) {
        val payload = buildJsonObject {
            put("type", type)
            put("event_id", "event_${eventSeq.incrementAndGet()}")
            body()
        }
        writeLock.withLock {
            session.send(Frame.Text(payload.toString()))
        }
    }
}