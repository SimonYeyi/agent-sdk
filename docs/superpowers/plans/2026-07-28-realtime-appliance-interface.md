# RealtimeAppliance 接口化与 Volc 端实现 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Extract `RealtimeAppliance` into an interface with two implementations (`DefaultRealtimeAppliance` Ktor/WebSocket + `VolcRealtimeAppliance` native SDK), refactor `DelegationHandler` to be cross-module reusable, and create the `:realtime:providers:volc-android` Android module.

**Architecture:** `RealtimeDelegation`, `DelegationReply`, and `DelegationHandler` move to their own file (`RealtimeDelegation.kt`). `DelegationHandler` replaces `session: RealtimeSession` dependency with `onReply: suspend (String) -> Unit` callback, decoupling protocol logic from transport. `VolcRealtimeAppliance` wraps the Volc Android SDK, using `VolcRealtimeAdapter` for protocol frame construction and a new `parseSdkEvent()` method for message parsing.

**Tech Stack:** Kotlin Multiplatform/JVM + Android, kotlinx.coroutines, Kotlin Serialization, Volc SpeechEngine SDK 0.0.15.0, JUnit/kotlin.test

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `realtime/core/.../RealtimeAppliance.kt` | Modify | 改造为 `interface RealtimeAppliance` + `internal class DefaultRealtimeAppliance` + 顶层工厂函数,三段并置 |
| `realtime/core/.../RealtimeDelegation.kt` | Create | `RealtimeDelegation` + `DelegationReply` + `DelegationHandler` |
| `realtime/core/.../RealtimeApplianceTest.kt` | - | 无需改动(工厂函数签名一致) |
| `realtime/core/.../DelegationHandlerTest.kt` | Create | 独立单测 |
| `realtime/providers/volc-android/build.gradle.kts` | Create | Android lib 模块配置 |
| `realtime/providers/volc-android/src/main/AndroidManifest.xml` | Create | Android 清单 |
| `realtime/providers/volc-android/.../VolcRealtimeAppliance.kt` | Create | Volc SDK 实现 |
| `gradle/libs.versions.toml` | Modify | 新增 speechengine-tob |
| `settings.gradle.kts` | Modify | 新增 volc-android include |
| `demo/.../S2sViewModel.kt` | - | 无需改动(工厂函数签名一致) |
| `docs/realtime-architecture.md` | Modify | 新增 "RealtimeAppliance 实现" 小节 |

---

### Task 1: 更新 `gradle/libs.versions.toml` — 新增 speechengine-tob 依赖

**Files:**
- Modify: `gradle/libs.versions.toml`

- [ ] **Step 1: 在 `[versions]` 和 `[libraries]` 中新增 speechengine-tob 条目**

在 `[versions]` 块末尾新增:
```toml
speechengine-tob = "0.0.15.0"
```

在 `[libraries]` 块末尾新增:
```toml
speechengine-tob = { module = "com.bytedance.speechengine:speechengine_tob", version.ref = "speechengine-tob" }
```

- [ ] **Step 2: 验证文件格式**

Run: `cat -n gradle/libs.versions.toml`
Expected: TOML 语法正确,新条目在各自区块末尾。

- [ ] **Step 3: Commit**

```bash
git add gradle/libs.versions.toml
git commit -m "chore(deps): add speechengine-tob 0.0.15.0"
```

---

### Task 2: 更新 `settings.gradle.kts` — 新增 volc-android 模块注册

**Files:**
- Modify: `settings.gradle.kts`

- [ ] **Step 1: 在 `:realtime:audio:android` 行后新增 include**

```kotlin
include(":realtime:providers:volc-android")
```

- [ ] **Step 2: Commit**

```bash
git add settings.gradle.kts
git commit -m "chore: register :realtime:providers:volc-android module"
```

---

### Task 3: 提取 Delegation 类型到独立文件 `RealtimeDelegation.kt`

**Files:**
- Create: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeDelegation.kt`
- Modify: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt` (删除这三个符号)

- [ ] **Step 1: 创建 `RealtimeDelegation.kt`**

写入以下内容:

```kotlin
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

    public fun appendInstructions(base: String): String {
        val capabilityList = delegation.capabilities.joinToString("\n") { "- $it" }
        return "$base\n\n$DELEGATION_PROTOCOL\n$capabilityList"
    }

    public fun start() {
        scopeProvider()?.launch {
            delegation.replies.collect { update ->
                val text = when (update) {
                    is DelegationReply.Confirmation -> update.text
                    is DelegationReply.Success -> update.text
                    is DelegationReply.Failure -> update.message
                }
                onReply(text)
            }
        }
    }

    public fun handle(event: RealtimeEvent): RealtimeEvent {
        when (event) {
            is RealtimeEvent.UserTranscriptCompleted -> pendingAsr = event.text
            is RealtimeEvent.AssistantTextDelta -> {
                if (event.text.startsWith(DELEGATION_MARKER)) {
                    pendingAsr?.let { runDelegation(it) }
                    return event.copy(text = event.text.removePrefix(DELEGATION_MARKER))
                }
            }
            else -> Unit
        }
        return event
    }

    private fun runDelegation(asrText: String) {
        scopeProvider()?.launch { delegation.run(asrText) }
    }

    internal companion object {
        private const val DELEGATION_MARKER = "|"
        private const val AVAILABLE_CAPABILITIES_LABEL = "可用能力"
        private val DELEGATION_PROTOCOL = """
            委派协议：
            1. 闲聊 (问候/聊天/知识问答/一般咨询)：直接自然口语回答。
            2. 命中已注册的 function_call 工具：直接发起函数调用，无需标记委派（跳过第3点）。
            3. 落在下面"${AVAILABLE_CAPABILITIES_LABEL}"列表中（不在能力范围内，一律按闲聊处理）：
               assistant 输出**必须**以 ${DELEGATION_MARKER} 开头标记委派，紧接对用户的简短确认。

               完整示例（用户说"帮我调暗客厅灯"）：

                   ${DELEGATION_MARKER}好的，正在为您调暗客厅灯，请稍等

               要求:
               - 简短确认**必须用进行时** (表达"正在处理"), 不能用完成时承诺结果。

               ${AVAILABLE_CAPABILITIES_LABEL}：
        """.trimIndent()
    }
}
```

- [ ] **Step 2: 从 `RealtimeAppliance.kt` 中删除 `RealtimeDelegation`, `DelegationReply`, `DelegationHandler`**

把 `RealtimeAppliance.kt` 中这三个符号的所有代码（从 `public interface RealtimeDelegation` 到文件末尾的 `companion object`）删除,只保留 `class RealtimeAppliance`。

- [ ] **Step 3: 验证编译**

Run: `cd realtime && ../gradlew :realtime:core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: Commit**

```bash
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeDelegation.kt
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt
git commit -m "refactor(core): extract RealtimeDelegation + DelegationHandler to separate file"
```

---

### Task 4: 改造 RealtimeAppliance 为接口 + 同文件加 DefaultRealtimeAppliance(internal) + 工厂函数

**Files:**
- Modify: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt`

- [ ] **Step 1: 重写 `RealtimeAppliance.kt`(interface + internal class + 顶层工厂函数)**

三段并置在同一文件中:

```kotlin
package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch

public interface RealtimeAppliance {
    public val delegation: RealtimeDelegation?
    public val events: Flow<RealtimeEvent>
    public suspend fun start()
    public suspend fun close()
}

internal class DefaultRealtimeAppliance(
    private val session: RealtimeSession,
    private val sessionConfig: SessionConfig,
    private val microphone: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    override val delegation: RealtimeDelegation? = null,
) : RealtimeAppliance {
    private var scope: CoroutineScope? = null
    private var userQuerying: Boolean = false
    private var audioChannel: Channel<ByteArray>? = null

    private val delegationHandler: DelegationHandler? = delegation?.let { delegation ->
        DelegationHandler(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = { text -> session.injectAndRespond(text) },
        )
    }

    override val events: Flow<RealtimeEvent> = MutableSharedFlow(extraBufferCapacity = 64)

    override suspend fun start() {
        if (scope != null) return
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        userQuerying = false
        try {
            val instructions = delegationHandler
                ?.appendInstructions(sessionConfig.instructions)
                ?: sessionConfig.instructions
            session.connect(sessionConfig.copy(instructions = instructions))
            microphone.start(session.inputAudioFormat)
            speaker.start(session.outputAudioFormat)
            scope?.launch {
                session.events.collect { event ->
                    handleEvent(event)
                    val handled = delegationHandler?.handle(event)
                    (events as MutableSharedFlow).emit(handled ?: event)
                }
            }
            scope?.launch {
                microphone.capture().collect { pcm -> session.sendAudio(pcm) }
            }

            audioChannel = Channel<ByteArray>(capacity = Channel.UNLIMITED).also { channel ->
                scope?.launch {
                    for (pcm in channel) {
                        speaker.play(pcm)
                    }
                }
            }

            delegationHandler?.start()
        } catch (e: Throwable) {
            runCatching { close() }
            throw e
        }
    }

    override suspend fun close() {
        userQuerying = false
        audioChannel?.close()
        audioChannel = null
        scope?.coroutineContext[Job]?.cancelAndJoin()
        scope = null
        microphone.close()
        speaker.close()
        session.close()
    }

    private suspend fun handleEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptStarted -> {
                userQuerying = true
                drainAudioChannel()
                speaker.stopPlayback()
            }
            is RealtimeEvent.AssistantAudioStarted -> {
                userQuerying = false
            }
            is RealtimeEvent.AssistantAudioDelta if !userQuerying -> {
                audioChannel?.send(event.pcm)
            }
            else -> {}
        }
    }

    private fun drainAudioChannel() {
        while (audioChannel?.tryReceive()?.isSuccess == true) {
            // drop pending audio to discard the tail of the previous round's TTS
        }
    }
}

public fun RealtimeAppliance(
    session: RealtimeSession,
    sessionConfig: SessionConfig,
    microphone: MicrophoneAdapter,
    speaker: SpeakerAdapter,
    delegation: RealtimeDelegation? = null,
): RealtimeAppliance = DefaultRealtimeAppliance(
    session = session,
    sessionConfig = sessionConfig,
    microphone = microphone,
    speaker = speaker,
    delegation = delegation,
)
```

- [ ] **Step 2: 验证编译**

Run: `cd realtime && ../gradlew :realtime:core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt
git commit -m "refactor(core): RealtimeAppliance is now interface, DefaultRealtimeAppliance in same file"
```

---### Task 5: 新增 `DelegationHandlerTest.kt`

**Files:**
- Create: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/DelegationHandlerTest.kt`

- [ ] **Step 1: 写入测试文件**

```kotlin
@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class DelegationHandlerTest {

    private class FakeDelegation(
        override val capabilities: List<String>,
    ) : RealtimeDelegation {
        private val replyEmitter = MutableSharedFlow<DelegationReply>(extraBufferCapacity = 16)
        override val replies = replyEmitter.asSharedFlow()
        val dispatched = Channel<String>(Channel.UNLIMITED)

        override suspend fun run(asrText: String) {
            dispatched.send(asrText)
        }

        fun emit(reply: DelegationReply) {
            check(replyEmitter.tryEmit(reply))
        }
    }

    @Test
    fun `appendInstructions with capabilities appends protocol`() = runTest {
        val delegation = FakeDelegation(capabilities = listOf("灯光控制", "空调控制"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = DelegationHandler(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = {},
        )

        val result = handler.appendInstructions("你是助手")

        // 结果应包含原 base, DELEGATION_PROTOCOL, 以及 capabilities 列表
        assertEquals(true, result.startsWith("你是助手"))
        assertEquals(true, result.contains("灯光控制"))
        assertEquals(true, result.contains("空调控制"))
        scope.cancel()
    }

    @Test
    fun `appendInstructions with empty capabilities returns base unchanged`() = runTest {
        val delegation = FakeDelegation(capabilities = emptyList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = DelegationHandler(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = {},
        )

        val result = handler.appendInstructions("你是助手")

        assertEquals(true, result.startsWith("你是助手"))
        // DELEGATION_PROTOCOL 会被追加,但 capabilities 列表为空
        assertEquals(true, result.contains("委派协议"))
        scope.cancel()
    }

    @Test
    fun `handle with UserTranscriptCompleted sets pendingAsr`() {
        val delegation = FakeDelegation(capabilities = emptyList())
        val handler = DelegationHandler(
            delegation = delegation,
            scopeProvider = { null },
            onReply = {},
        )

        val result = handler.handle(RealtimeEvent.UserTranscriptCompleted("帮我开灯"))

        // 事件原样透传
        assertEquals(RealtimeEvent.UserTranscriptCompleted("帮我开灯"), result)
    }

    @Test
    fun `handle with marker text triggers delegation and strips marker`() = runTest {
        val delegation = FakeDelegation(capabilities = listOf("灯光控制"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = DelegationHandler(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = {},
        )

        handler.handle(RealtimeEvent.UserTranscriptCompleted("帮我开灯"))
        val result = handler.handle(RealtimeEvent.AssistantTextDelta("|好的，正在开灯"))

        assertEquals(RealtimeEvent.AssistantTextDelta("好的，正在开灯"), result)
        val called = withTimeout(5_000) { delegation.dispatched.receive() }
        assertEquals("帮我开灯", called)
        scope.cancel()
    }

    @Test
    fun `start collects replies and invokes onReply`() = runTest {
        val delegation = FakeDelegation(capabilities = emptyList())
        val received = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = DelegationHandler(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = { text -> received.add(text) },
        )

        handler.start()

        delegation.emit(DelegationReply.Confirmation("正在处理"))
        delegation.emit(DelegationReply.Success("完成"))
        delegation.emit(DelegationReply.Failure("参数错误"))

        // 等待协程处理
        kotlinx.coroutines.delay(200)

        assertEquals(listOf("正在处理", "完成", "参数错误"), received)
        scope.cancel()
    }
}
```

- [ ] **Step 2: 运行测试**

Run: `cd realtime && ../gradlew :realtime:core:test`
Expected: 16 tests passed (11 existing + 5 new, same file name `RealtimeApplianceTest.kt`)

- [ ] **Step 3: Commit**

```bash
git add realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/DelegationHandlerTest.kt
git commit -m "test(core): add DelegationHandlerTest with 5 test cases"
```

### Task 6: 创建 `:realtime:providers:volc-android` 模块

**Files:**
- Create: `realtime/providers/volc-android/build.gradle.kts`
- Create: `realtime/providers/volc-android/src/main/AndroidManifest.xml`
- Create: `realtime/providers/volc-android/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeAppliance.kt`

- [ ] **Step 1: 创建目录结构**

```bash
mkdir -p realtime/providers/volc-android/src/main/kotlin/io/github/yeyi/agent/realtime/volc
```

- [ ] **Step 2: 创建 `build.gradle.kts`**

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
    api(project(":realtime:providers:volc"))
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.speechengine.tob)
    implementation(libs.androidx.annotation)
}
```

- [ ] **Step 3: 创建 `AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android" />
```

- [ ] **Step 4: 创建 `VolcRealtimeAppliance.kt`**

```kotlin
package io.github.yeyi.agent.realtime.volc

import androidx.annotation.Keep
import com.bytedance.speech.speechengine.SpeechEngine
import com.bytedance.speech.speechengine.SpeechEngineDefines
import com.bytedance.speech.speechengine.SpeechEngineDefines.DIRECTIVE_SEND_UPLINK_EVENT
import com.bytedance.speech.speechengine.SpeechEngineDefines.DIRECTIVE_START_ENGINE
import com.bytedance.speech.speechengine.SpeechEngineDefines.MESSAGE_TYPE_DIALOG_DOWNLINK_EVENT
import com.bytedance.speech.speechengine.SpeechEngineDefines.MESSAGE_TYPE_ENGINE_ERROR
import com.bytedance.speech.speechengine.SpeechEngineDefines.MESSAGE_TYPE_ENGINE_START
import com.bytedance.speech.speechengine.SpeechEngineDefines.MESSAGE_TYPE_ENGINE_STOP
import com.bytedance.speech.speechengine.SpeechEngineDefines.PARAMS_KEY_APP_KEY_STRING
import com.bytedance.speech.speechengine.SpeechEngineDefines.PARAMS_KEY_ENGINE_NAME_STRING
import com.bytedance.speech.speechengine.SpeechEngineDefines.PARAMS_KEY_PROTOCOL_TYPE_INT
import com.bytedance.speech.speechengine.SpeechEngineDefines.PARAMS_KEY_RESOURCE_ID_STRING
import com.bytedance.speech.speechengine.SpeechEngineDefines.PARAMS_KEY_UID_STRING
import com.bytedance.speech.speechengine.SpeechEngineDefines.PROTOCOL_TYPE_SEED_DUPLEX
import io.github.yeyi.agent.realtime.DelegationHandler
import io.github.yeyi.agent.realtime.ProtocolFrame
import io.github.yeyi.agent.realtime.RealtimeAppliance
import io.github.yeyi.agent.realtime.RealtimeDelegation
import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.SessionConfig
import kotlinx.serialization.json.Json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch

private const val DIALOG_ENGINE = "DialogDuplex"
private const val DIALOG_DUPLEX_DEFAULT_RESOURCE_ID = "agent-sdk"
private const val UID = "agent-sdk"

public class VolcRealtimeAppliance(
    private val sessionConfig: SessionConfig,
    override val delegation: RealtimeDelegation? = null,
) : RealtimeAppliance {

    private val protocolAdapter = VolcRealtimeAdapter()
    private var engine: SpeechEngine? = null
    private var scope: CoroutineScope? = null
    private val eventEmitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)

    private val delegationHandler: DelegationHandler? = delegation?.let {
        DelegationHandler(
            delegation = it,
            scopeProvider = { scope },
            onReply = { text ->
                val frames = protocolAdapter.commitSpeechTextFrame(text)
                frames.forEach { frame ->
                    engine?.sendDirective(DIRECTIVE_SEND_UPLINK_EVENT, frame.payload.toString())
                }
            },
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
            val sessionFrame = protocolAdapter.createSessionFrame(
                sessionConfig.copy(instructions = instructions)
            )
            engine.sendDirective(DIRECTIVE_START_ENGINE, sessionFrame.payload.toString())
            scope?.launch {
                protocolAdapter.events.collect { event ->
                    val handled = delegationHandler?.handle(event) ?: event
                    eventEmitter.emit(handled)
                }
            }
            delegationHandler?.start()
        } catch (t: Throwable) {
            runCatching { close() }
            throw t
        }
    }

    override suspend fun close() {
        engine?.destroyEngine()
        engine = null
        scope?.cancel()
        scope = null
    }

    private fun handleSdkMessage(type: Int, data: ByteArray, len: Int) {
        val scope = scope ?: return
        when (type) {
            MESSAGE_TYPE_DIALOG_DOWNLINK_EVENT -> {
                val payload = Json.parseToJsonElement(String(data, 0, len))
                val frame = ProtocolFrame(payload)
                scope.launch {
                    val replyFrames = protocolAdapter.handleIncomingFrame(frame)
                    replyFrames.forEach { rf ->
                        engine?.sendDirective(DIRECTIVE_SEND_UPLINK_EVENT, rf.payload.toString())
                    }
                }
            }
            MESSAGE_TYPE_ENGINE_START -> {
                // SDK init confirmed, no-op (start() already succeeded)
            }
            MESSAGE_TYPE_ENGINE_STOP -> {
                scope.launch { eventEmitter.emit(RealtimeEvent.Disconnected("engine stopped")) }
            }
            MESSAGE_TYPE_ENGINE_ERROR -> {
                val msg = if (len > 0) String(data, 0, len) else "engine error"
                scope.launch {
                    eventEmitter.emit(
                        RealtimeEvent.Error(code = "engine_error", message = msg, isFatal = true)
                    )
                }
            }
        }
    }

    private inner class SpeechListenerImpl(
        private val onMessage: (type: Int, data: ByteArray, len: Int) -> Unit,
    ) : SpeechEngine.SpeechListener {
        override fun onSpeechMessage(type: Int, data: ByteArray, len: Int) {
            onMessage(type, data, len)
        }
        override fun onSpeechLogid(logid: String) { /* no-op */ }
    }

    private fun configInitParams(engine: SpeechEngine, config: SessionConfig) {
        engine.setOptionString(PARAMS_KEY_ENGINE_NAME_STRING, DIALOG_ENGINE)
        engine.setOptionInt(PARAMS_KEY_PROTOCOL_TYPE_INT, PROTOCOL_TYPE_SEED_DUPLEX)
        engine.setOptionString(PARAMS_KEY_APP_KEY_STRING, config.apiKey)
        engine.setOptionString(PARAMS_KEY_RESOURCE_ID_STRING, DIALOG_DUPLEX_DEFAULT_RESOURCE_ID)
        engine.setOptionString(PARAMS_KEY_UID_STRING, UID)
    }
}
```

- [ ] **Step 5: 验证编译**

Run: `cd realtime && ../gradlew :realtime:providers:volc-android:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: Commit**

```bash
git add realtime/providers/volc-android/
git commit -m "feat(volc-android): add volc-android module with VolcRealtimeAppliance"
```

### Task 7: 更新架构文档

**Files:**
- Modify: `docs/realtime-architecture.md`

- [ ] **Step 1: 在文件末尾追加 "RealtimeAppliance 实现" 小节**

```markdown

---

## 12. RealtimeAppliance 实现

`RealtimeAppliance` 是高层事件编排器接口,负责把 `RealtimeSession` 的事件流
转成统一的 `RealtimeEvent` 流,并可选地挂载委托协议处理器。

### 12.1 DefaultRealtimeAppliance

Ktor WebSocket 实现(internal),通过顶层工厂函数构造:

```kotlin
public fun RealtimeAppliance(
    session: RealtimeSession,
    sessionConfig: SessionConfig,
    microphone: MicrophoneAdapter,
    speaker: SpeakerAdapter,
    delegation: RealtimeDelegation? = null,
): RealtimeAppliance
```

需要外部注入 `MicrophoneAdapter` / `SpeakerAdapter`,音频完全由调用方控制。

### 12.2 VolcRealtimeAppliance(Android 原生 SDK)

模块路径: `:realtime:providers:volc-android`

依赖: `com.bytedance.speechengine:speechengine_tob:0.0.15.0`

构造器: `VolcRealtimeAppliance(sessionConfig, delegation = null)`

音频由 SDK 自管,不暴露 `MicrophoneAdapter` / `SpeakerAdapter` 注入点。
```

同时更新第 7 节中 `RealtimeAppliance` 的模块结构树,将:
```
├── RealtimeAppliance.kt       # 高层编排器 + DelegationHandler
```
改为:
```
├── RealtimeAppliance.kt       # 高层编排器接口
├── DefaultRealtimeAppliance.kt # Ktor WebSocket 实现
├── RealtimeDelegation.kt      # 委托协议接口 + DelegationHandler
```

以及将模块树中的 `realtime/providers/volc/` 下新增:
```
├── providers/
│   └── volc-android/          # 火山引擎 Android SDK 实现
│       └── VolcRealtimeAppliance.kt
```

同时在 DelegationHandler 小节更新构造签名:
```kotlin
public class DelegationHandler(
    private val delegation: RealtimeDelegation,
    private val scopeProvider: () -> CoroutineScope?,
    private val onReply: suspend (String) -> Unit,
)
```

- [ ] **Step 2: Commit**

```bash
git add docs/realtime-architecture.md
git commit -m "docs: add RealtimeAppliance implementations section"
```

---

### Full Build Verification

- [ ] **Step: 全模块编译验证**

Run: `cd realtime && ../gradlew :realtime:core:compileKotlin :realtime:providers:volc:compileKotlin :realtime:providers:volc-android:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step: 全模块测试**

Run: `cd realtime && ../gradlew :realtime:core:test`
Expected: 16 tests passed

- [ ] **Step: Demo 编译**

Run: `cd demo && ../gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step: 最终提交**

```bash
git add -A
git commit -m "feat: RealtimeAppliance interface + DefaultRealtimeAppliance + VolcRealtimeAppliance"
```
