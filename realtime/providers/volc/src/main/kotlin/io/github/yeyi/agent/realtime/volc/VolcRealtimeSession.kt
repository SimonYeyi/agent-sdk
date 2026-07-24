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
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.Base64
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

/**
 * Volc realtime session — [RealtimeSession] 接口的 Volc 实现入口.
 *
 * 职责:
 * - WebSocket 连接生命周期管理 (建联 / 关闭 / 读循环 / `Connected` 握手等待).
 * - SDK 编排: [SessionConfig] -> Volc DTO 组装, PCM -> Base64, 本地 tool 查找与执行.
 * - 订阅 [VolcWireProtocol.events], 做 FC (`response.function_call_arguments.done`) 拦截
 *   与 [VolcEvent] -> [RealtimeEvent] 适配.
 * - 调用 [VolcWireProtocol] 的 type 映射方法完成帧发送与 tool 结果回传.
 *
 * [VolcWireProtocol] 不接触 [RealtimeEvent]; 每个公共方法对应一个 Volc C->S type.
 */
public class VolcRealtimeSession(private val client: HttpClient) : RealtimeSession {
    private val json = Json { ignoreUnknownKeys = true }
    private val eventEmitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    private val wsRef = AtomicReference<WebSocketSession?>(null)
    private var wire: VolcWireProtocol? = null
    private var scope: CoroutineScope? = null
    private val toolsByName: MutableMap<String, Tool> = mutableMapOf()

    override val inputAudioFormat: AudioFormat = AudioFormat(
        sampleRateHz = 16_000,
        encoding = AudioFormat.Encoding.PCM_16BIT,
    )

    override val outputAudioFormat: AudioFormat = AudioFormat(
        sampleRateHz = 24_000,
        encoding = AudioFormat.Encoding.PCM_16BIT,
    )

    override val events: Flow<RealtimeEvent> get() = eventEmitter.asSharedFlow()

    override suspend fun connect(config: SessionConfig) {
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val session = client.webSocketSession(
            urlString = config.endpoint,
            block = {
                val apiKey = config.apiKey.split(":")
                if (apiKey.size == 1) {
                    header("X-Api-Key", apiKey)
                } else {
                    header("X-Api-App-ID", apiKey[0])
                    header("X-Api-Access-Key", apiKey[1])
                }
            },
        )
        wsRef.set(session)
        toolsByName.clear()
        config.tools.forEach { toolsByName[it.name] = it }
        val wire = createVolcWireProtocol(session).also { this@VolcRealtimeSession.wire = it }
        wire.sessionCreate(buildSessionConfig(config), buildSessionExtensionConfig(config))
        waitForSessionCreated(session, wire)
        startReadLoop(session, wire)
    }

    override suspend fun sendAudio(pcm: ByteArray) {
        val encoded = Base64.getEncoder().encodeToString(pcm)
        wire?.inputAudioBufferAppend(encoded)
    }

    override suspend fun commitInput() {
        wire?.inputAudioBufferCommit()
    }

    override suspend fun cancelResponse() {
        wire?.responseCancel()
    }

    override suspend fun injectAndRespond(text: String) {
        wire?.speechTextBufferCommit(text)
    }

    override fun close() {
        wire = null
        scope?.cancel()
        scope = null
        wsRef.get()?.cancel()
        wsRef.set(null)
    }

    // ---- SDK 编排: SessionConfig -> Volc DTO ----

    private fun buildSessionConfig(config: SessionConfig): VolcSessionConfig =
        VolcSessionConfig(
            id = UUID.randomUUID().toString(),
            model = config.model,
            instructions = config.instructions,
            audio = VolcAudioConfig(
                input = VolcAudioSideConfig(format = inputAudioFormat.toVolcFormatConfig()),
                output = VolcAudioSideConfig(
                    format = outputAudioFormat.toVolcFormatConfig(),
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

    private fun buildSessionExtensionConfig(config: SessionConfig): VolcSessionExtensionConfig {
        val asrExtra = buildJsonObject {
            (config.turnDetection as? TurnDetection.ServerVad)?.thresholdMs?.let {
                put("enable_custom_vad", true)
                put("end_smooth_window_ms", it)
            }
        }
        val dialogExtra = buildJsonObject {
            put(
                "audit_response",
                "抱歉，这个问题我无法回答，你可以换个其他话题，我会尽力为你提供帮助。",
            )
            put("enable_loudness_norm", true)
            put("enable_music", false)
            if (config.turnDetection is TurnDetection.Manual) {
                put("input_mod", "push_to_talk")
            }
        }
        return VolcSessionExtensionConfig(
            asr = VolcExtensionSide(extra = asrExtra),
            tts = VolcExtensionSide(extra = buildJsonObject { }),
            dialog = VolcExtensionDialog(
                location = buildJsonObject { },
                extra = dialogExtra,
            ),
        )
    }

    private fun AudioFormat.toVolcFormatConfig(): VolcFormatConfig {
        val type = when (encoding) {
            AudioFormat.Encoding.PCM_16BIT -> "pcm_s16le"
            AudioFormat.Encoding.PCM_OPUS -> "pcm_opus"
            AudioFormat.Encoding.PCM_32BIT_FLOAT -> "pcm_float"
        }
        return VolcFormatConfig(type = type, rate = sampleRateHz)
    }

    // ---- 接收循环 + adaptor ----

    /**
     * 握手阶段: 读 `session.incoming` 直到 `session.created`/`session.updated` 出现.
     * 帧解析走 [VolcWireProtocol.handleIncoming]; RealtimeEvent 的发射与 FC 拦截由
     * adaptor ([createVolcWireProtocol], 已通过 UNDISPATCHED 订阅 `wire.events`) 统一处理,
     * 此处不直接 emit RealtimeEvent 避免双发.
     */
    private suspend fun waitForSessionCreated(session: WebSocketSession, wire: VolcWireProtocol) {
        for (frame in session.incoming) {
            if (frame !is Frame.Text) continue
            val evt = wire.handleIncoming(frame.readText())
            when (evt.type) {
                "session.created" -> return
            }
        }
        error("Session create error.")
    }

    private fun startReadLoop(session: WebSocketSession, wire: VolcWireProtocol) {
        scope?.launch {
            for (frame in session.incoming) {
                if (frame !is Frame.Text) continue
                val text = frame.readText()
                wire.handleIncoming(text)
            }
            eventEmitter.emit(RealtimeEvent.Disconnected(null))
        }
    }

    private fun createVolcWireProtocol(session: WebSocketSession): VolcWireProtocol {
        val wire = VolcWireProtocol(session)
        scope?.launch(start = CoroutineStart.UNDISPATCHED) {
            wire.events.collect { evt ->
                if (evt.type == "response.function_call_arguments.done") {
                    handleFunctionCall(evt)
                    return@collect
                }
                VolcStreamDecoder.toRealtimeEvents(evt).forEach { eventEmitter.emit(it) }
            }
        }
        return wire
    }

    /**
     * FC 拦截: 解析 `response.function_call_arguments.done` 的 callId / name / arguments,
     * 查找本地 tool 执行, 然后通过 `conversation.item.create` 回传结果.
     * [CancellationException] 透传. 调用方须保证 `evt.type == "response.function_call_arguments.done"`.
     */
    private suspend fun handleFunctionCall(evt: VolcEvent) {
        val calls =
            Json.decodeFromJsonElement(ListSerializer(VolcFunctionCall.serializer()), evt.items!!)
        calls.forEach { call ->
            val callId = call.callId ?: "function_call.call_id is missing"
            val arguments: JsonElement = call.arguments
                ?.takeIf { it.isNotBlank() }
                ?.let { json.parseToJsonElement(it) }
                ?: JsonObject(emptyMap())
            val output = try {
                if (call.name == null) error("function_call.name missing")
                val tool = toolsByName[call.name] ?: error("tool not registered: ${call.name}")
                tool.execute(arguments)
            } catch (t: CancellationException) {
                throw t
            } catch (t: Throwable) {
                "工具调用失败: $call : ${t.message ?: t.toString()}"
            }
            sendToolResult(callId, output)
        }
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
        wire?.conversationItemCreate(listOf(item))
    }
}
