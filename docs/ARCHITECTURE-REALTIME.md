# 实时语音对话架构

## 1. 概述

Realtime 模块把"实时语音"切成三层，做到"换厂商不需要改业务"：

- **接口层** — 定义 S2S 会话抽象、音频采集/播放适配器接口
- **业务层** — 会话装配器 (RealtimeAppliance)、音频播放管理 (RealtimeSpeaker)、委派桥 (RealtimeDelegation)
- **厂商层** — 协议适配器 (RealtimeAdapter)、音频采集/播放实现

## 2. 模块结构

```
realtime:core               ← 核心：RealtimeSession、RealtimeAdapter、RealtimeAppliance、RealtimeDelegation
realtime:audio:android      ← Android 音频采集与播放
realtime:providers:volc     ← JVM 端火山引擎 S2S 协议实现
realtime:providers:volc-android ← Android 端火山引擎实现
```

## 3. 架构

```
      麦克风 (MicrophoneAdapter)
            │
            │ PCM 帧
            ▼
┌────────────────────────────────────────────┐
│              RealtimeAppliance             │
│   ┌──────────────┐  ┌──────────────────┐   │
│   │ RealtimeSes- │  │ RealtimeSpeaker  │   │
│   │ sion(WS)     │  │ (SpeakerAdapter) │   │
│   └──────┬───────┘  └───────▲──────────┘   │
│          │ events          │ audio         │
│          │                 │               │
│          ▼                 │               │
│   RealtimeAdapter  ───────▶│               │
│  (协议帧 ↔ 内部事件)        │               │
└────────────────────────────│───────────────┘
                             │
                  ┌──────────┴───────────┐
                  │ RealtimeDelegation?  │  ← 可选: 把 S2S 内部事件
                  │                     │     路由回 agent 推理
                  └─────────────────────┘
```

## 4. 核心组件

| 组件 | 职责 |
|------|------|
| `RealtimeSession` | WebSocket 会话抽象，管理连接、发送音频、取消响应 |
| `RealtimeAdapter` | 厂商协议适配器接口，将厂商协议帧统一为内部事件 |
| `RealtimeAppliance` | 开箱即用的会话装配器，串接麦克风/扬声器/会话/委派 |
| `RealtimeSpeaker` | 音频播放管理器，处理用户打断时的播放缓存 |
| `RealtimeDelegation` | S2S 与 Agent 之间的桥，支持实时语义介入 |
| `MicrophoneAdapter` | 麦克风采集适配器接口 |
| `SpeakerAdapter` | 扬声器播放适配器接口 |

### 4.1 RealtimeSession

```kotlin
public interface RealtimeSession : AutoCloseable {
    val inputAudioFormat: AudioFormat
    val outputAudioFormat: AudioFormat
    val events: Flow<RealtimeEvent>
    suspend fun connect(config: SessionConfig)
    suspend fun sendAudio(pcm: ByteArray)
    suspend fun commitAudio()
    suspend fun cancelResponse()
    suspend fun injectAndRespond(text: String)
}
```

**DefaultRealtimeSession** 内部组件：
- `RealtimeAdapter` — 协议适配器，负责帧转换
- `WebSocketSession` (Ktor) — 底层通信信道
- `Mutex` 保护 send 路径
- `MutableSharedFlow` — 事件流
- 启动时 launch 两个协程：接收帧 + 发送帧

### 4.2 RealtimeAdapter

```kotlin
public interface RealtimeAdapter {
    val inputAudioFormat: AudioFormat
    val outputAudioFormat: AudioFormat
    val events: Flow<RealtimeEvent>
    fun getAuthHeaders(config: SessionConfig): Map<String, String>
    fun registerTools(tools: List<Tool>)
    fun createSessionFrame(config: SessionConfig): ProtocolFrame
    fun sendAudioFrame(pcm: ByteArray): ProtocolFrame
    fun commitAudioFrame(): ProtocolFrame
    fun commitSpeechTextFrame(text: String): List<ProtocolFrame>
    fun cancelResponseFrame(): ProtocolFrame
    suspend fun handleIncomingFrame(frame: ProtocolFrame): List<ProtocolFrame>
}
```

### 4.3 RealtimeAppliance

```kotlin
public interface RealtimeAppliance {
    val delegation: RealtimeDelegation?
    val events: Flow<RealtimeEvent>
    suspend fun start()
    suspend fun close()
}
```

**DefaultRealtimeAppliance** 启动流程：
1. 创建 `CoroutineScope(SupervisorJob())`
2. `session.connect(config)` — 建立 WebSocket 连接
3. `speaker.start(format)` — 启动扬声器
4. `microphone.start(format)` — 启动麦克风采集
5. launch 两个协程：麦克风采集 → session.sendAudio + session events → speaker.observed + emit

### 4.4 RealtimeSpeaker

`RealtimeSpeaker` 包装 `SpeakerAdapter`，管理播放缓存：

- 用户打断时清空缓存（`drain()`）
- 使用 `Channel<ByteArray>` 缓冲音频帧
- 通过 `AtomicBoolean` 标记用户是否正在说话
- 用户说话时静音播放，说话结束后恢复播放

## 5. 事件模型

```kotlin
RealtimeEvent (sealed interface)
├── UserTranscriptStarted(itemId)     — 用户开始说话
├── UserTranscriptDelta(text)         — 用户语音识别增量
├── UserTranscriptCompleted(text)     — 用户语音识别完成
├── AssistantTextDelta(text)          — 模型文本增量
├── AssistantTextDone(text)           — 模型文本完成
├── AssistantAudioStarted             — 模型音频开始
├── AssistantAudioDelta(pcm)          — 模型音频增量
├── AssistantAudioDone                — 模型音频完成
├── ResponseDone                      — 响应完成
├── ResponseCanceled                  — 响应取消
├── Connected(sessionId)             — 连接建立
├── Disconnected(reason)             — 连接断开
└── Error(code, message, isFatal)    — 错误
```

## 6. RealtimeDelegation 委派

`RealtimeDelegation` 是 S2S 与 Agent 之间的桥，让"模型当前说的内容"和"用户当前听到的内容"可以被另一个 Agent 实时介入。

### 6.1 接口

```kotlin
public interface RealtimeDelegation {
    val classifier: IntentionClassifier?
    val capabilities: List<String>
    val replies: Flow<DelegationReply>
    suspend fun run(task: String)
}
```

### 6.2 意图分类

```kotlin
public interface IntentionClassifier {
    val timeout: Long
    suspend fun classify(asr: String, chatHistories: List<String>): Intention
}

public sealed interface Intention {
    data class Task(val ack: String, val content: String) : Intention
    data class Chat(val ack: String?) : Intention
}
```

### 6.3 两种分类策略

| 策略 | 适用场景 | 特点 |
|------|----------|------|
| **InnerClassifyStrategy** | 轻量级 | 内置关键词匹配，无外部依赖，`capabilities` 列表正向匹配 |
| **OuterClassifyStrategy** | 精确分类 | 基于 `IntentionClassifier` 接口，可由 LLM 或其他外部服务实现，支持 timeout |

### 6.4 委派流程

```
用户语音 → ASR Transcript → DelegationProcessor
  │
  ├─ classify(asr) → Intention
  │     ├─ Task(ack, content) → delegation.run(content) → DelegationReply
  │     └─ Chat(ack) → 不委派，直接透传 S2S
  │
  └─ 根据分类结果控制 S2S 响应
        ├─ Task → 取消当前 S2S 响应，等待 delegation 结果
        └─ Chat → 继续 S2S 正常对话
```

## 7. 音频处理

| 组件 | 技术栈 | 说明 |
|------|--------|------|
| `AndroidMicrophoneAdapter` | `AudioRecord` | 采集 PCM 16kHz 16bit |
| `AndroidSpeakerAdapter` | `AudioTrack` | 播放 PCM 音频 |
| `RealtimeSpeaker` | `Channel<ByteArray>` | 播放缓存管理，打断清空 |

## 8. SessionConfig

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

public sealed interface TurnDetection {
    data class ServerVad(val thresholdMs: Int = 500) : TurnDetection
    object Manual : TurnDetection
}
```

## 9. 厂商实现

- `realtime:providers:volc` — JVM 端火山引擎 S2S 协议实现，完整的 `RealtimeAdapter` 实现
- `realtime:providers:volc-android` — Android 端火山引擎实现，集成 `speechengine_tob` SDK，底部连接火山引擎实时语音识别与合成