# RealtimeAppliance 接口化与 Volc 端实现

> Date: 2026-07-28
> Status: Draft
> Author: Claude (brainstorming with user)

## 1. 目标与非目标

### 目标

1. 把 `RealtimeAppliance` 提升为接口,让 `DefaultRealtimeAppliance`(Ktor WebSocket + 注入式 `MicrophoneAdapter`/`SpeakerAdapter`)与 `VolcRealtimeAppliance`(原生 SDK,自带音频设备)共用同一份事件编排 + 委托协议逻辑。
2. 抽出 `DelegationHandler` 为跨模块可复用的处理器,`Default` 与 `Volc` 两个实现都通过同一个类执行"委派 marker 协议"(`|` 前缀检测、`pendingAsr` 跟踪、`appendInstructions` 协议注入、`delegation.replies` 收集)。
3. 在新 Android 模块 `:realtime:providers:volc-android` 中提供 `VolcRealtimeAppliance`,基于 `com.bytedance.speechengine:speechengine_tob:0.0.15.0` 实现。
4. 不影响现有 demo 与 `DefaultRealtimeAppliance` 的使用方式(原有 demo 调用方只改类名)。

### 非目标

1. 不在 `RealtimeAppliance` 接口上声明音频设备相关参数 — 各实现自行管理音频输入/输出。
2. 不为 `VolcRealtimeAppliance` 提供音频旁路/录音回调等 V2 能力;V1 SDK 自播自采,事件流只透传 `AssistantAudioDelta` 用于上层 UI 动效,不消费 PCM 字节。
3. 不改动 `:realtime:providers:volc` 现有纯 JVM 模块(它继续承载 `VolcRealtimeAdapter`,供其他 WebSocket 场景复用)。
4. 不动 `docs/realtime-architecture.md` 主文档;本 spec 作为补充章节,只新增"Volc 原生模式"小节。
5. 不为 `RealtimeAppliance` 引入 DI/factory;业务方按需直接 `new DefaultRealtimeAppliance(...)` 或 `new VolcRealtimeAppliance(...)`。

---

## 2. 当前状态

`realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt` 包含 4 个 public 符号,揉在一个文件里:

| 符号 | 角色 |
|---|---|
| `RealtimeAppliance` | **concrete class** — Ktor 事件编排 + 音频路由 + 委托 |
| `RealtimeDelegation` | interface — 委托协议 |
| `DelegationReply` | sealed interface — `Confirmation/Success/Failure` |
| `DelegationHandler` | internal class — 委托 marker 协议实现 |

现有 demo(`demo/src/main/kotlin/io/github/yeyi/agent/demo/s2s/S2sViewModel.kt:56`)直接 `new RealtimeAppliance(session, sessionConfig, mic, speaker, delegation)`。

`RealtimeApplianceTest.kt` 现有 11 个测试覆盖:闲聊路径直放、用户打断暂停播放、TTS 期间 barge-in 丢弃尾段、委托路径触发、委托 reply 顺序注入、mic 转发 PCM、`start()` 幂等、`close()` 后重连、双重 `close()`、空委托时音频直通。

---

## 3. 设计

### 3.1 `RealtimeAppliance` 接口

```kotlin
// realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt
package io.github.yeyi.agent.realtime

import kotlinx.coroutines.flow.Flow

public interface RealtimeAppliance {
    public val delegation: RealtimeDelegation?
    public val events: Flow<RealtimeEvent>
    public suspend fun start()
    public suspend fun close()
}
```

- **接口不暴露音频参数** — 音频是各实现私有关注点(SDK 自管 vs. 外部注入)。
- **`delegation` 作为只读属性暴露** — 消费方无需保留构造参数即可查询 appliance 是否绑定了委托。
- **`start()`/`close()` 仍 suspend** — 与现有签名保持一致;`start()` 仍需幂等。

### 3.2 `DefaultRealtimeAppliance`(新文件,沿用现有逻辑)

```kotlin
// realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/DefaultRealtimeAppliance.kt
public class DefaultRealtimeAppliance(
    private val session: RealtimeSession,
    private val sessionConfig: SessionConfig,
    private val microphone: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    override val delegation: RealtimeDelegation? = null,
) : RealtimeAppliance {
    // 现有 RealtimeAppliance 类的全部逻辑原样迁移,只:
    //   1) 改 class 为 class : RealtimeAppliance
    //   2) 构造 delegationHandler 时把 session.injectAndRespond 包成 onReply 传进去
    //   3) 暴露 override val delegation
}
```

### 3.3 `RealtimeDelegation.kt`(新文件,统一管理委托)

```kotlin
// realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeDelegation.kt
package io.github.yeyi.agent.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch

public interface RealtimeDelegation {
    public val capabilities: List<String>
    public val replies: Flow<DelegationReply>
    public suspend fun run(asrText: String)
}

public sealed interface DelegationReply {
    public data class Confirmation(val text: String) : DelegationReply
    public data class Success(val text: String) : DelegationReply
    public data class Failure(val message: String) : DelegationReply
}

public class DelegationHandler(
    private val delegation: RealtimeDelegation,
    private val scopeProvider: () -> CoroutineScope?,
    private val onReply: suspend (String) -> Unit,
) {
    private var pendingAsr: String? = null

    fun appendInstructions(base: String): String { ... DELEGATION_PROTOCOL ... }
    fun start() { ... delegation.replies.collect { onReply(...) } ... }
    fun handle(event: RealtimeEvent): RealtimeEvent { ... marker + pendingAsr ... }

    private fun runDelegation(asrText: String) {
        scopeProvider()?.launch { delegation.run(asrText) }
    }

    internal companion object {
        // DELEGATION_MARKER, DELEGATION_PROTOCOL
    }
}
```

> 备注:`DelegationHandler` 构造器为 `public`,因为 `internal` 在 Kotlin 中是**模块级**可见性(:realtime:core 与 :realtime:providers:volc-android 是不同 Gradle 模块),用 `internal constructor` 会让 volc-android 模块无法直接构造。委托协议本身是公开契约,允许外部实现类直接构造并无问题。`start()` / `handle()` 仍是 public 方法。

要点:
- **`DelegationHandler` 不再依赖 `RealtimeSession`** — 改成接受 `onReply: suspend (String) -> Unit` 回调,使协议逻辑与具体传输方式(WebSocket vs SDK)解耦。`onReply` 命名表达"收到委托 reply 时调用"的语义。
- **类与构造器均为 `public`** — `:realtime:core` 与 `:realtime:providers:volc-android` 是不同 Gradle 模块,Kotlin 的 `internal` 是模块级可见性,无法跨模块限制构造。委托协议本身是公开契约,允许外部实现类直接构造。
- **DELEGATION_MARKER 与 DELEGATION_PROTOCOL 不变** — 协议文本与当前一致。

### 3.4 `DelegationHandler` 接线

`DefaultRealtimeAppliance`:

```kotlin
private val delegationHandler: DelegationHandler? = delegation?.let {
    DelegationHandler(
        delegation = it,
        scopeProvider = { scope },
        onReply = { text -> session.injectAndRespond(text) },
    )
}
```

`VolcRealtimeAppliance`:

```kotlin
private val delegationHandler: DelegationHandler? = delegation?.let {
    DelegationHandler(
        delegation = it,
        scopeProvider = { scope },
        onReply = { text -> engine?.sayHello(text) },  // SDK 原生方法
    )
}
```

---

## 4. 新模块 `:realtime:providers:volc-android`

### 4.1 模块结构

```
realtime/providers/volc-android/
├── build.gradle.kts
└── src/main/
    ├── AndroidManifest.xml
    └── kotlin/io/github/yeyi/agent/realtime/volc/
        └── VolcRealtimeAppliance.kt
```

### 4.2 依赖配置(`build.gradle.kts`)

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "io.github.yeyi.agent.realtime.volc"
    compileSdk = 36
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21); explicitApi() }
}

dependencies {
    api(project(":realtime:core"))
    api(project(":realtime:providers:volc"))  // 复用 VolcSessionConfig / VolcSessionExtensionConfig DTO
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.speechengine.tob)
    implementation(libs.androidx.annotation)
}
```

`gradle/libs.versions.toml` 新增:

```toml
speechengine-tob = "0.0.15.0"

speechengine-tob = { module = "com.bytedance.speechengine:speechengine_tob", version.ref = "speechengine-tob" }
```

### 4.3 `VolcRealtimeAppliance`

```kotlin
public class VolcRealtimeAppliance(
    private val sessionConfig: SessionConfig,
    override val delegation: RealtimeDelegation? = null,
) : RealtimeAppliance {
    private var engine: SpeechEngine? = null
    private var scope: CoroutineScope? = null
    private val eventEmitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)

    private val delegationHandler: DelegationHandler? = delegation?.let {
        DelegationHandler(
            delegation = it,
            scopeProvider = { scope },
            onReply = { text -> engine?.sendDirective(DIRECTIVE_SEND_UPLINK_EVENT, buildSpeechTextCommit(text)) },
        )
    }

    override val events: Flow<RealtimeEvent> = eventEmitter.asSharedFlow()

    override suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
        try {
            val instructions = delegationHandler
                ?.appendInstructions(sessionConfig.instructions)
                ?: sessionConfig.instructions
            val engine = SpeechEngineGenerator.getInstance().createEngine().also {
                configInitParams(it, sessionConfig)
                it.initEngine()
                it.setListener(SpeechListenerImpl(::handleSdkMessage))
            }
            this.engine = engine
            engine.sendDirective(DIRECTIVE_START_ENGINE, buildSessionCreate(sessionConfig.copy(instructions = instructions)))
            delegationHandler?.start()
        } catch (t: Throwable) {
            runCatching { close() }
            throw t
        }
    }

    override suspend fun close() {
        engine?.sendDirective(DIRECTIVE_SYNC_STOP_ENGINE, buildSessionClose())
        engine?.destroyEngine()
        engine = null
        scope?.cancel()
        scope = null
    }

    private fun handleSdkMessage(type: Int, data: ByteArray, len: Int) {
        val scope = scope ?: return  // 已 close,丢弃回调
        val event = mapToRealtimeEvent(type, data, len) ?: return
        val cleaned = delegationHandler?.handle(event) ?: event
        scope.launch { eventEmitter.emit(cleaned) }
    }

    // === 私有 helper(签名;实现按 SpeechDemoAndroid 移植) ===

    private inner class SpeechListenerImpl(
        private val onMessage: (type: Int, data: ByteArray, len: Int) -> Unit,
    ) : SpeechEngine.SpeechListener {
        override fun onSpeechMessage(type: Int, data: ByteArray, len: Int) {
            onMessage(type, data, len)
        }
        override fun onSpeechLogid(logid: String) { /* no-op */ }
    }

    private fun configInitParams(engine: SpeechEngine, config: SessionConfig) { ... }

    // 复用 VolcRealtimeAdapter 公开的 VolcProtocolSupport
    private fun buildSessionCreate(config: SessionConfig): String {
        val session = VolcProtocolSupport.buildSessionConfig(config, audioInputFormat, audioOutputFormat)
        val extension = VolcProtocolSupport.buildSessionExtensionConfig(config)
        val payload = buildJsonObject {
            put("type", "session.create")
            put("event_id", "event_${UUID.randomUUID()}")
            put("session", json.encodeToJsonElement(VolcSessionConfig.serializer(), session))
            put("extension", json.encodeToJsonElement(VolcSessionExtensionConfig.serializer(), extension))
        }
        return payload.toString()
    }

    private fun buildSessionClose(): String { ... }
    private fun buildSpeechTextCommit(text: String): String { ... }
    private fun mapToRealtimeEvent(type: Int, data: ByteArray, len: Int): RealtimeEvent? { ... }
}
```

#### SDK 集成要点(基于 `SpeechDemoAndroid/DialogDuplexActivity.java`)

| 配置 / 操作 | SDK 调用 | 数据来源 |
|---|---|---|
| 引擎类型 | `setOptionString(PARAMS_KEY_ENGINE_NAME_STRING, DIALOG_ENGINE)` | 写死 |
| 协议类型 | `setOptionInt(PARAMS_KEY_PROTOCOL_TYPE_INT, PROTOCOL_TYPE_SEED_DUPLEX)` | 写死 |
| App Key | `setOptionString(PARAMS_KEY_APP_KEY_STRING, ...)` | `sessionConfig.apiKey` |
| Resource ID | `setOptionString(PARAMS_KEY_RESOURCE_ID_STRING, ...)` | 写死常量(`DIALOG_DUPLEX_DEFAULT_RESOURCE_ID`) |
| UID | `setOptionString(PARAMS_KEY_UID_STRING, ...)` | 写死(`agent-sdk`) |
| 模型 | `session.model` JSON 字段 | `sessionConfig.model` |
| Instructions | `session.instructions` JSON 字段 | 委托注入后 |
| 启动 | `sendDirective(DIRECTIVE_START_ENGINE, session.create JSON)` | `buildSessionCreate()` |
| 停止 | `sendDirective(DIRECTIVE_SYNC_STOP_ENGINE, session.close JSON)` | `buildSessionClose()` |
| 文本注入 | `sendDirective(DIRECTIVE_SEND_UPLINK_EVENT, speech_text_buffer.commit JSON)` | `buildSpeechTextCommit(text)` |
| 回调 | `setListener(this)` → `onSpeechMessage(int type, byte[] data, int len)` | 转 `RealtimeEvent` |

#### 事件映射(基于 `DialogDuplexActivity.handleDialogDownlinkEvent`)

| SDK Message | Volc 协议事件 | `RealtimeEvent` |
|---|---|---|
| `MESSAGE_TYPE_ENGINE_START` | — | `Connected(sessionId)` |
| `MESSAGE_TYPE_ENGINE_STOP` | — | `Disconnected("engine stopped")` |
| `MESSAGE_TYPE_ENGINE_ERROR` | — | `Error("engine_error", data, isFatal=true)` |
| `MESSAGE_TYPE_DIALOG_DOWNLINK_EVENT` | `session.created` | `Connected(session.id)` |
| | `session.closed` | `Disconnected("session closed")` |
| | `conversation.item.input_audio_transcription.started` | `UserTranscriptStarted(itemId)` |
| | `conversation.item.input_audio_transcription.delta` | `UserTranscriptDelta(delta)` |
| | `conversation.item.input_audio_transcription.completed` | `UserTranscriptCompleted(text)` |
| | `response.output_text.delta` | `AssistantTextDelta(delta)` |
| | `response.output_text.done` | (空) |
| | `response.output_audio.started` | `AssistantAudioStarted` |
| | `response.output_audio.delta` | `AssistantAudioDelta(Base64.decode(delta))` |
| | `response.output_audio.done` | `AssistantAudioDone` |
| | `response.canceled` | `ResponseCanceled` |
| | `response.done` | `ResponseDone` |
| | `error` | `Error(code, message, isFatal=false)` |
| | `response.function_call_arguments.done` | (V1 不接 FC,空;留待 V2) |
| | 其他 `conversation.item.*` | (空) |

#### `SessionConfig` 字段映射

| `SessionConfig` 字段 | SDK 用途 | 来源 |
|---|---|---|
| `apiKey` | `PARAMS_KEY_APP_KEY_STRING` | SDK 选项 |
| `endpoint` | **忽略** — SDK 内置服务地址(可接受) | — |
| `model` | `session.model` JSON 字段 | 协议 |
| `instructions` | `session.instructions` JSON 字段(经委托协议增强) | 协议 |
| `voice` | `audio.output.voice` JSON 字段 | 协议 |
| `tools` | `session.tools` JSON 数组 | 协议(同 WebSocket) |
| `turnDetection.ServerVad(thresholdMs)` | `extension.asr.extra.enable_custom_vad=true` + `end_smooth_window_ms=N` | 协议(同 WebSocket) |
| `turnDetection.Manual` | `extension.dialog.extra.input_mod="push_to_talk"` | 协议(同 WebSocket) |

`tools` / `turnDetection` 通过复用 `VolcRealtimeAdapter` 的 `VolcSessionConfig` / `VolcSessionExtensionConfig` DTO 装配。协议层是同一个后端,SDK demo 没演示不代表不支持。端到端验证在 `SpeechDemoAndroid` 真机跑通即确认。

#### 限制(V1 范围外)

- `endpoint` 不生效(SDK 内置服务地址),字段保留仅为接口一致。
- SDK 端不发 `response.output_audio.delta` 的 PCM 字节给 callback(默认 `ENABLE_PLAYER_AUDIO_CALLBACK=false`);若上层需要 PCM,需另外打开该开关,不在本 spec 范围。
- 音频编码:SDK 模式固定为 opus(`speech_opus` / `ogg_opus`),与 WebSocket 模式的 PCM 不同;`SessionConfig` 不暴露该差异,`VolcRealtimeAppliance` 内部硬编码 opus 常量。

---

## 5. 测试策略

### 5.1 `:realtime:core` 单测迁移

`realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/RealtimeApplianceTest.kt`:

- 文件改名为 `DefaultRealtimeApplianceTest.kt`(包不变)。
- 全部 `RealtimeAppliance(...)` 构造改为 `DefaultRealtimeAppliance(...)`。
- 其余 11 个测试逻辑不变。
- 行为覆盖范围(闲聊、打断、barge-in、委托触发、reply 顺序、mic 转发、start 幂等、重连、双 close、空委托)全部保持。

### 5.2 `DelegationHandler` 独立单测(新增)

`realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/DelegationHandlerTest.kt`:

- 测试 `appendInstructions()` 在 capability 列表非空时拼接 DELEGATION_PROTOCOL,空时返回原 base。
- 测试 `handle(UserTranscriptCompleted)` 设置 pendingAsr,后续 `handle(AssistantTextDelta("|xxx"))` 触发 `delegation.run(pendingAsr)` 并去除 marker。
- 测试 `start()` 在收到 `DelegationReply.Success(text)` 时调用 `onReply` 传入 text。
- 测试三种 reply(Confirmation/Success/Failure)都被正确映射到 text(Failure 取 message)。

### 5.3 `:realtime:providers:volc-android` 单测

V1 范围内不引入对真实 SDK 的 mock(SDK 庞大、Java-only 接口,集成测试成本高);改为:

- 单元测试覆盖纯函数:`buildSessionCreate(SessionConfig)` / `buildSessionClose()` / `buildSpeechTextCommit(text)` / `mapToRealtimeEvent(sdkMessageType, payload)`。
- 端到端验证依赖 `SpeechDemoAndroid` 跑真机。
- 不在 CI 中跑 SDK 单测。

---

## 6. 迁移路径

### 6.1 `:realtime:core` 模块

| 文件 | 动作 |
|---|---|
| `RealtimeAppliance.kt` | 缩成只剩 `interface RealtimeAppliance` |
| `RealtimeDelegation.kt`(新建) | `RealtimeDelegation` + `DelegationReply` + `DelegationHandler` |
| `DefaultRealtimeAppliance.kt`(新建) | 现有类实现搬到新文件,`class` 改 `: RealtimeAppliance` |
| `RealtimeApplianceTest.kt` → `DefaultRealtimeApplianceTest.kt` | 类名 + 构造调用更新 |

### 6.2 `:realtime:providers:volc` 模块

| 文件 | 动作 |
|---|---|
| `VolcDtos.kt` | DTO(`VolcSessionConfig` / `VolcSessionExtensionConfig` / `VolcAudioConfig` / `VolcAudioSideConfig` / `VolcFormatConfig` / `VolcExtensionSide` / `VolcExtensionDialog` / `VolcConversationItem` 等)从 `internal` 升 `public`,供 `VolcRealtimeAppliance` 复用 |
| `VolcRealtimeAdapter.kt` | 抽出 `volcSessionConfig(config)` / `volcSessionExtensionConfig(config)` / `AudioFormat.toVolcFormatConfig()` 为 public top-level helper(或 `public object VolcProtocolSupport`),两处实现共用;`VolcRealtimeAdapter` 内部仍调同样的 helper(行为不变) |

### 6.3 `:realtime:audio:android` 模块

无改动。新模块不依赖它。

### 6.4 demo 模块

| 文件 | 动作 |
|---|---|
| `S2sViewModel.kt:56` | `RealtimeAppliance(...)` → `DefaultRealtimeAppliance(...)`;字段类型 `RealtimeAppliance?` 不变 |

### 6.5 settings.gradle.kts

新增:

```kotlin
include(":realtime:providers:volc-android")
```

### 6.6 `gradle/libs.versions.toml`

新增:

```toml
speechengine-tob = "0.0.15.0"
speechengine-tob = { module = "com.bytedance.speechengine:speechengine_tob", version.ref = "speechengine-tob" }
```

---

## 7. 文档更新

### 7.1 `docs/realtime-architecture.md`

新增小节(暂不重写主文档):

```markdown
## 12. RealtimeAppliance 实现

`RealtimeAppliance` 是高层事件编排器接口,负责把 `RealtimeSession` 的事件流
转成统一的 `RealtimeEvent` 流,并可选地挂载委托协议处理器。

### 12.1 DefaultRealtimeAppliance

Ktor WebSocket 实现。需要外部注入 `MicrophoneAdapter` / `SpeakerAdapter`,
音频完全由调用方控制。

### 12.2 VolcRealtimeAppliance(Android 原生 SDK)

模块路径: `:realtime:providers:volc-android`

依赖: `com.bytedance.speechengine:speechengine_tob:0.0.15.0`

构造器: `VolcRealtimeAppliance(sessionConfig, delegation = null)`

音频由 SDK 自管,不暴露 `MicrophoneAdapter` / `SpeakerAdapter` 注入点。
```

### 7.2 KDoc 注释

- `RealtimeAppliance.delegation` — `/** 当前 appliance 绑定的委托(可空);空时跳过委托协议 */`
- `DelegationHandler` 类 — `/** 委托协议处理器。由各 RealtimeAppliance 实现内部使用;构造器 internal 阻止外部直接实例化。 */`

---

## 8. 风险与开放问题

### 8.1 SDK 线程模型

`SpeechEngine.SpeechListener.onSpeechMessage` 在 SDK 内部线程回调。`VolcRealtimeAppliance.handleSdkMessage` 直接调用 `scope?.launch { eventEmitter.emit(...) }` — `eventEmitter` 是线程安全的 `MutableSharedFlow`,`DelegationHandler.handle()` 内部只读 `pendingAsr` 不需要同步。**风险点**:`pendingAsr` 的写/读是普通 `var`,横跨 SDK 线程与 `scope` 协程,可能存在竞态。

**缓解**:V1 接受竞态概率(委托触发概率小,且 `pendingAsr` 一定在 `AssistantTextDelta` 之前由 `UserTranscriptCompleted` 设置);V2 可改 `AtomicReference<String?>` 或把 `DelegationHandler.handle()` 移到 `scope.launch` 中执行。

### 8.2 SDK 版本冻结

`com.bytedance.speechengine:speechengine_tob:0.0.15.0` 是当前可用版本;若 SDK 升级破坏 API,`VolcRealtimeAppliance` 需同步适配。本 spec 冻结此版本,V2 升级时另起 spec。

### 8.3 `SessionConfig` 与 SDK 配置的语义偏差

仅 `endpoint` 在 `VolcRealtimeAppliance` 中被忽略(SDK 内置服务地址),其他字段均通过 `VolcSessionConfig` / `VolcSessionExtensionConfig` DTO 正确传递(同 WebSocket 协议)。`endpoint` 在 KDoc 中显式标注"Volc SDK 模式下不生效"。

### 8.4 与 `ResponseCanceled` 的对应事件

WebSocket 模式下 `cancelResponse()` 由 session 主动发送;SDK 模式下由 `clientInterrupt()` 通过 `DIRECTIVE_SEND_UPLINK_EVENT` + `response.cancel` JSON 发送。`VolcRealtimeAppliance` 不暴露 `cancelResponse` 方法(接口里没有),上层通过 `RealtimeEvent` 监听 `UserTranscriptStarted` 后自行处理(与 demo 现有方式一致)。

---

## 9. 验收标准

- [ ] `:realtime:core` 编译通过,`RealtimeAppliance` 为 interface,`DefaultRealtimeAppliance` 实现。
- [ ] `DelegationHandler` 提升至 `realtime/core` 独立文件,跨模块可访问(`public class` + `internal constructor`)。
- [ ] `RealtimeApplianceTest.kt` 11 个测试全部迁移到 `DefaultRealtimeApplianceTest.kt` 并通过。
- [ ] `DelegationHandlerTest.kt` 新增 ≥4 个测试并通过。
- [ ] `:realtime:providers:volc-android` 模块编译通过,`VolcRealtimeAppliance` 接线完整。
- [ ] `settings.gradle.kts` 与 `gradle/libs.versions.toml` 同步更新。
- [ ] `S2sViewModel.kt` 改用 `DefaultRealtimeAppliance` 构造,demo 编译通过。
- [ ] `docs/realtime-architecture.md` 新增"RealtimeAppliance 实现"小节。

---

## 10. 后续(V2,不在本 spec 范围)

1. `VolcRealtimeAppliance` 暴露 `ENABLE_PLAYER_AUDIO_CALLBACK` 让上层消费 PCM。
2. `DelegationHandler` 内部状态(`pendingAsr`)改线程安全容器。
3. `VolcRealtimeAppliance` 暴露 FC(`response.function_call_arguments.done`)支持,复用 `:realtime:core` 的 `Tool` 接口。
4. `SessionConfig` 拆分为 `WebSocketSessionConfig` + `SdkSessionConfig`,用 sealed interface 区分。
5. `VolcRealtimeAppliance` 单元测试接入 mock SDK(若 SDK 提供 test double)。
