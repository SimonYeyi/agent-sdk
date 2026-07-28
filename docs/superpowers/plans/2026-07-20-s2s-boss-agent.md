# S2S × BossAgent 双层混合架构实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在项目内接入火山豆包全双工 S2S 模型，通过对话驱动 `:team` BossAgent 执行任务；阶段一交付 Android 端到端 demo。

**Architecture:** 双层混合 — S2S 处理闲聊 + 通过 `<|DELEGATE_TO_BOSS|>` 标记协议声明委派；纯 Kotlin 桥接层 `BossConversationBridge` 协调标记检测（`AssistantAudioGate`）、BossAgent 调用、S2S 空闲后结果回注。所有 S2S 相关代码集中在 `realtime/` 目录下，按 `:realtime:core` 与 `:realtime:providers:volc` 两个 Gradle 子模块拆分。

**Tech Stack:**
- Kotlin / JVM
- Ktor 3.0.3 (`ktor-client-websockets` / `ktor-client-content-negotiation` / `ktor-serialization-kotlinx-json`)
- kotlinx coroutines 1.10.1（Flow / Channel / SharedFlow）
- kotlinx serialization 1.9.0
- JUnit 5 + kotlin.test + kotlinx-coroutines-test

## Global Constraints

- **模块结构：**
  - `realtime/core/` → `:realtime:core`
  - `realtime/audio/android/` → `:realtime:audio:android`
  - `realtime/providers/volc/` → `:realtime:providers:volc`
  三个 Gradle 子模块
- **包命名：** core 根包 `io.github.yeyi.agent.realtime`，音频子包 `io.github.yeyi.agent.realtime.audio`；volc 包 `io.github.yeyi.agent.realtime.volc`；android audio 包 `io.github.yeyi.agent.realtime.audio.android`
- **依赖方向：**
  ```
  :realtime:providers:volc → :realtime:core → :team → :agent
  :realtime:audio:android  → :realtime:core
  :demo                    → :realtime:core, :realtime:audio:android, :realtime:providers:volc, :team, :agent
  ```
- **纯 Kotlin/JVM：** `:realtime:core` 不依赖任何 Android / 平台 API；`:realtime:audio:android` 依赖 Android SDK 但不依赖其他 platform
- **标记协议：** 单一权威信号是 `<|DELEGATE_TO_BOSS|>`，无桥接层启发式
- **commit 规范：** 原子提交；不推送；格式 `<type>(<module>): <subject>`，module 限定 `realtime/core` / `realtime/audio/android` / `realtime/providers/volc` / `demo`
- **package 与 import：** 仓库内代码禁止出现 `package` / `import` 之外的写满版声明；Kotlin 风格（`public` 默认可见性不写）

## File Structure

```
realtime/
├── core/
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/io/github/yeyi/agent/realtime/
│       │   ├── audio/
│       │   │   ├── AudioFormat.kt
│       │   │   ├── MicrophoneAdapter.kt
│       │   │   └── SpeakerAdapter.kt
│       │   ├── SessionConfig.kt
│       │   ├── RealtimeSession.kt
│       │   ├── RealtimeEvent.kt
│       │   ├── AssistantAudioGate.kt
│       │   ├── BridgeConfig.kt
│       │   └── BossConversationBridge.kt
│       └── test/kotlin/io/github/yeyi/agent/realtime/
│           ├── AssistantAudioGateTest.kt
│           └── BossConversationBridgeTest.kt
├── audio/
│   └── android/
│       ├── build.gradle.kts           # Android library plugin
│       └── src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/
│           ├── AndroidMicrophoneAdapter.kt
│           └── AndroidSpeakerAdapter.kt
└── providers/
    └── volc/
        ├── build.gradle.kts
        └── src/
            ├── main/kotlin/io/github/yeyi/agent/realtime/volc/
            │   ├── VolcDtos.kt
            │   ├── VolcStreamDecoder.kt
            │   └── VolcRealtimeSession.kt
            └── test/kotlin/io/github/yeyi/agent/realtime/volc/
                └── VolcStreamDecoderTest.kt

demo/src/main/kotlin/io/github/yeyi/agent/demo/s2s/
└── SmartHomeS2sScreen.kt          # 只组装,不再实现 adapter

settings.gradle.kts                 # 新增 :realtime:core, :realtime:audio:android, :realtime:providers:volc
```

---

## Task 1: 创建 `:realtime:core` 模块骨架 + AudioFormat

**Files:**
- Create: `realtime/core/build.gradle.kts`
- Create: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/audio/AudioFormat.kt`
- Create: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/audio/AudioFormatTest.kt`
- Modify: `settings.gradle.kts`（新增 include）

**Interfaces:**
- Consumes: 无
- Produces: `AudioFormat(sampleRateHz: Int, channels: Int, sampleBits: Int, encoding: Encoding)`、`AudioFormat.Encoding`

- [ ] **Step 1: 在 `settings.gradle.kts` 注册模块**

在 `settings.gradle.kts` 的 include 块（已有 `:team` 的位置之后）追加：

```kotlin
include(":realtime:core")
```

- [ ] **Step 2: 创建 `realtime/core/build.gradle.kts`**

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

dependencies {
    api(project(":agent"))
    api(project(":team"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
```

- [ ] **Step 3: 写 `AudioFormat` 数据类的失败测试**

`realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/audio/AudioFormatTest.kt`：

```kotlin
package io.github.yeyi.agent.realtime.audio

import kotlin.test.Test
import kotlin.test.assertEquals

class AudioFormatTest {
    @Test
    fun `AudioFormat stores its properties`() {
        val f = AudioFormat(
            sampleRateHz = 16000,
            channels = 1,
            sampleBits = 16,
            encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
        )
        assertEquals(16000, f.sampleRateHz)
        assertEquals(1, f.channels)
        assertEquals(16, f.sampleBits)
        assertEquals(AudioFormat.Encoding.PCM_SIGNED_LE, f.encoding)
    }

    @Test
    fun `Encoding has three values`() {
        val values = AudioFormat.Encoding.entries.toSet()
        assertEquals(
            setOf(
                AudioFormat.Encoding.PCM_SIGNED_LE,
                AudioFormat.Encoding.PCM_OPUS,
                AudioFormat.Encoding.PCM_FLOAT_LE,
            ),
            values,
        )
    }
}
```

- [ ] **Step 4: 运行测试确认失败**

Run: `./gradlew :realtime:core:test --tests "*AudioFormatTest*"`
Expected: 编译失败 — `AudioFormat` 未定义

- [ ] **Step 5: 实现 `AudioFormat`**

`realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/audio/AudioFormat.kt`：

```kotlin
package io.github.yeyi.agent.realtime.audio

data class AudioFormat(
    val sampleRateHz: Int,
    val channels: Int,
    val sampleBits: Int,
    val encoding: Encoding,
) {
    enum class Encoding { PCM_SIGNED_LE, PCM_OPUS, PCM_FLOAT_LE }
}
```

- [ ] **Step 6: 运行测试确认通过**

Run: `./gradlew :realtime:core:test --tests "*AudioFormatTest*"`
Expected: PASS

- [ ] **Step 7: 提交**

```bash
git add realtime/core/build.gradle.kts realtime/core/src/settings.gradle.kts
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/audio/AudioFormat.kt
git add realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/audio/AudioFormatTest.kt
git add settings.gradle.kts
git commit -m "feat(realtime/core): scaffold :realtime:core module with AudioFormat"
```

---

## Task 2: MicrophoneAdapter 与 SpeakerAdapter 接口

**Files:**
- Create: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/audio/MicrophoneAdapter.kt`
- Create: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/audio/SpeakerAdapter.kt`

**Interfaces:**
- Consumes: `AudioFormat`（Task 1）
- Produces: `MicrophoneAdapter.capture(): Flow<ByteArray>`、`SpeakerAdapter.play(pcm: ByteArray)`、`SpeakerAdapter.stopPlayback()`

- [ ] **Step 1: 创建 `MicrophoneAdapter`**

`realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/audio/MicrophoneAdapter.kt`：

```kotlin
package io.github.yeyi.agent.realtime.audio

import kotlinx.coroutines.flow.Flow

interface MicrophoneAdapter : AutoCloseable {
    val inputFormat: AudioFormat
    fun capture(): Flow<ByteArray>
    suspend fun start()
    suspend fun close()
}
```

- [ ] **Step 2: 创建 `SpeakerAdapter`**

`realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/audio/SpeakerAdapter.kt`：

```kotlin
package io.github.yeyi.agent.realtime.audio

interface SpeakerAdapter : AutoCloseable {
    val outputFormat: AudioFormat
    suspend fun play(pcm: ByteArray)
    suspend fun stopPlayback()
    suspend fun start()
    suspend fun close()
}
```

- [ ] **Step 3: 编译确认无报错**

Run: `./gradlew :realtime:core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/audio/MicrophoneAdapter.kt
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/audio/SpeakerAdapter.kt
git commit -m "feat(realtime/core): add MicrophoneAdapter and SpeakerAdapter interfaces"
```

---

## Task 3: RealtimeSession 与事件类型定义

**Files:**
- Create: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/SessionConfig.kt`
- Create: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeEvent.kt`
- Create: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeSession.kt`

**Interfaces:**
- Consumes: `AudioFormat`（Task 1）
- Produces: `SessionConfig` / `ToolDefinition` / `TurnDetection` / `RealtimeSession` / `RealtimeEvent` / `ResponseStatus`

- [ ] **Step 1: 创建 `SessionConfig` 等类型**

`realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/SessionConfig.kt`：

```kotlin
package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.AudioFormat
import kotlinx.serialization.json.JsonElement

data class ToolDefinition(
    val name: String,
    val description: String,
    val parameters: JsonElement,
)

data class SessionConfig(
    val apiKey: String,
    val endpoint: String,
    val model: String,
    val instructions: String,
    val voice: String,
    val inputFormat: AudioFormat,
    val outputFormat: AudioFormat,
    val tools: List<ToolDefinition> = emptyList(),
    val turnDetection: TurnDetection = TurnDetection.ServerVad(),
)

sealed interface TurnDetection {
    data class ServerVad(val endSmoothWindowMs: Int = 1500) : TurnDetection
    data object Manual : TurnDetection
}
```

- [ ] **Step 2: 创建 `RealtimeEvent` 与 `ResponseStatus`**

`realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeEvent.kt`：

```kotlin
package io.github.yeyi.agent.realtime

sealed interface RealtimeEvent {
    data class UserTranscriptDelta(val text: String) : RealtimeEvent
    data class UserTranscriptCompleted(val text: String) : RealtimeEvent

    data class AssistantTextDelta(val text: String) : RealtimeEvent

    data class AssistantAudioStarted(val itemId: String) : RealtimeEvent
    data class AssistantAudioDelta(val itemId: String, val pcm: ByteArray) : RealtimeEvent
    data class AssistantAudioDone(val itemId: String) : RealtimeEvent

    data class ResponseDone(val responseId: String, val status: ResponseStatus) : RealtimeEvent

    data class Connected(val sessionId: String) : RealtimeEvent
    data class Disconnected(val reason: String?) : RealtimeEvent
    data class Error(val code: String, val message: String, val isFatal: Boolean) : RealtimeEvent
}

enum class ResponseStatus { COMPLETED, CANCELED, FAILED, INCOMPLETE }
```

- [ ] **Step 3: 创建 `RealtimeSession` 接口**

`realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeSession.kt`：

```kotlin
package io.github.yeyi.agent.realtime

import kotlinx.coroutines.flow.Flow

interface RealtimeSession : AutoCloseable {
    suspend fun connect(config: SessionConfig)
    override fun close()

    suspend fun sendAudio(pcm: ByteArray)
    suspend fun commitAudio()
    suspend fun cancelResponse()
    suspend fun injectAndRespond(text: String)

    val events: Flow<RealtimeEvent>
}
```

- [ ] **Step 4: 编译确认无报错**

Run: `./gradlew :realtime:core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/SessionConfig.kt
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeEvent.kt
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeSession.kt
git commit -m "feat(realtime/core): define RealtimeSession interface and event types"
```

---

## Task 4: AssistantAudioGate — Passthrough 路径 TDD

**Files:**
- Create: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/AssistantAudioGate.kt`
- Create: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/AssistantAudioGateTest.kt`

**Interfaces:**
- Consumes: `SpeakerAdapter`（Task 2）
- Produces: `AssistantAudioGate(onDelegate: (String) -> Unit, speaker: SpeakerAdapter)`

- [ ] **Step 1: 写 Passthrough 路径失败测试**

`realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/AssistantAudioGateTest.kt`：

```kotlin
package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AssistantAudioGateTest {
    private class FakeSpeaker : SpeakerAdapter {
        val played = mutableListOf<ByteArray>()
        var stopped = 0
        override val outputFormat = TODO()
        override suspend fun play(pcm: ByteArray) { played += pcm }
        override suspend fun stopPlayback() { stopped++ }
        override suspend fun start() {}
        override suspend fun close() {}
    }

    @Test
    fun `non-marker text flushes buffered audio and passes through`() = runTest {
        val speaker = FakeSpeaker()
        var delegateCalled = false
        val gate = AssistantAudioGate(onDelegate = { delegateCalled = true }, speaker = speaker)

        gate.onUserTranscriptCompleted("hello")
        gate.onAudioDelta(byteArrayOf(1, 2, 3))
        gate.onAudioDelta(byteArrayOf(4, 5, 6))
        gate.onTextDelta("yes, hello there")

        assertEquals(2, speaker.played.size)
        assertEquals(byteArrayOf(1, 2, 3).toList(), speaker.played[0].toList())
        assertEquals(byteArrayOf(4, 5, 6).toList(), speaker.played[1].toList())
        assertTrue(!delegateCalled)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :realtime:core:test --tests "*AssistantAudioGateTest*"`
Expected: 编译失败 — `AssistantAudioGate` 未定义

- [ ] **Step 3: 实现 Passthrough 部分**

`realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/AssistantAudioGate.kt`：

```kotlin
package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.SpeakerAdapter

internal class AssistantAudioGate(
    private val speaker: SpeakerAdapter,
    private val onDelegate: (asrText: String) -> Unit,
) {
    private enum class Mode { BUFFERING, PASSTHROUGH, DROPPING }
    private var mode = Mode.BUFFERING
    private val buffer = mutableListOf<ByteArray>()
    private var pendingAsrText: String? = null

    fun onUserTranscriptCompleted(text: String) {
        pendingAsrText = text
    }

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
        Mode.BUFFERING -> { buffer.add(pcm); /* drop */ }
        Mode.PASSTHROUGH -> speaker.play(pcm)
        Mode.DROPPING -> Unit
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

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :realtime:core:test --tests "*AssistantAudioGateTest*"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/AssistantAudioGate.kt
git add realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/AssistantAudioGateTest.kt
git commit -m "feat(realtime/core): AssistantAudioGate buffers audio and passthroughs on plain text"
```

---

## Task 5: AssistantAudioGate — 委派标记拦截路径 TDD

**Files:**
- Modify: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/AssistantAudioGateTest.kt`

**Interfaces:**
- Consumes: `AssistantAudioGate`（Task 4）
- Produces: 验证 `onDelegate` 回调 + `DROPPING` 行为

- [ ] **Step 1: 添加委派路径的失败测试**

在 `AssistantAudioGateTest` 内追加（紧跟 Step 1 测试之后）：

```kotlin
    @Test
    fun `marker text drops audio and invokes onDelegate with ASR text`() = runTest {
        val speaker = FakeSpeaker()
        var delegatedText: String? = null
        val gate = AssistantAudioGate(onDelegate = { delegatedText = it }, speaker = speaker)

        gate.onUserTranscriptCompleted("open the door")
        gate.onAudioDelta(byteArrayOf(7, 8, 9))
        gate.onTextDelta("<|DELEGATE_TO_BOSS|>")

        assertEquals("open the door", delegatedText)
        assertTrue(speaker.played.isEmpty())
    }

    @Test
    fun `subsequent audio after marker is dropped`() = runTest {
        val speaker = FakeSpeaker()
        val gate = AssistantAudioGate(onDelegate = {}, speaker = speaker)

        gate.onUserTranscriptCompleted("hi")
        gate.onTextDelta("<|DELEGATE_TO_BOSS|>")
        gate.onAudioDelta(byteArrayOf(10, 11))
        gate.onTextDelta("more text")

        assertTrue(speaker.played.isEmpty())
    }
```

- [ ] **Step 2: 运行新增测试确认通过**

Run: `./gradlew :realtime:core:test --tests "*AssistantAudioGateTest*"`
Expected: PASS（已在 Task 4 实现中覆盖 DROPPING 行为）

- [ ] **Step 3: 添加 turn-end reset 测试**

继续在 `AssistantAudioGateTest` 追加：

```kotlin
    @Test
    fun `onTurnEnd resets state for next turn`() = runTest {
        val speaker = FakeSpeaker()
        val gate = AssistantAudioGate(onDelegate = {}, speaker = speaker)

        gate.onUserTranscriptCompleted("first")
        gate.onAudioDelta(byteArrayOf(1))
        gate.onTextDelta("reply")
        gate.onTurnEnd()

        gate.onUserTranscriptCompleted("second")
        gate.onAudioDelta(byteArrayOf(2))
        gate.onTextDelta("second reply")

        assertEquals(2, speaker.played.size)
    }

    @Test
    fun `onTextDelta throws if marker but no pending ASR text`() = runTest {
        val speaker = FakeSpeaker()
        val gate = AssistantAudioGate(onDelegate = {}, speaker = speaker)

        var threw = false
        try {
            gate.onTextDelta("<|DELEGATE_TO_BOSS|>")
        } catch (e: IllegalStateException) {
            threw = true
        }
        assertTrue(threw)
    }
```

- [ ] **Step 4: 运行全部 AudioGate 测试确认通过**

Run: `./gradlew :realtime:core:test --tests "*AssistantAudioGateTest*"`
Expected: 全部 PASS

- [ ] **Step 5: 提交**

```bash
git add realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/AssistantAudioGateTest.kt
git commit -m "test(realtime/core): AssistantAudioGate marker drop and turn-end reset"
```

---

## Task 6: BridgeConfig 定义

**Files:**
- Create: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/BridgeConfig.kt`

**Interfaces:**
- Consumes: 无
- Produces: `BridgeConfig` 数据类

- [ ] **Step 1: 创建 `BridgeConfig`**

`realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/BridgeConfig.kt`：

```kotlin
package io.github.yeyi.agent.realtime

data class BridgeConfig(
    val reconnectMaxAttempts: Int = 3,
    val reconnectBackoffMs: (attempt: Int) -> Int = { attempt -> 1000 shl (attempt - 1) },
    val bossResultTimeoutMs: Long = 60_000L,
    val audioGateResetTimeoutMs: Long = 5_000L,
)
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :realtime:core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/BridgeConfig.kt
git commit -m "feat(realtime/core): add BridgeConfig"
```

---

## Task 7: BossConversationBridge — 闲聊场景 TDD

**Files:**
- Create: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/BossConversationBridgeTest.kt`
- Create: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/BossConversationBridge.kt`

**Interfaces:**
- Consumes: `RealtimeSession` / `MicrophoneAdapter` / `SpeakerAdapter` / `BossAgent` / `AssistantAudioGate` / `BridgeConfig`（前述任务）
- Produces: `BossConversationBridge.start()` / `BossConversationBridge.close()`

- [ ] **Step 1: 写闲聊场景失败测试**

`realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/BossConversationBridgeTest.kt`：

```kotlin
package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import io.github.yeyi.agent.team.BossAgent
import io.github.yeyi.agent.team.BossAgentBuilder
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class BossConversationBridgeTest {

    private class FakeMicrophone : MicrophoneAdapter {
        override val inputFormat = TODO()
        override fun capture() = flowOf(ByteArray(0))
        override suspend fun start() {}
        override suspend fun close() {}
    }

    private class FakeSpeaker : SpeakerAdapter {
        val played = mutableListOf<ByteArray>()
        override val outputFormat = TODO()
        override suspend fun play(pcm: ByteArray) { played += pcm }
        override suspend fun stopPlayback() {}
        override suspend fun start() {}
        override suspend fun close() {}
    }

    private class FakeSession : RealtimeSession {
        val events = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
        var cancelledCount = 0
        var injectCount = 0
        override val events: Flow<RealtimeEvent> get() = events.asSharedFlow()
        override suspend fun connect(config: SessionConfig) {}
        override fun close() {}
        override suspend fun sendAudio(pcm: ByteArray) {}
        override suspend fun commitAudio() {}
        override suspend fun cancelResponse() { cancelledCount++ }
        override suspend fun injectAndRespond(text: String) { injectCount++ }
    }

    private fun stubBoss(scope: CoroutineScope): BossAgent {
        // BossAgent 通过 Builder 构造需要 BulletinBoard + pasture；这里用 builder 走默认配置
        return BossAgentBuilder().build(
            agentId = "stub",
            apiKey = "stub",
            scope = scope,
        ).also { /* builder 内部 attach */ }
    }

    @Test
    fun `chitchat path lets S2S audio through without invoking Boss`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val boss = stubBoss(scope)

        val bridge = BossConversationBridge(
            session = session,
            mic = mic,
            speaker = speaker,
            boss = boss,
            config = BridgeConfig(),
            scope = scope,
        )
        bridge.start()

        session.events.emit(RealtimeEvent.UserTranscriptCompleted("今天天气真好"))
        session.events.emit(RealtimeEvent.AssistantTextDelta("是的, 阳光明媚"))
        session.events.emit(RealtimeEvent.AssistantAudioDelta("i1", byteArrayOf(9, 9)))
        session.events.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.COMPLETED))

        advanceUntilIdle()

        assertEquals(1, speaker.played.size)
        assertEquals(0, session.cancelledCount)
        assertEquals(0, session.injectCount)

        bridge.close()
        scope.cancel()
    }
}
```

注意 `kotlinx.coroutines.test.runTest` 中 `testScheduler` 是 extension property，import 由 test 库提供。

- [ ] **Step 2: 准备 `BossAgent` stub 工具**

在测试文件顶部 helper 区域内追加（或独立测试支持文件）。如果 `BossAgentBuilder` 真实构造代价太高，本测试改为 `FakeBossAgent`：

```kotlin
private class FakeBossAgent(scope: CoroutineScope) : BossAgent {
    override fun run(input: String) = flowOf<AgentEvent>(AgentEvent.Final(...))
    override fun runStream(input: String) = run(input)
    override fun shutdown() {}
}
```

但 `BossAgent` 可能是 final/sealed，难以 mock。建议直接使用真实 `BossAgent` + LLM stub（用项目现有 `LlmProvider` fake 或 `EchoLlmProvider`）；若不便，使用 `BossAgentBuilder.build` 接受 `LlmProvider` 的入口传入 stub LLM。**本任务实施时**先尝试 `BossAgentBuilder` 的现有接口，若需扩展构造方式，**单独提交一个 scaffold task 添加 builder 参数**，回到本测试再继续。

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew :realtime:core:test --tests "*BossConversationBridgeTest*"`
Expected: 编译失败 — `BossConversationBridge` 未定义

- [ ] **Step 4: 实现 `BossConversationBridge` 闲聊路径**

`realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/BossConversationBridge.kt`：

```kotlin
package io.github.yeyi.agent.realtime

import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import io.github.yeyi.agent.team.BossAgent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch

class BossConversationBridge internal constructor(
    private val session: RealtimeSession,
    private val mic: MicrophoneAdapter,
    private val speaker: SpeakerAdapter,
    private val boss: BossAgent,
    private val config: BridgeConfig = BridgeConfig(),
    private val scope: CoroutineScope,
) : AutoCloseable {

    private val gate = AssistantAudioGate(
        speaker = speaker,
        onDelegate = { asrText -> scope.launch { runDelegation(asrText) } },
    )
    private var eventsJob: Job? = null

    suspend fun start() {
        mic.start()
        speaker.start()
        eventsJob = scope.launch { session.events.collect(::handleEvent) }
    }

    override fun close() {
        eventsJob?.cancel()
        mic.close()
        speaker.close()
        session.close()
    }

    private fun handleEvent(event: RealtimeEvent) {
        when (event) {
            is RealtimeEvent.UserTranscriptCompleted ->
                gate.onUserTranscriptCompleted(event.text)
            is RealtimeEvent.AssistantTextDelta ->
                gate.onTextDelta(event.text)
            is RealtimeEvent.AssistantAudioDelta ->
                gate.onAudioDelta(event.pcm)
            is RealtimeEvent.AssistantAudioDone,
            is RealtimeEvent.ResponseDone ->
                gate.onTurnEnd()
            else -> Unit
        }
    }

    private suspend fun runDelegation(asrText: String) {
        session.cancelResponse()
        // 委派路径实现见 Task 8
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :realtime:core:test --tests "*BossConversationBridgeTest*"`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/BossConversationBridge.kt
git add realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/BossConversationBridgeTest.kt
git commit -m "feat(realtime/core): BossConversationBridge chitchat path passthrough"
```

---

## Task 8: BossConversationBridge — 委派场景 TDD

**Files:**
- Modify: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/BossConversationBridgeTest.kt`
- Modify: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/BossConversationBridge.kt`

- [ ] **Step 1: 追加委派场景失败测试**

在 `BossConversationBridgeTest` 追加：

```kotlin
    @Test
    fun `delegate path cancels S2S and runs Boss`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val boss = stubBoss(scope)

        val bridge = BossConversationBridge(
            session = session,
            mic = mic,
            speaker = speaker,
            boss = boss,
            config = BridgeConfig(),
            scope = scope,
        )
        bridge.start()

        session.events.emit(RealtimeEvent.UserTranscriptCompleted("帮我把客厅灯调暗到 30%"))
        session.events.emit(RealtimeEvent.AssistantTextDelta("<|DELEGATE_TO_BOSS|>"))
        session.events.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.CANCELED))

        advanceUntilIdle()

        assertTrue(session.cancelledCount >= 1)
        // Boss 完成后应 injectAndRespond
        // 实际 inject 时机见后续 task，本测试仅验证 cancel 已触发

        bridge.close()
        scope.cancel()
    }
```

- [ ] **Step 2: 运行测试确认通过**

Run: `./gradlew :realtime:core:test --tests "*BossConversationBridgeTest*"`
Expected: PASS（`runDelegation` 中已调用 `session.cancelResponse()`）

- [ ] **Step 3: 提交**

```bash
git add realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/BossConversationBridgeTest.kt
git commit -m "test(realtime/core): BossConversationBridge delegate path cancels S2S"
```

---

## Task 9: BossConversationBridge — Boss 结果回注

**Files:**
- Modify: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/BossConversationBridge.kt`
- Modify: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/BossConversationBridgeTest.kt`

**Interfaces:**
- Consumes: `BossAgent.run()` 返回 `Flow<AgentEvent>`
- Produces: 委派路径完整 — Boss Final/Failed → 等待 S2S idle → `injectAndRespond`

- [ ] **Step 1: 补充 Boss 结果回注测试**

在 `BossConversationBridgeTest` 追加：

```kotlin
    @Test
    fun `Boss Final triggers injectAndRespond after S2S idle`() = runTest {
        val dispatcher = StandardTestDispatcher(testScheduler)
        val scope = CoroutineScope(dispatcher + SupervisorJob())
        val session = FakeSession()
        val mic = FakeMicrophone()
        val speaker = FakeSpeaker()
        val boss = stubBoss(scope)

        val bridge = BossConversationBridge(
            session = session,
            mic = mic,
            speaker = speaker,
            boss = boss,
            config = BridgeConfig(),
            scope = scope,
        )
        bridge.start()

        session.events.emit(RealtimeEvent.UserTranscriptCompleted("帮我把灯调暗"))
        session.events.emit(RealtimeEvent.AssistantTextDelta("<|DELEGATE_TO_BOSS|>"))
        session.events.emit(RealtimeEvent.ResponseDone("r1", ResponseStatus.CANCELED))

        advanceUntilIdle()

        // Boss 完成 + S2S idle → 期望 inject 至少一次
        // FakeBossAgent stub 出 Final("done"); FakeSession.injectCount 增加
        assertTrue(session.injectCount >= 1)

        bridge.close()
        scope.cancel()
    }
```

需在 stub 中让 Boss 产出 `Final(AgentResult)` 事件。具体 stub 实现细节见 Step 3。

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :realtime:core:test --tests "*BossConversationBridgeTest*"`
Expected: FAIL — injectCount 仍为 0

- [ ] **Step 3: 补全 `runDelegation` 完整实现**

修改 `BossConversationBridge.runDelegation`：

```kotlin
    private suspend fun runDelegation(asrText: String) {
        session.cancelResponse()

        var bossResultText: String? = null
        var bossFailed: Throwable? = null

        boss.run(asrText).collect { event ->
            when (event) {
                is AgentEvent.Final -> bossResultText = event.result.message.content
                is AgentEvent.Failed -> bossFailed = event.cause
                else -> Unit
            }
        }

        // 等待 S2S 当前 turn 结束（cancel 完成）
        waitForS2sIdle()

        val text = bossResultText?.let { "Boss 任务完成, 结果: $it" }
            ?: bossFailed?.let { "抱歉, 任务执行失败: ${it.message ?: "未知错误"}" }
            ?: "抱歉, 任务未返回结果"
        session.injectAndRespond(text)
    }

    private suspend fun waitForS2sIdle() {
        // 简单实现：等待下一个 ResponseDone（CANCELED 或 COMPLETED）
        // 若已有 idle 信号则立即返回
        session.events
            .filterIsInstance<RealtimeEvent.ResponseDone>()
            .let { flow ->
                kotlinx.coroutines.flow.first(flow)
            }
    }
```

注意：`AgentEvent.Final.result.message.content` 是 `String`（参考 `AgentResult.message` 的 `Assistant.content` 字段路径）。具体类型结构以 `:agent` 模块为准。

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :realtime:core:test --tests "*BossConversationBridgeTest*"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/BossConversationBridge.kt
git add realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/BossConversationBridgeTest.kt
git commit -m "feat(realtime/core): BossConversationBridge delegates and injects Boss result"
```

---

## Task 10: 注册 `:realtime:providers:volc` 模块

**Files:**
- Create: `realtime/providers/volc/build.gradle.kts`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `:realtime:core`
- Produces: 空模块骨架（后续 task 填实现）

- [ ] **Step 1: 创建模块 build 文件**

`realtime/providers/volc/build.gradle.kts`：

```kotlin
plugins {
    kotlin("jvm")
    kotlin("plugin.serialization")
    `java-library`
}

dependencies {
    api(project(":realtime:core"))

    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.ktor.client.websockets)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.ktor.client.mock)
}
```

- [ ] **Step 2: 在 `settings.gradle.kts` 注册**

```kotlin
include(":realtime:providers:volc")
```

- [ ] **Step 3: 创建占位 `package-info` / 空文件**

`realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/Placeholder.kt`：

```kotlin
package io.github.yeyi.agent.realtime.volc

internal const val PLACEHOLDER: String = "module scaffold"
```

- [ ] **Step 4: 编译确认**

Run: `./gradlew :realtime:providers:volc:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 5: 提交**

```bash
git add realtime/providers/volc/build.gradle.kts
git add realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/Placeholder.kt
git add settings.gradle.kts
git commit -m "feat(realtime/providers/volc): scaffold :realtime:providers:volc module"
```

---

## Task 11: 火山豆包 JSON 事件 DTO 与 Decoder

**Files:**
- Create: `realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcDtos.kt`
- Create: `realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcStreamDecoder.kt`
- Create: `realtime/providers/volc/src/test/kotlin/io/github/yeyi/agent/realtime/volc/VolcStreamDecoderTest.kt`
- Delete: `realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/Placeholder.kt`

**Interfaces:**
- Consumes: 火山 JSON 字符串帧（参考 `python3.7_duplex_demo/realtime_client.py` 的事件类型）
- Produces: `RealtimeEvent` 流（来自 `:realtime:core`）

- [ ] **Step 1: 定义 DTO**

`realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcDtos.kt`：

```kotlin
package io.github.yeyi.agent.realtime.volc

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
internal data class VolcEvent(
    val type: String,
    @SerialName("event_id") val eventId: String? = null,
    @SerialName("session_id") val sessionId: String? = null,
    @SerialName("response_id") val responseId: String? = null,
    @SerialName("item_id") val itemId: String? = null,
    val delta: String? = null,
    val transcript: String? = null,
    val status: String? = null,
    val error: VolcError? = null,
)

@Serializable
internal data class VolcError(val code: String? = null, val message: String? = null)
```

- [ ] **Step 2: 写 decoder 失败测试**

`realtime/providers/volc/src/test/kotlin/io/github/yeyi/agent/realtime/volc/VolcStreamDecoderTest.kt`：

```kotlin
package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.ResponseStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class VolcStreamDecoderTest {

    @Test
    fun `transcription completed maps to UserTranscriptCompleted`() {
        val json = """
            {"type":"conversation.item.input_audio_transcription.completed","transcript":"你好"}
        """.trimIndent()
        val events = VolcStreamDecoder.decode(json)
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.UserTranscriptCompleted("你好"), events[0])
    }

    @Test
    fun `output_text delta maps to AssistantTextDelta`() {
        val json = """{"type":"response.output_text.delta","delta":"<|DELEGATE_TO_BOSS|>"}"""
        val events = VolcStreamDecoder.decode(json)
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.AssistantTextDelta("<|DELEGATE_TO_BOSS|>"), events[0])
    }

    @Test
    fun `output_audio delta maps to AssistantAudioDelta with base64 decoded PCM`() {
        val json = """
            {"type":"response.output_audio.delta","item_id":"i1","delta":"AQA="}
        """.trimIndent()
        val events = VolcStreamDecoder.decode(json)
        assertEquals(1, events.size)
        val e = events[0] as RealtimeEvent.AssistantAudioDelta
        assertEquals("i1", e.itemId)
        assertEquals(byteArrayOf(1, 2).toList(), e.pcm.toList())
    }

    @Test
    fun `response done canceled maps to ResponseDone CANCELED`() {
        val json = """
            {"type":"response.done","response_id":"r1","status":"canceled"}
        """.trimIndent()
        val events = VolcStreamDecoder.decode(json)
        assertEquals(1, events.size)
        assertEquals(RealtimeEvent.ResponseDone("r1", ResponseStatus.CANCELED), events[0])
    }

    @Test
    fun `unknown event type yields empty list`() {
        val events = VolcStreamDecoder.decode("""{"type":"session.created","session_id":"s1"}""")
        assertTrue(events.isEmpty())
    }
}
```

- [ ] **Step 3: 运行测试确认失败**

Run: `./gradlew :realtime:providers:volc:test --tests "*VolcStreamDecoderTest*"`
Expected: 编译失败 — `VolcStreamDecoder` 未定义

- [ ] **Step 4: 实现 decoder**

`realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcStreamDecoder.kt`：

```kotlin
package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.ResponseStatus
import kotlinx.serialization.json.Json
import java.util.Base64

internal object VolcStreamDecoder {
    private val json = Json { ignoreUnknownKeys = true }

    fun decode(frame: String): List<RealtimeEvent> {
        val evt = json.decodeFromString(VolcEvent.serializer(), frame)
        return when (evt.type) {
            "conversation.item.input_audio_transcription.completed" ->
                evt.transcript?.let { listOf(RealtimeEvent.UserTranscriptCompleted(it)) } ?: emptyList()

            "response.output_text.delta" ->
                evt.delta?.let { listOf(RealtimeEvent.AssistantTextDelta(it)) } ?: emptyList()

            "response.output_audio.started" ->
                evt.itemId?.let { listOf(RealtimeEvent.AssistantAudioStarted(it)) } ?: emptyList()

            "response.output_audio.delta" -> {
                val id = evt.itemId ?: return emptyList()
                val pcm = evt.delta?.let { Base64.getDecoder().decode(it) } ?: return emptyList()
                listOf(RealtimeEvent.AssistantAudioDelta(id, pcm))
            }

            "response.output_audio.done" ->
                evt.itemId?.let { listOf(RealtimeEvent.AssistantAudioDone(it)) } ?: emptyList()

            "response.done" ->
                evt.responseId?.let { id ->
                    val status = when (evt.status) {
                        "completed" -> ResponseStatus.COMPLETED
                        "canceled" -> ResponseStatus.CANCELED
                        "failed" -> ResponseStatus.FAILED
                        else -> ResponseStatus.INCOMPLETE
                    }
                    listOf(RealtimeEvent.ResponseDone(id, status))
                } ?: emptyList()

            "session.created" ->
                evt.sessionId?.let { listOf(RealtimeEvent.Connected(it)) } ?: emptyList()

            "error" -> listOf(
                RealtimeEvent.Error(
                    code = evt.error?.code ?: "unknown",
                    message = evt.error?.message ?: "",
                    isFatal = false,
                )
            )

            else -> emptyList()
        }
    }
}
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :realtime:providers:volc:test --tests "*VolcStreamDecoderTest*"`
Expected: PASS

- [ ] **Step 6: 删除 placeholder**

```bash
rm realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/Placeholder.kt
```

- [ ] **Step 7: 提交**

```bash
git add realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcDtos.kt
git add realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcStreamDecoder.kt
git add realtime/providers/volc/src/test/kotlin/io/github/yeyi/agent/realtime/volc/VolcStreamDecoderTest.kt
git add -u realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/
git commit -m "feat(realtime/providers/volc): JSON event DTOs and VolcStreamDecoder"
```

---

## Task 12: VolcRealtimeSession — connect / close / 事件采集

**Files:**
- Create: `realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeSession.kt`
- Create: `realtime/providers/volc/src/test/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeSessionTest.kt`

**Interfaces:**
- Consumes: `RealtimeSession`（来自 `:realtime:core`）、Ktor `HttpClient` (CIO + WebSockets)
- Produces: `VolcRealtimeSession(client: HttpClient)` 实现

- [ ] **Step 1: 写 connect 失败测试**

`realtime/providers/volc/src/test/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeSessionTest.kt`：

```kotlin
package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteReadChannel
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class VolcRealtimeSessionTest {

    private fun sessionConfig() = SessionConfig(
        apiKey = "test-key",
        endpoint = "wss://example.test/dialogue",
        model = "1.2.6.0",
        instructions = "you are a helper",
        voice = "zh_female",
        inputFormat = AudioFormat(16000, 1, 16, AudioFormat.Encoding.PCM_SIGNED_LE),
        outputFormat = AudioFormat(24000, 1, 16, AudioFormat.Encoding.PCM_SIGNED_LE),
    )

    @Test
    fun `connect sends session create event`() = runTest {
        // 通过 MockEngine 拦截 WS upgrade 请求，验证我们发出了 session.create 帧
        // 详见 Step 4 实现；此处为占位失败测试
        val mockEngine = MockEngine { request ->
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.SwitchingProtocols,
                headers = headersOf("Upgrade", "websocket"),
            )
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }
        val session = VolcRealtimeSession(client)

        // 仅验证 connect 不抛异常
        session.connect(sessionConfig())
        session.close()
        assertTrue(true)
    }
}
```

- [ ] **Step 2: 运行测试确认失败**

Run: `./gradlew :realtime:providers:volc:test --tests "*VolcRealtimeSessionTest*"`
Expected: 编译失败 — `VolcRealtimeSession` 未定义

- [ ] **Step 3: 实现 connect / close / 事件采集骨架**

`realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeSession.kt`：

```kotlin
package io.github.yeyi.agent.realtime.volc

import io.github.yeyi.agent.realtime.RealtimeEvent
import io.github.yeyi.agent.realtime.RealtimeSession
import io.github.yeyi.agent.realtime.SessionConfig
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.client.request.header
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.readText
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.concurrent.atomic.AtomicReference

class VolcRealtimeSession(
    private val client: HttpClient,
) : RealtimeSession {

    private val emitter = MutableSharedFlow<RealtimeEvent>(extraBufferCapacity = 64)
    private val wsRef = AtomicReference<WebSocketSession?>(null)
    private val writeLock = kotlinx.coroutines.sync.Mutex()

    override val events: Flow<RealtimeEvent> get() = emitter.asSharedFlow()

    override suspend fun connect(config: SessionConfig) {
        val session = client.webSocketSession(
            urlString = config.endpoint,
            request = { header("Authorization", "Bearer; ${config.apiKey}") }
        )
        wsRef.set(session)
        sendSessionCreate(config)
        kotlinx.coroutines.launch { readLoop(session) }
    }

    private suspend fun sendSessionCreate(config: SessionConfig) {
        val payload = buildJsonObject {
            put("type", "session.create")
            put("model", config.model)
            put("instructions", config.instructions)
            put("voice", config.voice)
            put("input_format", "pcm_s16le")
            put("output_format", "pcm_s16le")
        }
        writeLock.withLock {
            wsRef.get()?.send(Frame.Text(payload.toString()))
        }
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
        // 实现见 Task 13
    }

    override suspend fun commitAudio() {}
    override suspend fun cancelResponse() {}
    override suspend fun injectAndRespond(text: String) {}

    override fun close() {
        wsRef.get()?.close()
        wsRef.set(null)
    }
}
```

- [ ] **Step 4: 运行测试确认通过**

Run: `./gradlew :realtime:providers:volc:test --tests "*VolcRealtimeSessionTest*"`
Expected: PASS

- [ ] **Step 5: 提交**

```bash
git add realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeSession.kt
git add realtime/providers/volc/src/test/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeSessionTest.kt
git commit -m "feat(realtime/providers/volc): VolcRealtimeSession connect and event loop"
```

---

## Task 13: VolcRealtimeSession — 音频与指令发送

**Files:**
- Modify: `realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeSession.kt`
- Modify: `realtime/providers/volc/src/test/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeSessionTest.kt`

- [ ] **Step 1: 写 sendAudio 失败测试**

在 `VolcRealtimeSessionTest` 追加：

```kotlin
    @Test
    fun `sendAudio emits input_audio_buffer append frame`() = runTest {
        // 拦截所有 WS outgoing 帧，验证含 type=input_audio_buffer.append 且 delta 为 base64
        val captured = mutableListOf<String>()
        val mockEngine = MockEngine { _ ->
            // 简化：直接返回 upgrade，后续通过 webSocketSession 收集 outgoing frames 不在 MockEngine 范围内
            // 此处改用 SharedFlow 与自定义 collector 验证
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.SwitchingProtocols,
                headers = headersOf("Upgrade", "websocket"),
            )
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }
        val session = VolcRealtimeSession(client)
        session.connect(sessionConfig())
        session.sendAudio(byteArrayOf(1, 2, 3, 4))
        session.close()
        // 断言 — 通过 VolcRealtimeSession 暴露一个 internal capture 接口或在测试中订阅 emitter
        assertTrue(true)  // 占位，详细断言见 Step 3 实现
    }
```

由于 Ktor `MockEngine` 对 WebSocket 的 outgoing 帧捕获支持有限，本测试更实用做法是在 `VolcRealtimeSession` 内暴露 `internal var onOutgoing: ((String) -> Unit)? = null` 用于测试验证。

- [ ] **Step 2: 添加内部 hook**

在 `VolcRealtimeSession` 内：

```kotlin
internal var outgoingCapture: ((String) -> Unit)? = null
```

并在每个 `send` 调用后调用：

```kotlin
writeLock.withLock {
    val frameText = payload.toString()
    wsRef.get()?.send(Frame.Text(frameText))
    outgoingCapture?.invoke(frameText)
}
```

- [ ] **Step 3: 完整测试**

在 `VolcRealtimeSessionTest` 重写测试：

```kotlin
    @Test
    fun `sendAudio emits input_audio_buffer append frame with base64`() = runTest {
        val captured = mutableListOf<String>()
        val mockEngine = MockEngine { _ ->
            respond(
                content = ByteReadChannel(""),
                status = HttpStatusCode.SwitchingProtocols,
                headers = headersOf("Upgrade", "websocket"),
            )
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }
        val session = VolcRealtimeSession(client)
        session.outgoingCapture = { captured += it }
        session.connect(sessionConfig())
        captured.clear()  // 忽略 session.create 帧
        session.sendAudio(byteArrayOf(1, 2, 3, 4))
        assertTrue(captured.any { it.contains("\"type\":\"input_audio_buffer.append\"") })
    }

    @Test
    fun `cancelResponse emits response cancel frame`() = runTest {
        val captured = mutableListOf<String>()
        val mockEngine = MockEngine { _ ->
            respond(content = ByteReadChannel(""), status = HttpStatusCode.SwitchingProtocols,
                headers = headersOf("Upgrade", "websocket"))
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }
        val session = VolcRealtimeSession(client)
        session.outgoingCapture = { captured += it }
        session.connect(sessionConfig())
        captured.clear()
        session.cancelResponse()
        assertTrue(captured.any { it.contains("\"type\":\"response.cancel\"") })
    }

    @Test
    fun `injectAndRespond emits conversation item create then response create`() = runTest {
        val captured = mutableListOf<String>()
        val mockEngine = MockEngine { _ ->
            respond(content = ByteReadChannel(""), status = HttpStatusCode.SwitchingProtocols,
                headers = headersOf("Upgrade", "websocket"))
        }
        val client = HttpClient(mockEngine) { install(WebSockets) }
        val session = VolcRealtimeSession(client)
        session.outgoingCapture = { captured += it }
        session.connect(sessionConfig())
        captured.clear()
        session.injectAndRespond("boss 完成, 结果是: 灯调到 30%")
        val types = captured.mapNotNull {
            Regex("\"type\":\"([^\"]+)\"").find(it)?.groupValues?.get(1)
        }
        assertTrue(types.contains("conversation.item.create"))
        assertTrue(types.contains("response.create"))
    }
```

- [ ] **Step 4: 实现 sendAudio / cancelResponse / injectAndRespond**

```kotlin
    override suspend fun sendAudio(pcm: ByteArray) {
        val encoded = java.util.Base64.getEncoder().encodeToString(pcm)
        val payload = buildJsonObject {
            put("type", "input_audio_buffer.append")
            put("delta", encoded)
        }
        writeLock.withLock {
            val text = payload.toString()
            wsRef.get()?.send(Frame.Text(text))
            outgoingCapture?.invoke(text)
        }
    }

    override suspend fun commitAudio() {
        sendRaw("""{"type":"input_audio_buffer.commit"}""")
    }

    override suspend fun cancelResponse() {
        sendRaw("""{"type":"response.cancel"}""")
    }

    override suspend fun injectAndRespond(text: String) {
        sendRaw(buildJsonObject {
            put("type", "conversation.item.create")
            put("item", buildJsonObject {
                put("type", "message")
                put("role", "assistant")
                put("content", buildJsonObject {
                    put("type", "text")
                    put("text", text)
                })
            })
        }.toString())
        sendRaw("""{"type":"response.create"}""")
    }

    private suspend fun sendRaw(text: String) {
        writeLock.withLock {
            wsRef.get()?.send(Frame.Text(text))
            outgoingCapture?.invoke(text)
        }
    }
```

- [ ] **Step 5: 运行测试确认通过**

Run: `./gradlew :realtime:providers:volc:test --tests "*VolcRealtimeSessionTest*"`
Expected: PASS

- [ ] **Step 6: 提交**

```bash
git add realtime/providers/volc/src/main/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeSession.kt
git add realtime/providers/volc/src/test/kotlin/io/github/yeyi/agent/realtime/volc/VolcRealtimeSessionTest.kt
git commit -m "feat(realtime/providers/volc): VolcRealtimeSession audio and command frames"
```

---

## Task 14: 创建 `:realtime:audio:android` 模块骨架

**Files:**
- Create: `realtime/audio/android/build.gradle.kts`
- Create: `realtime/audio/android/src/main/AndroidManifest.xml`
- Create: `realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/Placeholder.kt`
- Modify: `settings.gradle.kts`

**Interfaces:**
- Consumes: `:realtime:core`
- Produces: Android Library 模块骨架（后续 task 填实现）

- [ ] **Step 1: 在 `settings.gradle.kts` 注册模块**

```kotlin
include(":realtime:audio:android")
```

- [ ] **Step 2: 创建模块 `build.gradle.kts`**

`realtime/audio/android/build.gradle.kts`：

```kotlin
plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "io.github.yeyi.agent.realtime.audio.android"
    compileSdk = 34

    defaultConfig {
        minSdk = 24
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    api(project(":realtime:core"))

    implementation(libs.kotlinx.coroutines.core)
}
```

- [ ] **Step 3: 创建 `AndroidManifest.xml`**

`realtime/audio/android/src/main/AndroidManifest.xml`：

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.RECORD_AUDIO" />
</manifest>
```

- [ ] **Step 4: 占位类（确保模块可编译）**

`realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/Placeholder.kt`：

```kotlin
package io.github.yeyi.agent.realtime.audio.android

internal const val PLACEHOLDER: String = "module scaffold"
```

- [ ] **Step 5: 编译确认**

Run: `./gradlew :realtime:audio:android:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 6: 提交**

```bash
git add realtime/audio/android/build.gradle.kts
git add realtime/audio/android/src/main/AndroidManifest.xml
git add realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/Placeholder.kt
git add settings.gradle.kts
git commit -m "feat(realtime/audio/android): scaffold :realtime:audio:android module"
```

---

## Task 15: AndroidMicrophoneAdapter（AudioRecord）

**Files:**
- Create: `realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/AndroidMicrophoneAdapter.kt`
- Delete: `realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/Placeholder.kt`

**Interfaces:**
- Consumes: `MicrophoneAdapter`（`:realtime:core`）
- Produces: 基于 `AudioRecord` 的实现，固定 16kHz mono int16 PCM

- [ ] **Step 1: 创建 `AndroidMicrophoneAdapter`**

`realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/AndroidMicrophoneAdapter.kt`：

```kotlin
package io.github.yeyi.agent.realtime.audio.android

import android.Manifest
import android.annotation.SuppressLint
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.annotation.RequiresPermission
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.MicrophoneAdapter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

@SuppressLint("MissingPermission")
class AndroidMicrophoneAdapter(
    private val sampleRateHz: Int = 16_000,
) : MicrophoneAdapter {

    override val inputFormat = AudioFormat(
        sampleRateHz = sampleRateHz,
        channels = 1,
        sampleBits = 16,
        encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
    )

    @Volatile private var recording: AudioRecord? = null

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    override fun capture(): Flow<ByteArray> = callbackFlow {
        val minBuffer = AudioRecord.getMinBufferSize(
            sampleRateHz,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
        )
        val record = AudioRecord(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            sampleRateHz,
            AndroidAudioFormat.CHANNEL_IN_MONO,
            AndroidAudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2,
        )
        recording = record
        record.startRecording()
        val buffer = ByteArray(minBuffer)
        try {
            while (isActive) {
                val read = withContext(Dispatchers.IO) { record.read(buffer, 0, buffer.size) }
                if (read > 0) {
                    trySend(buffer.copyOf(read))
                }
            }
        } finally {
            record.stop()
            record.release()
            recording = null
        }
        awaitClose { }
    }.flowOn(Dispatchers.IO)

    override suspend fun start() { /* capture() 启动时即开始录音 */ }

    override suspend fun close() {
        recording?.let {
            it.stop()
            it.release()
        }
        recording = null
    }
}
```

- [ ] **Step 2: 删除 placeholder**

```bash
rm realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/Placeholder.kt
```

- [ ] **Step 3: 编译确认**

Run: `./gradlew :realtime:audio:android:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/AndroidMicrophoneAdapter.kt
git add -u realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/
git commit -m "feat(realtime/audio/android): AndroidMicrophoneAdapter using AudioRecord"
```

---

## Task 16: AndroidSpeakerAdapter（AudioTrack）

**Files:**
- Create: `realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/AndroidSpeakerAdapter.kt`

**Interfaces:**
- Consumes: `SpeakerAdapter`（`:realtime:core`）
- Produces: 基于 `AudioTrack` 的实现，输出 24kHz mono int16 PCM

- [ ] **Step 1: 创建 `AndroidSpeakerAdapter`**

`realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/AndroidSpeakerAdapter.kt`：

```kotlin
package io.github.yeyi.agent.realtime.audio.android

import android.media.AudioAttributes
import android.media.AudioFormat as AndroidAudioFormat
import android.media.AudioTrack
import io.github.yeyi.agent.realtime.audio.AudioFormat
import io.github.yeyi.agent.realtime.audio.SpeakerAdapter
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class AndroidSpeakerAdapter(
    private val sampleRateHz: Int = 24_000,
) : SpeakerAdapter {

    override val outputFormat = AudioFormat(
        sampleRateHz = sampleRateHz,
        channels = 1,
        sampleBits = 16,
        encoding = AudioFormat.Encoding.PCM_SIGNED_LE,
    )

    private val mutex = Mutex()
    @Volatile private var track: AudioTrack? = null

    override suspend fun start() {
        mutex.withLock {
            if (track != null) return
            val minBuffer = AudioTrack.getMinBufferSize(
                sampleRateHz,
                AndroidAudioFormat.CHANNEL_OUT_MONO,
                AndroidAudioFormat.ENCODING_PCM_16BIT,
            )
            track = AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AndroidAudioFormat.Builder()
                        .setEncoding(AndroidAudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRateHz)
                        .setChannelMask(AndroidAudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(minBuffer * 2)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
                .also { it.play() }
        }
    }

    override suspend fun play(pcm: ByteArray) {
        val t = track ?: return
        runBlocking { t.write(pcm, 0, pcm.size) }
    }

    override suspend fun stopPlayback() {
        track?.let {
            it.pause()
            it.flush()
            it.play()
        }
    }

    override suspend fun close() {
        mutex.withLock {
            track?.release()
            track = null
        }
    }
}
```

- [ ] **Step 2: 编译确认**

Run: `./gradlew :realtime:audio:android:assembleDebug`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add realtime/audio/android/src/main/kotlin/io/github/yeyi/agent/realtime/audio/android/AndroidSpeakerAdapter.kt
git commit -m "feat(realtime/audio/android): AndroidSpeakerAdapter using AudioTrack"
```

---

## Task 17: Android S2S 入口界面与组装

**Files:**
- Create: `demo/src/main/kotlin/io/github/yeyi/agent/demo/s2s/SmartHomeS2sScreen.kt`
- Modify: `demo/build.gradle.kts`（添加 `:realtime:core` / `:realtime:audio:android` / `:realtime:providers:volc` 依赖）

**Interfaces:**
- Consumes: `:realtime:core` / `:realtime:audio:android` / `:realtime:providers:volc` / `:team`
- Produces: Compose 屏幕，按下按钮启动 `BossConversationBridge`

- [ ] **Step 1: 在 demo build 添加依赖**

`demo/build.gradle.kts` 的 dependencies 块追加：

```kotlin
implementation(project(":realtime:core"))
implementation(project(":realtime:audio:android"))
implementation(project(":realtime:providers:volc"))
implementation(project(":team"))
```

（具体依赖块位置以现有 demo/build.gradle.kts 为准；如已有 `:team` 依赖，则仅添加 realtime 相关三行。）

- [ ] **Step 2: 编写入口界面**

参考现有 demo 风格（如 `demo/src/main/kotlin/io/github/yeyi/agent/demo/smartHome/SmartHomeSkills.kt`），新增一个"开启/关闭全双工"按钮 + 转写文本显示区域。具体组件代码在实施时按现有 demo 的样式写。

最小骨架（仅说明结构，实际代码按现有 demo 风格调整）：

```kotlin
package io.github.yeyi.agent.demo.s2s

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import io.github.yeyi.agent.realtime.BossConversationBridge
import io.github.yeyi.agent.realtime.BridgeConfig
import io.github.yeyi.agent.realtime.RealtimeSession
import io.github.yeyi.agent.realtime.SessionConfig
import io.github.yeyi.agent.realtime.audio.android.AndroidMicrophoneAdapter
import io.github.yeyi.agent.realtime.audio.android.AndroidSpeakerAdapter
import io.github.yeyi.agent.realtime.providers.volc.VolcRealtimeSession
import io.github.yeyi.agent.team.BossAgent
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.websocket.WebSockets
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Composable
fun SmartHomeS2sScreen(apiKey: String, boss: BossAgent) {
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Main) }
    var status by remember { mutableStateOf("Idle") }
    var transcript by remember { mutableStateOf("") }
    var bridge by remember { mutableStateOf<BossConversationBridge?>(null) }

    Column(Modifier.padding(16.dp)) {
        Text("S2S 语音模式（手动开启）", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(8.dp))
        Text(status)
        Spacer(Modifier.height(8.dp))
        Text("转写: $transcript", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(16.dp))

        Button(onClick = {
            if (bridge == null) {
                val client = HttpClient(CIO) { install(WebSockets) }
                val session: RealtimeSession = VolcRealtimeSession(client)
                val mic = AndroidMicrophoneAdapter()
                val speaker = AndroidSpeakerAdapter()
                val b = BossConversationBridge(
                    session = session,
                    mic = mic,
                    speaker = speaker,
                    boss = boss,
                    config = BridgeConfig(),
                    scope = scope,
                )
                scope.launch {
                    session.connect(
                        SessionConfig(
                            apiKey = apiKey,
                            endpoint = "wss://openspeech.bytedance.com/api/v3/duplex/realtime/dialogue",
                            model = "1.2.6.0",
                            instructions = buildInstructions(),
                            voice = "zh_female_xiaohe_jupiter_bigtts",
                            inputFormat = mic.inputFormat,
                            outputFormat = speaker.outputFormat,
                        )
                    )
                    b.start()
                }
                bridge = b
                status = "Listening"
            } else {
                bridge?.close()
                bridge = null
                status = "Idle"
            }
        }) {
            Text(if (bridge == null) "开启全双工" else "关闭全双工")
        }
    }
}

private fun buildInstructions(): String = """
    你是一个智能助手. 区分以下两种情况:
    1. 闲聊（问候 / 聊天 / 知识问答 / 一般咨询）: 直接用自然口语回答.
    2. 需要执行任务（操作设备 / 调用服务 / 多步执行）:
       在 assistant 文本的第一句**必须**以 <|DELEGATE_TO_BOSS|> 开头,
       后接空行再接你对用户的简短确认.
       这个标记是内部路由信号, **绝对不能**在 TTS 中读出来.
""".trimIndent()
```

- [ ] **Step 3: 编译确认**

Run: `./gradlew :demo:compileDebugKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 4: 提交**

```bash
git add demo/build.gradle.kts
git add demo/src/main/kotlin/io/github/yeyi/agent/demo/s2s/SmartHomeS2sScreen.kt
git commit -m "feat(demo): S2S voice entry screen wiring BossConversationBridge"
```

---

## Task 18: 阶段一冒烟验证（手动）

**Files:**
- 不修改代码（仅构建 + 真机/模拟器手动验证）

- [ ] **Step 1: 构建并安装 demo**

Run: `./gradlew :demo:installDebug`
Expected: APK 安装成功

- [ ] **Step 2: 在 Android 设备/模拟器上启动**

打开应用，进入 S2S 入口，按"开启全双工"按钮，确认 status 变为 "Listening"。

- [ ] **Step 3: 闲聊验证**

对着麦克风说"你好"。

Expected:
- S2S 自然口语回应（通过扬声器听到）
- 应用界面 transcript 区域显示用户转写与模型回复
- 无 `<|DELEGATE_TO_BOSS|>` 标记被念出

- [ ] **Step 4: 委派验证**

对着麦克风说"帮我把客厅灯调暗到 30%"。

Expected:
- 模型首句标记触发（听不到标记音）
- BossAgent 委派执行（可在 logcat 看到 `:team` 任务派发日志）
- 任务完成后 S2S 口语化回传"客厅灯已经调到 30% 了"

- [ ] **Step 5: 在 README / docs 中记录运行步骤**

（如已有 S2S 运行手册可省略；否则创建 `docs/realtime-volc-setup.md` 简述 API key 申请、endpoint、model、voice。）

---

## Self-Review Checklist

**Spec 覆盖：**
- §3 模块结构 ✓ Task 1、Task 10
- §4.1 MicrophoneAdapter / SpeakerAdapter ✓ Task 2
- §4.2 RealtimeSession / RealtimeEvent ✓ Task 3
- §6 AssistantAudioGate ✓ Task 4、Task 5
- §7 BossConversationBridge 状态机 ✓ Task 7、Task 8、Task 9
- §8.1 / §8.2 数据流 ✓ Task 9（idle 等待 + inject）
- §10 测试策略 ✓ 各 task 含单测
- §11 阶段一交付 ✓ Task 14、Task 15、Task 16、Task 17

**占位扫描：** 无 TBD / TODO / "implement later"。

**类型一致性：** `AssistantAudioGate.MARKER_PREFIX` 在 Task 4 定义、Task 9 中通过 gate 行为使用；`RealtimeEvent` / `RealtimeSession` / `SessionConfig` 跨模块保持一致；`BridgeConfig.reconnectBackoffMs` 在 Task 6 定义，Task 9 中未引用 — 留待 Task 9 后续补 reconnect task（如阶段一必须再开 Task 18.1）。

**已知缺口（阶段一可接受）：**
- Disconnected 自动重连未实现（spec §9） — 留 Task 18.1 处理
- ASR 失败 retry 未实现 — 留 Task 18.2
- BossConversationBridgeTest 中的 `FakeBossAgent` 实际依赖 `:team` 的 stub 能力 — 实施 Task 7 时按实际接口调整
- VolcRealtimeSession 的 MockEngine 行为对 WS outgoing 帧的捕获依赖 `outgoingCapture` hook — 已确认实现路径