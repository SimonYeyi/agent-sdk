# S2S × BossAgent 双层混合架构设计

**日期：** 2026-07-20
**作者：** Claude
**状态：** 待用户审阅
**关联文档：**
- `2026-07-13-team-module-design.md`（`:team` 模块设计，含 BossAgent）
- `2026-07-15-team-boss-and-agent-impl.md`（BossAgent 实施记录）
- 火山引擎「端到端实时语音-全双工版本」API 文档 + `python3.7_duplex_demo` 参考实现

---

## 1. 背景与目标

### 1.1 背景

项目已具备 `:team` BossAgent —— 一个负责长期任务拆解、派发、监督的下属智能体。其 `run()` 接受用户文本输入，返回 `Flow<AgentEvent>` 流；终态事件为 `Final`（含 `result.message` 文本）或 `Failed`。

但当前所有调用方（`:demo` 智能家居、`:gateway` 飞书/Telegram/微信）都是文本通道。用户在语音场景下需要：

1. 实时语音输入/输出（不能等 LLM 出整段文本再 TTS）
2. 对话驱动 BossAgent 干活（语音 → ASR → 委派 → 任务执行 → 口语化结果回传）
3. 普通闲聊由 S2S 模型自己处理，不走 BossAgent

### 1.2 目标

- 接入 S2S（speech-to-speech）模型，把 `:team` BossAgent 升级为语音可达
- 第一家 provider：火山引擎豆包「端到端实时语音-全双工版本」
- 架构上预留 provider 扩展位（OpenAI Realtime、未来其它）
- 纯 Kotlin/JVM 实现，不引入 Android-only 依赖；Android 直接调用、JVM 后台也可部署
- 阶段一成功标准：Android 端到端 demo，语音闲聊 + 语音委派 BossAgent 各跑通一次

### 1.3 非目标

- 不实现多轮对话记忆的 S2S 侧管理（由 provider 模型自身上下文处理）
- 不实现 provider 间的无缝切换 / 路由（手动选 provider）
- 不实现语音生物特征、声纹、个性化音色克隆
- 不实现端到端加密通道、双向认证（依赖 provider 自身的鉴权）
- 不实现实时翻译 / 多语种 ASR 切换（首版中文为主）

---

## 2. 整体架构

### 2.1 双层混合（Hybrid Two-Layer）

```
┌──────────────────────────────────────────────────────────────┐
│  Android App / JVM 后台（调用方）                              │
│   - 手动开启全双工语音模式                                     │
│   - MicrophoneAdapter：麦克风 PCM 采集                        │
│   - SpeakerAdapter：TTS PCM 播放                              │
└───────────────┬──────────────────────────┬───────────────────┘
                │ mic PCM ↓   TTS PCM ↑     │ Boss 事件流
                ▼                            ▼
┌──────────────────────────────┐   ┌──────────────────────────┐
│  :realtime:core（纯 Kotlin）  │   │  :team BossAgent（已存在）│
│  ┌────────────────────────┐  │   │  run / continuations     │
│  │ RealtimeSession 抽象    │  │   │  / tasksStates           │
│  ├────────────────────────┤  │   └────────────┬─────────────┘
│  │ AssistantAudioGate     │  │                │
│  │   （标记拦截/音频闸门） │  │  委派原文 →    │
│  ├────────────────────────┤  │                │ 结果事件
│  │ BossConversationBridge │──┼────────────────┘
│  │   （决定论协调器）      │  │
│  └────────────────────────┘  │
└──────────────────────────────┘
                ▲
                │ 实现 RealtimeSession 接口
┌──────────────────────────────────────────────┐
│  :realtime:providers:volc（火山豆包全双工适配）│
│   - Ktor WS client → wss://.../duplex/realtime│
│   - JSON 事件编解码                            │
└──────────────────────────────────────────────┘
```

**关键设计原则：**

1. **桥接层是决定论的 Kotlin 代码，不含任何模型**。它订阅 S2S 事件、协调 BossAgent.run()、把结果回注 S2S；唯一的"判定"是读取模型通过 `system_prompt` 标记协议发出的信号。
2. **S2S 模型自己处理闲聊，也自己决定要不要委派**。桥接层不为 S2S 拆任务、不替 S2S 分类 —— 由 S2S 通过 `<|DELEGATE_TO_BOSS|>` 标记告知。模型比桥接层的启发式更准确。
3. **BossAgent 收到的是原始 ASR 文本**，由 Boss 自主决定如何执行（拆任务、调下属、读记忆等）。
4. **Boss 结果原文回传 S2S**，由 S2S 在空闲时口语化转述给用户。
5. **调用方持有音频适配器**，`:realtime:core` 不直接依赖任何音频 API。

### 2.2 为什么不用原生 Function Calling

火山豆包全双工版本原生支持 `tools` 数组 + `function_call`。但本设计统一采用 `system_prompt` 标记协议，理由：

| 维度 | 原生 FC | 标记协议（本设计采用） |
|------|--------|----------------------|
| Provider 兼容 | 仅支持 FC 的 provider 才能用 | 所有文本型 S2S provider 都可接入（未来 OpenAI Realtime 也可直接复用） |
| 抽象复杂度 | 每个 provider 的 tools schema 各异 | 一段 system_prompt 字符串 + 一个简单前缀匹配 |
| TTS 泄漏风险 | FC 由 provider 在 response 外触发，无 TTS 风险 | **必须配 AudioGate 拦截标记，否则模型自言自语念出标记** |
| 调试可观测性 | 需查看 provider 内部 events | 直接看首句文本即可定位 |

代价：必须实现 AudioGate 拦截标记音。详见 §6。

**决策权归属：模型标记是单一权威。** 阶段一不在桥接层做"是否委派"的二次判定 —— S2S 模型判断比关键词启发式更准确。桥接层只识别模型输出的 `<|DELEGATE_TO_BOSS|>` 标记，并据此触发委派流程。阶段二如需进一步优化（如节省模型 token 的预过滤），再考虑加独立的启发式。

---

## 3. 模块划分

所有 S2S 相关代码集中在 `realtime/` 目录下，按职责拆为三个 Gradle 子模块：

```
realtime/
├── core/                 # → Gradle 模块 :realtime:core
├── audio/
│   └── android/          # → Gradle 模块 :realtime:audio:android
└── providers/
    └── volc/             # → Gradle 模块 :realtime:providers:volc
```

| 模块 | 角色 | 依赖 | 状态 |
|------|------|------|------|
| `:realtime:core` | provider 非依赖的抽象层：`RealtimeSession` 接口、`MicrophoneAdapter` / `SpeakerAdapter` 接口、`AssistantAudioGate`、`BossConversationBridge` | `:agent`, `:team`, coroutines, serialization | 新增 |
| `:realtime:audio:android` | Android 平台音频实现：`AndroidMicrophoneAdapter`（AudioRecord）/ `AndroidSpeakerAdapter`（AudioTrack） | `:realtime:core`, Android SDK | 新增 |
| `:realtime:providers:volc` | 火山豆包全双工 WS 实现（`RealtimeSession` 的 provider 实现） | `:realtime:core`, `ktor-client-websockets` | 新增 |
| `:demo`（扩展） | Android 端到端 demo，新增 S2S 入口界面；只负责组装桥接层与各模块，不实现 adapter | `:realtime:core`, `:realtime:audio:android`, `:realtime:providers:volc`, `:team`, Android SDK | 扩展 |
| `:realtime:audio:jvm`（可选） | JVM 后台部署的 `JvmMicrophoneAdapter` / `JvmSpeakerAdapter`（javax.sound.sampled） | `:realtime:core` | 阶段二 |

**模块依赖方向：**
```
:realtime:providers:volc → :realtime:core → :team → :agent
:realtime:audio:android  → :realtime:core
:demo (Android)           → :realtime:core, :realtime:audio:android, :realtime:providers:volc, :team, :agent
```

`:realtime:core` 不依赖任何 Android / 平台 API，可被纯 JVM 模块使用。`:realtime:audio:*` 子目录按 platform 组织音频实现，未来加 `:realtime:audio:jvm` / `:realtime:audio:ios` 不影响其他模块。`:realtime:providers:*` 用于扩展更多 provider。

---

## 4. 核心接口设计

### 4.1 音频格式与适配器

```kotlin
package io.github.yeyi.agent.realtime.audio

public data class AudioFormat(
    val sampleRateHz: Int,         // 例：输入 16000 / 输出 24000
    val channels: Int,             // 1 = mono
    val sampleBits: Int,           // 16
    val encoding: Encoding,        // PCM_SIGNED_LE / PCM_OPUS / PCM_FLOAT_LE
) {
    public enum class Encoding { PCM_SIGNED_LE, PCM_OPUS, PCM_FLOAT_LE }
}

/** 麦克风适配器 — 只负责采集. */
public interface MicrophoneAdapter : AutoCloseable {
    public val inputFormat: AudioFormat
    public fun capture(): Flow<ByteArray>
    public suspend fun start()
    override suspend fun close()
}

/** 扬声器适配器 — 只负责播放. */
public interface SpeakerAdapter : AutoCloseable {
    public val outputFormat: AudioFormat
    public suspend fun play(pcm: ByteArray)
    public suspend fun stopPlayback()
    public suspend fun start()
    override suspend fun close()
}
```

**拆分依据：** 生命周期、权限、测试场景三者独立。Android 上 `RECORD_AUDIO` 权限只跟麦走；JVM 上 `TargetDataLine` 与 `SourceDataLine` 资源独立；测试时可单独 mock 其中一个。

### 4.2 RealtimeSession 与事件

```kotlin
package io.github.yeyi.agent.realtime

public data class SessionConfig(
    val apiKey: String,
    val endpoint: String,                    // 例："wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue"
    val model: String,                       // 例："1.2.6.0"
    val instructions: String,                // 含标记协议的 system prompt
    val voice: String,                       // TTS 音色
    val inputFormat: AudioFormat,
    val outputFormat: AudioFormat,
    val tools: List<io.github.yeyi.agent.tool.Tool> = emptyList(),  // :agent 的 Tool 接口; 各 provider 内部转 FC schema
    val turnDetection: TurnDetection = TurnDetection.ServerVad(),
)

public sealed interface TurnDetection {
    public data class Silence(val thresholdMs: Int = 600) : TurnDetection
    public data class ServerVad(val threshold: Float = 0.5f) : TurnDetection
    public data object Manual : TurnDetection
}

public interface RealtimeSession : AutoCloseable {
    public suspend fun connect(config: SessionConfig)
    public override fun close()

    public suspend fun sendAudio(pcm: ByteArray)
    public suspend fun commitAudio()         // 仅 Manual turn detection 有意义
    public suspend fun cancelResponse()      // VAD 误启动 / 委派检测时中断当前 turn
    public suspend fun injectAndRespond(text: String)  // Boss 结果回注

    public val events: Flow<RealtimeEvent>
}

public sealed interface RealtimeEvent {
    // ASR 结果
    public data class UserTranscriptDelta(val text: String) : RealtimeEvent
    public data class UserTranscriptCompleted(val text: String) : RealtimeEvent

    // Assistant 文本（标记检测用）
    public data class AssistantTextDelta(val text: String) : RealtimeEvent

    // Assistant 音频
    public data class AssistantAudioStarted(val itemId: String) : RealtimeEvent
    public data class AssistantAudioDelta(val itemId: String, val pcm: ByteArray) : RealtimeEvent
    public data class AssistantAudioDone(val itemId: String) : RealtimeEvent

    // Response 边界
    public data class ResponseDone(val responseId: String, val status: ResponseStatus) : RealtimeEvent

    // 连接状态
    public data class Connected(val sessionId: String) : RealtimeEvent
    public data class Disconnected(val reason: String?) : RealtimeEvent
    public data class Error(val code: String, val message: String, val isFatal: Boolean) : RealtimeEvent
}

public enum class ResponseStatus { COMPLETED, CANCELED, FAILED, INCOMPLETE }
```

**设计约束：**
- `RealtimeEvent` 是核心抽象，**不携带 provider 私有字段**（如 Volcengine 的 `event_id`、时间戳等）
- provider 实现在内部把 provider 事件 → `RealtimeEvent` 映射
- 多消费者友好（SharedFlow，调用方与桥接层可分别订阅）

### 4.3 Volcengine 事件映射表

| `RealtimeEvent`（core）       | 火山豆包全双工事件                                            |
|------------------------------|--------------------------------------------------------------|
| `Connected`                  | 握手成功（HTTP upgrade 200 后）                                |
| `UserTranscriptDelta`        | `conversation.item.input_audio_transcription.delta`           |
| `UserTranscriptCompleted`    | `conversation.item.input_audio_transcription.completed`       |
| `AssistantTextDelta`         | `response.output_text.delta`                                  |
| `AssistantAudioStarted`      | `response.output_audio.started`                               |
| `AssistantAudioDelta`        | `response.output_audio.delta`（base64 → ByteArray）            |
| `AssistantAudioDone`         | `response.output_audio.done`                                  |
| `ResponseDone`               | `response.done`                                               |
| `Disconnected`               | WS close 帧 / `error` 致命事件                                 |
| `Error`                      | `error` 事件（非致命）                                         |

**协议交互：**

| core 方法                | 火山事件序列                                                    |
|--------------------------|----------------------------------------------------------------|
| `connect(config)`        | WS upgrade → `session.create` → `session.update`（按需）        |
| `sendAudio(pcm)`         | `input_audio_buffer.append`（base64 编码）                     |
| `commitAudio()`          | `input_audio_buffer.commit`                                    |
| `cancelResponse()`       | `response.cancel`                                              |
| `injectAndRespond(text)` | `conversation.item.create`（role=assistant, text）→ `response.create` |
| `close()`                | `session.close` → WS close                                     |

---

## 5. 标记协议（system_prompt）

### 5.1 system_prompt 模板

```
你是一个智能助手. 区分以下两种情况:

1. 闲聊（问候 / 聊天 / 知识问答 / 一般咨询）: 直接用自然口语回答.

2. 需要执行任务（操作设备 / 调用服务 / 多步执行 / 调用下属智能体）:
   在 assistant 文本的第一句**必须**以 `<|DELEGATE_TO_BOSS|>` 开头,
   后接空行再接你对用户的简短确认（如"好的，我让 Boss 处理一下"）.

   这个标记是内部路由信号, **绝对不能**在 TTS 中读出来 —
   听到这个标记就立刻停止当前 turn 的音频.
```

### 5.2 为什么用单标记而非嵌套

考虑过的方案 A：`<tool_call>delegate:boss|user_text</tool_call>` —— 易与模型自带的 function call 风格混淆。

方案 B（采用）：`<|DELEGATE_TO_BOSS|>` 单标记开头 + 我们已在桥接层持有原始 ASR 文本（来自 `UserTranscriptCompleted`）。标记只承担"是不是要委派"的二值信号，原始 ASR 文本无需在标记内重复。

**优点：** 标记简短、首句即可检测、ASR 文本来源唯一（不会出现标记内文本与 ASR 文本不一致的边界 case）。

---

## 6. AssistantAudioGate（音频闸门）

### 6.1 状态机

```
                 ┌──────────┐
                 │ BUFFERING │  ← turn 开始, 缓存所有音频 chunk
                 └────┬─────┘
                      │ 首个 AssistantTextDelta 到达
        ┌─────────────┴─────────────┐
        ▼                           ▼
 文本以 MARKER_PREFIX 开头       文本不包含 MARKER_PREFIX
        │                           │
        ▼                           ▼
   ┌─────────┐                ┌────────────┐
   │ DROPPING│                │ PASSTHROUGH│
   └─────────┘                └────────────┘
   丢弃所有后续音频             flush buffer → SpeakerAdapter.play
   通知 Bridge 委派             后续音频直接放行
```

### 6.2 接口（桥接层内部，不暴露 public API）

```kotlin
internal class AssistantAudioGate(
    private val speaker: SpeakerAdapter,
    private val onDelegate: (asrText: String) -> Unit,
) {
    private enum class Mode { BUFFERING, PASSTHROUGH, DROPPING }
    private var mode = Mode.BUFFERING
    private val buffer = mutableListOf<ByteArray>()
    private var pendingAsrText: String? = null

    /** UserTranscriptCompleted 时调用, 保存本轮 ASR 原文. */
    fun onUserTranscriptCompleted(text: String) {
        pendingAsrText = text
    }

    /** AssistantTextDelta 时调用. 命中标记时触发 onDelegate 回调. */
    fun onTextDelta(text: String) {
        if (mode == Mode.DROPPING) return
        if (text.startsWith(MARKER_PREFIX)) {
            mode = Mode.DROPPING
            val asr = pendingAsrText ?: error("ASR text missing")
            onDelegate(asr)
        } else {
            mode = Mode.PASSTHROUGH
            buffer.forEach { speaker.play(it) }
            buffer.clear()
        }
    }

    fun onAudioDelta(pcm: ByteArray) = when (mode) {
        Mode.BUFFERING -> buffer.add(pcm).also { /* 不播放 */ }
        Mode.PASSTHROUGH -> speaker.play(pcm)
        Mode.DROPPING -> Unit  // 丢弃
    }

    fun onTurnEnd() {
        mode = Mode.BUFFERING
        buffer.clear()
        pendingAsrText = null
    }

    companion object {
        const val MARKER_PREFIX = "<|DELEGATE_TO_BOSS|>"
    }
}
```

**信号机制：** 桥接层仅通过 `onDelegate` 回调得知"模型已表达委派意图"。AudioGate 内部不直接调用 session 或 boss —— 状态切换与回调触发都集中在这里，桥接层只需要响应回调。

**Chunk 边界假设：** 阶段一假设 provider 在首个 `AssistantTextDelta` 中完整输出 `<|DELEGATE_TO_BOSS|>` 标记。火山豆包全双工版本的实测 delta 大小通常 ≥20 字符，标记长度 20 字符，绝大多数情况落在单个 chunk 内。如果未来发现 chunk 切碎，需引入文本累积 buffer（参照 §12.1）。

### 6.3 为什么必须 buffer 音频而非"先播放后回滚"

全双工 S2S 中，文本 delta 与音频 delta 几乎同时到达。AudioTrack 已播放的音频无法回滚 —— 用户会先听到"好的我让 Boss 处理一下"然后才听到切换。所以必须在文本决策之前 **先 buffer 所有音频**，等首句文本判定后再决定播放还是丢弃。

代价：首句音频有约 200-500ms 缓冲延迟，可接受。

---

## 7. BossConversationBridge（决定论协调器）

### 7.1 状态机

```
                     ┌───────────────────────────────────────────┐
                     │                  Idle                     │
                     │ 麦克风采集中, 等待用户说完                    │
                     └───────────────┬───────────────────────────┘
                                     │ UserTranscriptCompleted
                                     │
                                     ▼
                     ┌───────────────────────────────────────────┐
                     │  等待 AssistantTextDelta 到达              │
                     │  AudioGate 检查首句文本                     │
                     └───────────────┬───────────────────────────┘
                                     │ 首句以 <|DELEGATE_TO_BOSS|> 开头
                                     │ → AudioGate mode=DROPPING
                                     │ → Bridge 收到 onDelegate(asrText)
                                     ▼
                     ┌───────────────────────────────────────────┐
                     │   session.cancelResponse()                 │
                     │   boss.run(asrText).collect { ... }       │
                     └───────────────┬───────────────────────────┘
                                     │ Final(text) or Failed(cause)
                                     ▼
                     ┌───────────────────────────────────────────┐
                     │   等待 ResponseDone (S2S 进入 idle)        │
                     └───────────────┬───────────────────────────┘
                                     │ S2sIdle
                                     ▼
                     ┌───────────────────────────────────────────┐
                     │   injectAndRespond(text)                   │
                     │   (S2S 在 idle 时自然口语化回放)            │
                     └───────────────┬───────────────────────────┘
                                     │
                                     ▼
                                  Idle
```

**`UserTranscriptCompleted` 本身不触发任何动作**，只是缓存 ASR 原文等模型响应。委派的唯一触发源是模型输出 `<|DELEGATE_TO_BOSS|>` 标记。

### 7.2 接口

```kotlin
public class BossConversationBridge internal constructor(
    private val session: RealtimeSession,
    private val mic: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    private val boss: BossAgent,
    private val config: BridgeConfig = BridgeConfig(),
    private val scope: CoroutineScope,
) : AutoCloseable {

    /** 启动桥接: 连接 S2S、订阅事件流、启动麦克风. */
    public suspend fun start()

    /** 关闭: 取消 scope、关麦克风、关扬声器、断 S2S. */
    public override fun close()
}
```

**决策权归属：** 桥接层不做任何"是否委派"的判定。模型通过 `<|DELEGATE_TO_BOSS|>` 标记表达意图，桥接层只是标记的搬运工和执行者。阶段一不提供可选的启发式 delegate 函数 —— 避免和模型决策冲突。

### 7.3 Boss 结果回注协议

```
1. S2S 当前 turn 完成 (ResponseDone.status = COMPLETED 或 CANCELED)
2. Bridge 调用 session.injectAndRespond("Boss 任务完成, 结果: ${finalText}")
3. S2S 处理该消息, 触发新 response, TTS 口语化播报
4. AudioGate 正常放行 (无标记)
```

**为什么不在 Boss 完成瞬间立刻 inject：**
- S2S 当前 turn 可能正在播放用户确认语音（"好的我让 Boss 处理一下"），此时注入会打断
- 等 ResponseDone 之后注入，S2S 在 idle 状态自然衔接

**失败回注：** Boss 抛 `Failed(cause)` 时，固定字符串 `"抱歉, 任务执行失败: ${cause.message ?: "未知错误"}"`。

---

## 8. 数据流（典型场景）

### 8.1 场景 A：闲聊

```
1. 用户说话 → MicrophoneAdapter.capture() 流 → session.sendAudio(pcm)
2. S2S: ASR → UserTranscriptCompleted("今天天气真好")
3. AudioGate.onUserTranscriptCompleted 缓存 ASR 原文
4. S2S: 输出首句文本 "是的, 阳光明媚"（不带标记）
5. AudioGate: 首句无标记 → flush buffer → mode=PASSTHROUGH → 后续音频直通
6. 用户听到完整回复
```

### 8.2 场景 B：任务委派

```
1. 用户说话 → session.sendAudio(pcm) → ASR → UserTranscriptCompleted("帮我把客厅灯调暗到 30%")
2. AudioGate.onUserTranscriptCompleted 缓存 ASR 原文
3. S2S: 开始响应 → AssistantTextDelta("<|DELEGATE_TO_BOSS|>") 到达
4. AudioGate: 检测到标记 → mode=DROPPING → 触发 onDelegate(asrText="帮我把客厅灯调暗到 30%")
5. Bridge: session.cancelResponse() 取消当前 turn → boss.run("帮我把客厅灯调暗到 30%")
6. Boss: 拆任务 → 派发下属 → 收集结果 → emit Final(result.message.content="已把客厅灯调到 30%")
7. Bridge: 收集到 Final, 等待 session.events 中 ResponseDone (S2S cancel 已完成)
8. Bridge: session.injectAndRespond("Boss 任务完成, 结果: 已把客厅灯调到 30%")
9. S2S: 自然口语化播报 "客厅灯已经调到 30% 了, 你看合适吗"
10. AudioGate: 文本无标记 → 直通播放
11. 用户听到结果
```

---

## 9. 错误处理

| 错误源 | 处理策略 |
|--------|---------|
| WS 断开 (Disconnected) | 指数退避自动重连（1s / 2s / 4s，最多 3 次），失败后通知调用方 |
| ASR 失败 (Error.code 存在) | 1 次重试，仍失败则 `injectAndRespond("我没听清, 请再说一遍")` |
| BossAgent Failed | 注入固定回执字符串，不重试 |
| AudioGate 状态错乱（漏掉 ResponseDone） | 5s 超时强制 reset；日志记录 |
| Provider 返回未知事件 | 忽略并 log（不中断主流程） |
| 麦克风权限被拒 | `MicrophoneAdapter.start()` 抛异常，调用方上抛 UI 层处理 |

**`BridgeConfig`：**
```kotlin
public data class BridgeConfig(
    val reconnectMaxAttempts: Int = 3,
    val reconnectBackoffMs: () -> Int = { attempt -> 1000 shl (attempt - 1) },
    val bossResultTimeoutMs: Long = 60_000,
    val audioGateResetTimeoutMs: Long = 5_000,
)
```

---

## 10. 测试策略

### 10.1 单元测试（`:realtime:core`）

- **`AssistantAudioGateTest`**：
  - 输入纯文本 → BUFFERING → flush → PASSTHROUGH；`onDelegate` 不触发
  - 输入带标记文本 → DROPPING + `onDelegate` 触发，传入正确 ASR 原文
  - 标记检测后再来的 delta 被忽略
  - buffer 在 turn 结束时清空
  - pendingAsrText 缺失时抛 `IllegalStateException`
- **`BossConversationBridgeTest`**：
  - 用 fake `RealtimeSession` / fake `MicrophoneAdapter` / fake `SpeakerAdapter`
  - 验证场景 A/B 各路径
  - 验证 Boss 失败 → 失败回注
  - 验证 S2S 断开重连
  - 验证并发 user round（Boss 运行中又来一句）

### 10.2 provider 测试（`:realtime:providers:volc`）

- 用 Ktor `MockEngine` 模拟 WS 升级和事件帧
- 验证 JSON ↔ `RealtimeEvent` 双向转换覆盖所有事件类型
- 验证 base64 PCM 编解码
- 验证 write lock 序列化（并发 `sendAudio` + `injectAndRespond`）
- 不做端到端（需真实 API key）

### 10.3 集成测试（`:demo`）

- Android `androidTest`：构造完整 demo，但用 mock session 替换真实 provider
- 验证 `AudioRecord/AudioTrack` 与 mock session 的连接
- 真机/模拟器手工 smoke：闲聊 + 委派各一次
- 真机语音测试 CI 范围外（依赖人工）

### 10.4 回归验证

- `:team` 既有测试保持通过（Bridge 只新增，不改 BossAgent 接口）
- `:agent` 既有测试保持通过

---

## 11. 阶段一交付清单

| 交付物 | 位置 |
|--------|------|
| `:realtime:core` 模块（含 core 接口 + AudioGate + Bridge） | `realtime/core/` |
| `:realtime:audio:android` 模块（含 `AndroidMicrophoneAdapter` / `AndroidSpeakerAdapter`） | `realtime/audio/android/` |
| `:realtime:providers:volc` 模块 | `realtime/providers/volc/` |
| Android demo 入口（只组装，不实现 adapter） | `demo/src/main/.../s2s/` |
| `MicrophoneAdapter` / `SpeakerAdapter` / `RealtimeSession` 单测 | 各模块 `src/test/` |
| `BossConversationBridge` 集成测试（fake session） | `realtime/core/src/test/` |
| `python3.7_duplex_demo` 行为对齐回归 | 手动 smoke |
| 配置文档（API key 申请、endpoint、model、voice） | `docs/realtime-volc-setup.md`（可选） |

**阶段一验收标准：**
1. Android demo 启动后手动开启全双工
2. 用户说"你好" → S2S 自然口语回应（音频）
3. 用户说"帮我把客厅灯调暗到 30%" → S2S 标记触发 → Boss 委派 → 任务执行 → 结果回传
4. 全过程无 TTS 念出 `<|DELEGATE_TO_BOSS|>` 标记
5. 单测覆盖率 ≥ 70% on `:realtime:core`

---

## 12. 开放问题与后续

### 12.1 阶段一可暂缓的问题

| 问题 | 暂缓理由 | 后续方案 |
|------|---------|---------|
| Boss 运行中又来一句的优先级策略 | 阶段一用 Boss.run() 串行 | 阶段二：支持取消当前 Boss round |
| 多语种 ASR / TTS 切换 | 阶段一中英文为主 | 阶段二：动态 system prompt |
| VAD vs 手动切话的选择策略 | 阶段一 Manual 模式（Android demo） | 阶段二：抽象 `TurnDetection` 切换 |
| S2S 上下文超长时压缩 | provider 自身处理 | 阶段二：监测 usage 主动 reset |
| AudioGate buffer 上限 | 当前实现可无限累积 | 阶段二：设上限，超限强制直通 + log |
| 启发式 pre-filter（节省模型 token） | 模型标记已足够准确 | 阶段二：在 ASR 后加粗粒度启发式 |
| 模型标记跨 chunk 边界 | 阶段一假设首 chunk 即含完整 `<|DELEGATE_TO_BOSS|>` | 阶段二：累积文本直到出现判定字符 |

### 12.2 后续模块候选

- `:realtime:audio:jvm`：javax.sound 实现的后台部署版
- `:realtime:providers:openai`：OpenAI Realtime API provider
- `:realtime:tools:recorder`：本地录音回放调试工具

---

## 13. 决策记录

| 决策 | 备选 | 选定 | 理由 |
|------|------|------|------|
| 委派机制 | 原生 FC / system_prompt 标记 | 标记协议 | 跨 provider 一致，需 AudioGate 防泄漏 |
| 音频适配粒度 | 单一 AudioAdapter / 拆 Mic + Speaker | 拆开 | 生命周期、权限、测试独立性 |
| 桥接层决策权 | 含启发式 delegate / 仅识别模型标记 | 仅识别模型标记 | 模型比启发式更准；避免两路决策冲突 |
| Boss 结果回传时机 | 立即 / S2S idle 后 | idle 后 | 避免打断 S2S 当前 turn |
| AudioGate 缓冲策略 | 不缓冲（先播放后回滚）/ 缓冲首句音频 | 缓冲首句音频 | 全双工场景音频不可回滚 |
| 第一家 provider | 火山豆包 / OpenAI Realtime | 火山豆包 | 用户指定；API 文档 + Python demo 齐全 |
| 模块结构 | 单模块 / 拆 core + provider + demo | 拆三个 | 沿用 `:providers:xxx` 既有模式 |