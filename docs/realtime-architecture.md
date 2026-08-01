# RealtimeSession 架构设计

## 模块结构

```
realtime/
├── core/                          # 核心抽象 + 默认实现
│   ├── RealtimeSession.kt         # 公开 API 接口
│   ├── DefaultRealtimeSession.kt  # Ktor WebSocket 实现 + 工厂方法
│   ├── RealtimeAdapter.kt         # 内部协议适配接口 + ProtocolFrame
│   ├── RealtimeEvent.kt           # 事件 sealed interface
│   ├── SessionConfig.kt           # 会话配置 + Tool + TurnDetection
│   ├── RealtimeAppliance.kt       # 高层编排器接口
│   ├── DefaultRealtimeAppliance.kt # Ktor WebSocket 实现
│   ├── RealtimeDelegation.kt      # 委托协议接口 + DelegationProcessor
│   └── audio/
│       ├── AudioFormat.kt
│       ├── MicrophoneAdapter.kt
│       └── SpeakerAdapter.kt
├── providers/volc/                # 火山引擎 provider
│   ├── VolcRealtimeAdapter.kt     # Volc 协议适配器
│   └── VolcDtos.kt                # Volc 协议 DTO
├── providers/volc-android/        # 火山引擎 Android SDK 实现
│   └── VolcRealtimeAppliance.kt  # Volc SDK 封装 (需 speechengine_tob SDK)
└── audio/android/                 # Android 音频实现
    ├── AndroidMicrophoneAdapter.kt
    ├── AndroidSpeakerAdapter.kt
    └── AudioFormatExtensions.kt
```

---

## 1. ProtocolFrame

```kotlin
public data class ProtocolFrame(val payload: JsonObject)
```

- 纯数据类包装 `JsonObject`，**不标记 `@Serializable`**。
- payload 始终包含 `type` 字段（如 `"session.create"`）。
- 在 `DefaultRealtimeSession` 中直接以 `JsonObject.serializer()` 序列化/反序列化。

---

## 2. RealtimeSession — 公开 API

```kotlin
public interface RealtimeSession : AutoCloseable {
    public val inputAudioFormat: AudioFormat
    public val outputAudioFormat: AudioFormat
    public val events: Flow<RealtimeEvent>
    
    public suspend fun connect(config: SessionConfig)
    public override fun close()

    public suspend fun sendAudio(pcm: ByteArray)
    public suspend fun commitAudio()
    public suspend fun cancelResponse()
    public suspend fun injectAndRespond(text: String)
}
```

---

## 3. RealtimeAdapter — 内部协议适配

Provider 实现此接口以对接不同的 S2S 引擎。

```kotlin
public interface RealtimeAdapter {
    public val inputAudioFormat: AudioFormat
    public val outputAudioFormat: AudioFormat
    public val events: Flow<RealtimeEvent>

    public fun getAuthHeaders(config: SessionConfig): Map<String, String>
    public fun registerTools(tools: List<Tool>)

    public fun createSessionFrame(config: SessionConfig): ProtocolFrame
    public fun sendAudioFrame(pcm: ByteArray): ProtocolFrame
    public fun commitAudioFrame(): ProtocolFrame
    public fun commitSpeechTextFrame(text: String): List<ProtocolFrame>
    public fun cancelResponseFrame(): ProtocolFrame

    public suspend fun handleIncomingFrame(frame: ProtocolFrame): List<ProtocolFrame>
}
```

关键设计点：
- 帧构造方法均为 **非 suspend**（纯数据组装）；`registerTools` 也非 suspend。
- `handleIncomingFrame` 返回 `List<ProtocolFrame>` 代替回调——**回复帧作为返回值**，由 `DefaultRealtimeSession` 逐个发送，避免 adapter 持有 WebSocket 引用。
- `events: Flow<RealtimeEvent>` 由 adapter 内部维护 `MutableSharedFlow`。

---

## 4. DefaultRealtimeSession

```kotlin
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
            ws?.send(Frame.Text(json.encodeToString(JsonObject.serializer(), frame.payload)))
        }
    }

    private fun startReadLoop() {
        val wsLocal = ws ?: return
        scope?.launch {
            try {
                for (frame in wsLocal.incoming) {
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

public fun RealtimeSession(client: HttpClient, adapter: RealtimeAdapter): RealtimeSession
```

设计要点：
- **Factory 函数** `RealtimeSession(client, adapter)` 替代构造器，返回 `DefaultRealtimeSession` 实例。
- **`writeLock: Mutex`** 保证 WebSocket 发送不并发争抢。
- **`JsonObject.serializer()`** 直连序列化 payload，不走 `ProtocolFrame.serializer()`。
- **回复帧机制**：adapter 通过返回值返回回复帧，session 在 read loop 中 launch 发送。
- **`waitConnected()`** 等待 `Connected` 事件，期间若出现 `Error` 则抛出异常。
- **Disconnected 事件来源**：WebSocket 连接关闭时（for-loop 正常结束或网络错误），由 session 本身通过 `disconnectedEvent` 发射；adapter 的 Disconnected 事件在 `events` 流中被过滤掉，避免重复。
- **`try-finally` 保证 Disconnected 发送**：即使 `CancellationException` 触发，finally 块仍会执行。

---

## 5. SessionConfig

```kotlin
public data class SessionConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val instructions: String,
    val voice: String,
    val tools: List<Tool> = emptyList(),
    val turnDetection: TurnDetection = TurnDetection.ServerVad(),
)

public interface Tool {
    public val name: String
    public val description: String
    public val parametersSchema: JsonObject
    public suspend fun execute(arguments: JsonElement): String
}

public sealed interface TurnDetection {
    public data class ServerVad(val thresholdMs: Int = 500) : TurnDetection
    public data object Manual : TurnDetection
}
```

---

## 6. RealtimeEvent

```kotlin
public sealed interface RealtimeEvent {
    public data class UserTranscriptStarted(val itemId: String) : RealtimeEvent
    public data class UserTranscriptDelta(val text: String) : RealtimeEvent
    public data class UserTranscriptCompleted(val text: String) : RealtimeEvent

    public data class AssistantTextDelta(val text: String) : RealtimeEvent

    public object AssistantAudioStarted : RealtimeEvent
    public data class AssistantAudioDelta(val pcm: ByteArray) : RealtimeEvent
    public object AssistantAudioDone : RealtimeEvent

    public object ResponseDone : RealtimeEvent
    public object ResponseCanceled : RealtimeEvent

    public data class Connected(val sessionId: String) : RealtimeEvent
    public data class Disconnected(val reason: String?) : RealtimeEvent
    public data class Error(val code: String, val message: String, val isFatal: Boolean) : RealtimeEvent
}
```

---

## 7. RealtimeAppliance — 高层编排器

组装 `RealtimeSession`、`MicrophoneAdapter`、`SpeakerAdapter` 和可选的 `RealtimeDelegation`。

```kotlin
public interface RealtimeAppliance {
    public val delegation: RealtimeDelegation?
    public val events: Flow<RealtimeEvent>
    public suspend fun start()
    public suspend fun close()
}

public fun RealtimeAppliance(
    session: RealtimeSession,
    sessionConfig: SessionConfig,
    microphone: MicrophoneAdapter,
    speaker: SpeakerAdapter,
    delegation: RealtimeDelegation? = null,
): RealtimeAppliance = DefaultRealtimeAppliance(...)
```

### 设计要点

- **`start()` 幂等**：第二次调用直接返回。
- **指令注入**：delegation 非 null 时，将委派协议 + capabilities 追加到 `instructions`。
- **事件路由**：`process()` 将 `AssistantAudioDelta` 转发到 speaker；同时将事件传递给 `DelegationProcessor`。
- **`close()` 使用 `cancelAndJoin()`** 确保所有协程收尾。

### RealtimeDelegation 接口

```kotlin
public interface RealtimeDelegation {
    public val classifier: IntentionClassifier? get() = null
    public val capabilities: List<String>
    public val replies: Flow<DelegationReply>
    public suspend fun run(task: String)
}

public interface IntentionClassifier {
    public suspend fun classify(asr: String): Intention
}

public sealed interface Intention {
    public data class Delegated(val ack: String, val task: String) : Intention
    public data class Casual(val ack: String?) : Intention
}

public sealed interface DelegationReply {
    public data class Confirmation(val text: String) : DelegationReply
    public data class Success(val text: String) : DelegationReply
    public data class Failure(val message: String) : DelegationReply
}
```

### DelegationProcessor 内部类

```kotlin
internal class DelegationProcessor(
    private val delegation: RealtimeDelegation,
    private val scopeProvider: () -> CoroutineScope?,
    private val onReply: suspend (String) -> Unit,
    private val onReplacementAck: suspend (String) -> Unit,
)
```

DelegationProcessor 根据 `delegation.classifier` 是否存在选择 Strategy：

- **InnerClassifyStrategy**（无 classifier）：通过协议标记 `|` 检测委派，将 DELEGATION_PROTOCOL + capabilities 追加到 instructions。
- **OuterClassifyStrategy**（有 classifier）：由外部 LLM 分类器判断意图，分类器返回 `Intention.Delegated` 时触发 delegation，返回的 `ack` 用于 suppress TTS 并替换回复。

职责：
1. **`appendInstructions()`** — 委托给 Strategy。
2. **`start()`** — 订阅 `delegation.replies`，每条 reply 调用 `session.injectAndRespond()`。
3. **`process(event)`** — 委托给 Strategy 处理。InnerClassifyStrategy 侦听 `|` 标记；OuterClassifyStrategy 侦听分类结果并 suppress TTS。

---

## 8. Audio 接口

```kotlin
public data class AudioFormat(
    val sampleRateHz: Int,
    val encoding: Encoding,
) {
    public enum Encoding { PCM_16BIT, PCM_32BIT_FLOAT, PCM_OPUS }
}

public interface MicrophoneAdapter {
    public suspend fun start(format: AudioFormat)
    public fun capture(): Flow<ByteArray>
    public suspend fun close()
}

public interface SpeakerAdapter {
    public suspend fun start(format: AudioFormat)
    public suspend fun play(pcm: ByteArray)
    public suspend fun stopPlayback()
    public suspend fun close()
}
```

---

## 9. VolcRealtimeAdapter

### 协议参数

| 参数 | 值 |
|------|-----|
| inputAudioFormat | PCM_16BIT / 16kHz |
| outputAudioFormat | PCM_16BIT / 24kHz |
| Auth (单段 key) | `X-Api-Key` |
| Auth (两段 key) | `X-Api-App-ID` + `X-Api-Access-Key` |

### 帧构造

全部通过私有方法组装，统一使用 `buildFrame()` helper 自动注入 `type` 和 `event_id`：

```kotlin
private fun buildFrame(type: String, body: JsonObjectBuilder.() -> Unit): ProtocolFrame
```

支持的帧类型：
- `session.create` / `session.update` / `session.close`
- `input_audio_buffer.append` / `input_audio_buffer.commit`
- `response.cancel`
- `speech_text_buffer.append` / `speech_text_buffer.commit`（tts_prompt = "原文播报"）
- `speech_text_buffer.replacement.append` / `speech_text_buffer.replacement.commit`
- `conversation.item.create` / `conversation.item.update` / `conversation.item.retrieve` / `conversation.item.delete`

### 事件映射

```kotlin
private fun toRealtimeEvents(evt: VolcEvent): List<RealtimeEvent>
```

| Volc Event | RealtimeEvent |
|---|---|
| `session.created` | `Connected(sessionId)` |
| `session.updated` | (空) |
| `session.closed` | `Disconnected` |
| `conversation.item.input_audio_transcription.started` | `UserTranscriptStarted(itemId)` |
| `conversation.item.input_audio_transcription.delta` | `UserTranscriptDelta` |
| `conversation.item.input_audio_transcription.completed` | `UserTranscriptCompleted` |
| `conversation.item.input_audio_transcription.failed` | `Error` |
| `response.output_text.delta` | `AssistantTextDelta` |
| `response.output_text.done` / `.added` / `.retrieved` / `.updated` / `.deleted` | (空) |
| `response.output_audio.started` | `AssistantAudioStarted` |
| `response.output_audio.delta` | `AssistantAudioDelta`（base64 解码）|
| `response.output_audio.done` | `AssistantAudioDone` |
| `response.canceled` | `ResponseCanceled` |
| `response.done` | `ResponseDone` |
| `error` | `Error` |

### 工具调用（FC）

`response.function_call_arguments.done` — 被 `handleIncomingEvent` 优先拦截：
1. 解析 `items` 为 `List<VolcFunctionCall>`。
2. 遍历调用，从 `toolsByName` 查找已注册的 `Tool`，执行 `tool.execute(arguments)`。
3. 若 tool 未注册或执行异常，返回错误消息。
4. 返回 `conversation.item.create` 帧（role="tool"），由 DefaultRealtimeSession 发送回引擎。

---

## 10. VolcDtos

```kotlin
VolcEvent          — 通用入站事件（type, session, itemId, delta, text, items, error...）
VolcSession        — { id }
VolcError          — { code, message }
VolcFunctionCall   — { call_id, name, arguments }

VolcSessionConfig       — session.create/update 的 session 体
VolcAudioConfig         — { input, output }
VolcAudioSideConfig     — { format, voice }
VolcFormatConfig        — { type, rate }

VolcSessionExtensionConfig  — extension 配置（asr, tts, dialog）
VolcExtensionSide          — 单侧扩展（extra: JsonElement）
VolcExtensionDialog        — dialog 扩展（location, extra）

VolcConversationItem       — conversation.item.create 的 item 体
```

---

## 11. Volc 模块已删除的类

以下类在重构中删除，功能已迁移至 `VolcRealtimeAdapter`：

| 已删除文件 | 功能迁移至 |
|---|---|
| `VolcWireProtocol.kt` | `VolcRealtimeAdapter` 的帧构造方法 |
| `VolcRealtimeSession.kt` | `DefaultRealtimeSession` + `VolcRealtimeAdapter` |
| `VolcStreamDecoder.kt` | `VolcRealtimeAdapter.toRealtimeEvents()` |
| `VolcStreamDecoderTest.kt` | `VolcRealtimeAdapterTest` |

---

## 12. RealtimeAppliance 实现

`RealtimeAppliance` 是高层事件编排器接口，负责把 `RealtimeSession` 的事件流
转成统一的 `RealtimeEvent` 流，并可选地挂载委托协议处理器。

### 12.1 DefaultRealtimeAppliance

Ktor WebSocket 实现(internal)，通过顶层工厂函数构造:

```kotlin
public fun RealtimeAppliance(
    session: RealtimeSession,
    sessionConfig: SessionConfig,
    microphone: MicrophoneAdapter,
    speaker: SpeakerAdapter,
    delegation: RealtimeDelegation? = null,
): RealtimeAppliance
```

需要外部注入 `MicrophoneAdapter` / `SpeakerAdapter`，音频完全由调用方控制。

### 12.2 VolcRealtimeAppliance (Android 原生 SDK)

模块路径: `:realtime:providers:volc-android`

依赖: `com.bytedance.speechengine:speechengine_tob:0.0.15.0`

构造器: `VolcRealtimeAppliance(sessionConfig, delegation = null)`

音频由 SDK 自管，不暴露 `MicrophoneAdapter` / `SpeakerAdapter` 注入点。

**注意**: `speechengine_tob` SDK 需要手动配置。参见 `realtime/providers/volc_android_sdk.md` 获取 SDK 设置说明。
