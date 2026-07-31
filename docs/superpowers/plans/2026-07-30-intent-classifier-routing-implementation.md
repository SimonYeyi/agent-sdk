# Intent Classifier Routing Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让外部通过实现 `classifier` 字段接管委派决策，且让 handler 抑制 s2s 模型的本地 TTS 输出，改用 ack 作为替代回复。`classifier != null` 时**取代**原 marker 路径，`classifier == null` 时维持原 marker 路径。

**Architecture:** `IntentionClassifier.classify(asr)` 在 `UserTranscriptCompleted` 时同步阻塞调用，根据返回的 `Intention` 类型决定是否触发 `onReplacementAck` 和 `delegation.run`。`DelegationHandler.handle` 改 suspend，返回 `RealtimeEvent?`，null 表示抑制该事件。`currentRoundIntent` 状态机驱动 TTS 抑制逻辑。

**Tech Stack:** Kotlin Multiplatform/JVM, kotlinx.coroutines, Kotlin Test, `runTest`

---

## 假设 / 约束

- **服务端严格 ASR-first 模式**：假设 s2s 服务端在生成首个 assistant 事件（`AssistantTextDelta` / `AssistantAudioStarted`）之前，必先发出对应轮的 `UserTranscriptCompleted`。  
  后果：若服务端在 `UserTranscriptStarted` 与 `UserTranscriptCompleted` 之间提前发出 assistant 事件（即"真空期"），`currentRoundIntent` 仍为 `null`，`shouldSuppressTts()` 返回 `false`，事件不会被抑制。  
  此限制不在 client spec 修复（详见 spec 讨论记录）；服务端升级到 speculative TTS 模式时需重新评估。

---

## 文件结构

| 文件 | 动作 | 职责 |
|---|---|---|
| `realtime/core/.../RealtimeDelegation.kt` | Modify | Task 1: 新增 `IntentionClassifier` 接口，修改 `Intention` 类型，新增 `ack` 扩展属性。Task 2: 改 `DelegationHandler` 构造参数（新增 `onReplacementAck`），改 `appendInstructions`，重写 `handle`（suspend + nullable），移除 `dispatch` |
| `realtime/core/.../RealtimeAppliance.kt` | Modify | 适配 `DelegationHandler` 新签名（前置 Task 2），collect 循环调整 |
| `realtime/providers/volc/.../VolcRealtimeAppliance.kt` | Modify | 适配 `DelegationHandler` 新签名（前置 Task 2），collect 循环调整 |
| `realtime/core/.../DelegationHandlerTest.kt` | Modify | 新增测试用例（含 import 更新） |
| `realtime/core/.../RealtimeApplianceTest.kt` | - | `FakeDelegation` 不变；`classifier` 字段有默认值 `null` |

---

### Task 1: 修改 `Intention` 类型并新增 `IntentionClassifier` 接口

**Files:**
- Modify: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeDelegation.kt`

- [ ] **Step 1: 将 `Casual` 改为 data class**

```kotlin
// 旧（如当前是 object）
public object Casual : Intention

// 新
public data class Casual(val ack: String?) : Intention
```

- [ ] **Step 2: 新增 `IntentionClassifier` 接口**

```kotlin
public interface IntentionClassifier {
    public suspend fun classify(asr: String): Intention
}
```

- [ ] **Step 3: 新增 `ack` 扩展属性**

```kotlin
public val Intention?.ack: String?
    get() = when (this) {
        is Intention.Delegated -> ack
        is Intention.Casual -> ack
        null -> null
    }
```

- [ ] **Step 4: 验证编译**

Run: `cd realtime && ../gradlew :realtime:core:compileKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 2: 重构 `DelegationHandler`

**Files:**
- Modify: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeDelegation.kt`

**契约说明：**
- `onReply` 用于普通 reply（marker 路径触发的 `Confirmation/Success/Failure`）
- `onReplacementAck` 用于 classifier 抑制路径，**自带 cancel + pause + drain**，`onReplacementAck` 实现方**必须**在内部完成：
  - 取消 s2s 服务端响应（`session.cancelResponse()` 或 `DIRECTIVE_CANCEL_CURRENT_DIALOG`）
  - 暂停本地音频播放（`speaker.stopPlayback()` 或 `DIRECTIVE_PAUSE_PLAYER`）
  - 清空本地音频队列（`drainAudioChannel()`，仅 RealtimeAppliance；Volc 由 SDK 处理）

- [ ] **Step 1: 新增构造参数 `onReplacementAck`**

```kotlin
public class DelegationHandler(
    private val delegation: RealtimeDelegation,
    private val scopeProvider: () -> CoroutineScope?,
    private val onReply: suspend (String) -> Unit,
    private val onReplacementAck: suspend (String) -> Unit,  // 新增
)
```

- [ ] **Step 2: 新增 `currentRoundIntent` 状态**

```kotlin
private var currentRoundIntent: Intention? = null
```

- [ ] **Step 3: 修改 `appendInstructions` 方法**

```kotlin
public fun appendInstructions(base: String): String {
    if (delegation.classifier != null) return base
    val capabilityList = delegation.capabilities.joinToString("\n") { "- $it" }
    return "$base\n\n${DELEGATION_PROTOCOL.replace(CAPABILITIES_PLACEHOLDER, capabilityList)}"
}
```

- [ ] **Step 4: 重写 `handle` 方法为 suspend + nullable**

```kotlin
public suspend fun handle(event: RealtimeEvent): RealtimeEvent? {
    when (event) {
        is RealtimeEvent.UserTranscriptStarted -> {
            currentRoundIntent = null
            return event
        }
        is RealtimeEvent.UserTranscriptCompleted -> {
            pendingAsr = event.text
            delegation.classifier?.let { classifier ->
                val intent = try {
                    classifier.classify(event.text)
                } catch (_: Throwable) {
                    Intention.Casual(null)
                }
                if (intent is Intention.Delegated) {
                    runDelegation(intent.task)
                }
                intent.ack?.let { ack -> onReplacementAck(ack) }
                currentRoundIntent = intent
            }
            return event
        }
        is RealtimeEvent.AssistantTextDelta,
        is RealtimeEvent.AssistantAudioStarted,
        is RealtimeEvent.AssistantAudioDelta,
        is RealtimeEvent.AssistantAudioDone,
        is RealtimeEvent.ResponseDone,
        is RealtimeEvent.ResponseCanceled -> {
            if (shouldSuppressTts()) return null
            if (delegation.classifier == null
                && event is RealtimeEvent.AssistantTextDelta
                && event.text.startsWith(DELEGATION_MARKER)
            ) {
                pendingAsr?.let { runDelegation(it) }
                return event.copy(text = event.text.removePrefix(DELEGATION_MARKER))
            }
            return event
        }
        else -> return event
    }
}

private fun shouldSuppressTts(): Boolean = currentRoundIntent?.ack != null
```

**关键机制说明：**
1. `handle` 改 suspend：`UserTranscriptCompleted` 内同步阻塞等 classify 结果。
   **契约**：`classifier.classify` 必须在 s2s 模型生成首个 `AssistantTextDelta` 之前返回。
2. `handle` 返回 `RealtimeEvent?`：null = 跳过本地副作用（`handleEvent`）与 emit。该语义由 collect 循环中 `delegationHandler != null && handled == null` 联合判断触发，区别于「无 handler」场景（后者走 `emit(event)` fall-through）。
3. `currentRoundIntent` 状态机：
   - `UserTranscriptStarted` 重置为 null（新一轮开始）
   - `UserTranscriptCompleted` 内 classify 后设置
   - `shouldSuppressTts()` 基于 `currentRoundIntent` 决定 assistant 事件是否放行
4. **事件流阻塞是有意的**：classify 阻塞期间，session.events 上到达的事件在 collect queue 里**按到达顺序堆积**，handler.handle 串行处理（每个事件处理完才处理下一个），不并行。堆积的 AssistantTextDelta / AudioDelta 被 `shouldSuppressTts()` 过滤返回 null。
5. **抑制路径与 onReplacementAck 同步触发**：`onReplacementAck` 在 handle(Completed) 内同步执行 `cancelResponse + pause + drain`，确保 cancel 早于堆积事件的处理。

- [ ] **Step 5: 确认 `dispatch` 方法已移除**

检查文件是否存在 `dispatch` 私有方法，若存在则删除（已并入 `handle(UserTranscriptCompleted)`）。

- [ ] **Step 6: 验证编译**

Run: `cd realtime && ../gradlew :realtime:core:compileKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 3: 适配 `RealtimeAppliance`

**前置：Task 2 完成**（`DelegationHandler` 必须先有 `onReplacementAck` 构造参数，本 Task 才能编译通过）。

**Files:**
- Modify: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt`

- [ ] **Step 1: 更新 `DelegationHandler` 构造调用**

```kotlin
private val delegationHandler: DelegationHandler? = delegation?.let { delegation ->
    DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = { text -> session.injectAndRespond(text) },
        onReplacementAck = { ack ->
            session.cancelResponse()
            speaker.stopPlayback()
            drainAudioChannel()
            // 注：服务端 tts 记录修改（问题 3）后续讨论后再加入。暂时使用追加的方式填充历史
            session.injectAndRespond(ack)
        },
    )
}
```

**注**：`onReplacementAck` 内暂不调 `conversation.item.delete/update/truncate` 或 `speech_text_buffer.replacement`，问题 3 后续讨论。

- [ ] **Step 2: 调整 collect 循环**

```kotlin
scope?.launch {
    session.events.collect { event ->
        val finalEvent = when {
            delegationHandler == null -> event
            else -> delegationHandler.handle(event) ?: return@collect
        }
        handleEvent(finalEvent)
        (events as MutableSharedFlow).emit(finalEvent)
    }
}
```

- [ ] **Step 3: 验证编译**

Run: `cd realtime && ../gradlew :realtime:core:compileKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 4: 适配 `VolcRealtimeAppliance`

**前置：Task 2 完成**（同 Task 3）。

**Files:**
- Modify: `realtime/providers/volc/.../VolcRealtimeAppliance.kt`

- [ ] **Step 1: 更新 `DelegationHandler` 构造调用**

```kotlin
private val delegationHandler: DelegationHandler? = delegation?.let {
    DelegationHandler(
        delegation = it,
        scopeProvider = { scope },
        onReply = { text ->
            val frames = protocolAdapter.commitSpeechTextFrame(text)
            frames.forEach { frame ->
                engine?.sendDirective(
                    SpeechEngineDefines.DIRECTIVE_SEND_UPLINK_EVENT,
                    frame.payload.toString(),
                )
            }
        },
        onReplacementAck = { ack ->
            engine?.sendDirective(
                SpeechEngineDefines.DIRECTIVE_PAUSE_PLAYER,
                "",
            )
            engine?.sendDirective(
                SpeechEngineDefines.DIRECTIVE_CANCEL_CURRENT_DIALOG,
                "",
            )
            val frames = protocolAdapter.commitSpeechTextFrame(ack)
            frames.forEach { frame ->
                engine?.sendDirective(
                    SpeechEngineDefines.DIRECTIVE_SEND_UPLINK_EVENT,
                    frame.payload.toString(),
                )
            }
        },
    )
}
```

- [ ] **Step 2: 调整 collect 循环**

```kotlin
scope?.launch {
    protocolAdapter.events.collect { event ->
        if (delegationHandler == null) {
            eventEmitter.emit(event)
        } else {
            delegationHandler.handle(event)?.let { eventEmitter.emit(it) }
        }
    }
}
```

- [ ] **Step 3: 验证编译**

Run: `cd realtime && ../gradlew :realtime:providers:volc:compileKotlin`
Expected: BUILD SUCCESSFUL

---

### Task 5: 新增测试用例

**Files:**
- Modify: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/DelegationHandlerTest.kt`

- [ ] **Step 1: 扩展 FakeDelegation 支持 classifier + 更新 imports**

扩展 `FakeDelegation` 以支持 `classifier` 字段：

```kotlin
private class FakeDelegation(
    override val capabilities: List<String>,
    val classifier: IntentionClassifier? = null,
) : RealtimeDelegation {
    // ... existing code ...
    override val replies: Flow<DelegationReply> = replyEmitter.asSharedFlow()
    val dispatched = Channel<String>(Channel.UNLIMITED)
    // ... existing code ...
}
```

补全 imports（当前文件仅 `assertEquals`，新用例需要 `assertFalse` / `assertNull` / `assertNotNull`）：

```kotlin
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
```

- [ ] **Step 2: 新增 T1 — classifier != null + Delegated**

```kotlin
@Test
fun `classifier delegable triggers onReplacementAck and runDelegation`() = runTest {
    val task = "打开空调"
    val ack = "好的"
    val delegation = FakeDelegation(
        capabilities = emptyList(),
        classifier = object : IntentionClassifier {
            override suspend fun classify(asr: String) = Intention.Delegated(ack, task)
        },
    )
    var replacementAckCalledWith: String? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val handler = DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = {},
        onReplacementAck = { replacementAckCalledWith = it },
    )

    handler.handle(RealtimeEvent.UserTranscriptCompleted("帮我开空调"))

    assertEquals(ack, replacementAckCalledWith)
    assertEquals(task, withTimeout(5_000) { delegation.dispatched.receive() })
    scope.cancel()
}
```

- [ ] **Step 3: 新增 T2 — classifier != null + Casual(ack)**

```kotlin
@Test
fun `classifier casual with ack triggers onReplacementAck only`() = runTest {
    val ack = "好的"
    val delegation = FakeDelegation(
        capabilities = emptyList(),
        classifier = object : IntentionClassifier {
            override suspend fun classify(asr: String) = Intention.Casual(ack)
        },
    )
    var replacementAckCalledWith: String? = null
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val handler = DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = {},
        onReplacementAck = { replacementAckCalledWith = it },
    )

    handler.handle(RealtimeEvent.UserTranscriptCompleted("你好"))

    assertEquals(ack, replacementAckCalledWith)
    scope.cancel()
}
```

- [ ] **Step 4: 新增 T3 — classifier != null + Casual(null)**

```kotlin
@Test
fun `classifier casual with null ack does not trigger onReplacementAck`() = runTest {
    val delegation = FakeDelegation(
        capabilities = emptyList(),
        classifier = object : IntentionClassifier {
            override suspend fun classify(asr: String) = Intention.Casual(null)
        },
    )
    var replacementAckCalled = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val handler = DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = {},
        onReplacementAck = { replacementAckCalled = true },
    )

    handler.handle(RealtimeEvent.UserTranscriptCompleted("你好"))

    assertFalse(replacementAckCalled)
    scope.cancel()
}
```

- [ ] **Step 5: 新增 T4 — classifier 抛异常**

```kotlin
@Test
fun `classifier exception is caught and treated as Casual null`() = runTest {
    val delegation = FakeDelegation(
        capabilities = emptyList(),
        classifier = object : IntentionClassifier {
            override suspend fun classify(asr: String): Intention {
                throw RuntimeException(" classify failed")
            }
        },
    )
    var replacementAckCalled = false
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val handler = DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = {},
        onReplacementAck = { replacementAckCalled = true },
    )

    // should not throw
    handler.handle(RealtimeEvent.UserTranscriptCompleted("你好"))

    assertFalse(replacementAckCalled)
    scope.cancel()
}
```

- [ ] **Step 6: 新增 T5 — classifier != null 抑制全部 assistant 事件**

覆盖 spec 验收 #5 全部 6 个事件类型（`AssistantTextDelta` / `AssistantAudioStarted` / `AssistantAudioDelta` / `AssistantAudioDone` / `ResponseDone` / `ResponseCanceled`）。这些分支在 `handle` 的 `when` 中共享同一 `shouldSuppressTts()` 判定，单测覆盖所有类型避免 6 分支因类型擦除漏检。

```kotlin
@Test
fun `classifier with ack suppresses all assistant event types`() = runTest {
    val delegation = FakeDelegation(
        capabilities = emptyList(),
        classifier = object : IntentionClassifier {
            override suspend fun classify(asr: String) = Intention.Delegated("好的", "task")
        },
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val handler = DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = {},
        onReplacementAck = {},
    )

    handler.handle(RealtimeEvent.UserTranscriptCompleted("开空调"))

    assertNull(handler.handle(RealtimeEvent.AssistantTextDelta("hi")))
    assertNull(handler.handle(RealtimeEvent.AssistantAudioStarted))
    assertNull(handler.handle(RealtimeEvent.AssistantAudioDelta(byteArrayOf(1))))
    assertNull(handler.handle(RealtimeEvent.AssistantAudioDone))
    assertNull(handler.handle(RealtimeEvent.ResponseDone))
    assertNull(handler.handle(RealtimeEvent.ResponseCanceled))
    scope.cancel()
}
```

- [ ] **Step 7: 新增 T6 — UserTranscriptStarted 重置抑制状态**

```kotlin
@Test
fun `UserTranscriptStarted resets suppression state`() = runTest {
    val delegation = FakeDelegation(
        capabilities = emptyList(),
        classifier = object : IntentionClassifier {
            override suspend fun classify(asr: String) = Intention.Delegated("好的", "task")
        },
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val handler = DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = {},
        onReplacementAck = {},
    )

    handler.handle(RealtimeEvent.UserTranscriptCompleted("开空调"))
    assertNull(handler.handle(RealtimeEvent.AssistantTextDelta("hi")))

    handler.handle(RealtimeEvent.UserTranscriptStarted("test"))

    val notSuppressed = handler.handle(RealtimeEvent.AssistantTextDelta("hello"))
    assertNotNull(notSuppressed)
    scope.cancel()
}
```

- [ ] **Step 8: 新增 T7 — classifier == null 维持 marker 路径**

```kotlin
@Test
fun `classifier null falls back to marker path`() = runTest {
    val delegation = FakeDelegation(capabilities = listOf("灯光控制"))
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val handler = DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = {},
        onReplacementAck = {},
    )

    handler.handle(RealtimeEvent.UserTranscriptCompleted("帮我开灯"))
    val result = handler.handle(RealtimeEvent.AssistantTextDelta("|好的，正在开灯"))

    assertEquals(RealtimeEvent.AssistantTextDelta("好的，正在开灯"), result)
    scope.cancel()
}
```

- [ ] **Step 9: 新增 T8 — classifier != null 时 appendInstructions 返回 base**

```kotlin
@Test
fun `classifier not null appendInstructions returns base unchanged`() = runTest {
    val delegation = FakeDelegation(
        capabilities = listOf("灯光控制"),
        classifier = object : IntentionClassifier {
            override suspend fun classify(asr: String) = Intention.Casual(null)
        },
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val handler = DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = {},
        onReplacementAck = {},
    )

    val result = handler.appendInstructions("你是助手")

    assertEquals("你是助手", result)
    assertFalse(result.contains("委派协议"))
    scope.cancel()
}
```

- [ ] **Step 10: 新增 T9 — Casual(ack) 也应抑制 assistant 事件**

`shouldSuppressTts()` 判定条件是 `currentRoundIntent?.ack != null`，因此 `Casual(ack = "好的")` 与 `Delegated(ack, task)` 同样抑制。T2 仅验证 `onReplacementAck` 被调用，本用例补全抑制路径。

```kotlin
@Test
fun `Casual with ack also suppresses assistant events`() = runTest {
    val delegation = FakeDelegation(
        capabilities = emptyList(),
        classifier = object : IntentionClassifier {
            override suspend fun classify(asr: String) = Intention.Casual("好的")
        },
    )
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val handler = DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = {},
        onReplacementAck = {},
    )

    handler.handle(RealtimeEvent.UserTranscriptCompleted("你好"))

    assertNull(handler.handle(RealtimeEvent.AssistantTextDelta("hi")))
    assertNull(handler.handle(RealtimeEvent.AssistantAudioStarted))
    scope.cancel()
}
```

- [ ] **Step 11: 运行测试**

Run: `cd realtime && ../gradlew :realtime:core:test`
Expected: All tests PASS

---

### Task 6: 回归测试

**Files:**
- Run: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/RealtimeApplianceTest.kt`

`RealtimeApplianceTest` 的 `FakeDelegation`（line 105）**不需要修改**：`RealtimeDelegation.classifier` 有默认值 `null`，现有 FakeDelegation 不显式实现 `classifier` 即可走 marker 路径。

- [ ] **Step 1: 运行 RealtimeApplianceTest**

重点观察「delegate path runs delegation」用例（line 232）—— 该用例依赖 v1 marker 行为（`UserTranscriptCompleted` + `AssistantTextDelta("|好的")` → delegation 收到 asr），Task 2 重写 `handle` 后该用例必须通过才算回归通过。

Run: `cd realtime && ../gradlew :realtime:core:test --tests io.github.yeyi.agent.realtime.RealtimeApplianceTest`
Expected: All existing tests PASS

- [ ] **Step 2: 运行 DelegationHandlerTest**

Run: `cd realtime && ../gradlew :realtime:core:test --tests io.github.yeyi.agent.realtime.DelegationHandlerTest`
Expected: All tests PASS

---

### Task 7: 全模块验证

- [ ] **Step 1: 编译 realtime core 和 volc**

Run: `cd realtime && ../gradlew :realtime:core:compileKotlin :realtime:providers:volc:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 2: 运行所有 realtime core 测试**

Run: `cd realtime && ../gradlew :realtime:core:test`
Expected: All tests PASS

- [ ] **Step 3: Demo 编译验证**

Run: `cd demo && ../gradlew compileDebugKotlin`
Expected: BUILD SUCCESSFUL

---

## Classifier 实现方契约

`classifier.classify(asr)` 必须在 s2s 模型生成首个 `AssistantTextDelta` 之前返回。否则：
- `onReplacementAck` 仍触发（cancel 已发出的响应），但服务端历史里已有 assistant 回复片段
- 问题 3（删除/替换服务端历史片段）未实现，无法擦除该片段
- 用户可能听到/看到部分 s2s 原 tts

---

## 验收清单

- [ ] `classifier != null` 时 `appendInstructions` 返回 base，不追加委派协议
- [ ] `classifier != null` 时 `AssistantTextDelta` 不再剥 `|` marker
- [ ] `Delegated(ack, task)` 触发 `onReplacementAck(ack)` 与 `delegation.run(task)`
- [ ] `Casual(ack)` 当 `ack != null` 时触发 `onReplacementAck(ack)`，否则 no-op
- [ ] `Delegated(ack, task)` 与 `Casual(ack != null)` 均抑制后续 assistant 事件（6 个事件类型全覆盖）
- [ ] `UserTranscriptStarted` 重置 `currentRoundIntent = null`，新一轮恢复放行
- [ ] `UserTranscriptCompleted` 之后到达的 assistant 事件被 handle 返回 null，不 emit
- [ ] `classifier == null` 时所有现有测试不回归（含 `RealtimeApplianceTest`「delegate path runs delegation」）
- [ ] `classifier.classify` 异常被捕获，不传播到调用方

---

## 明确范围（不做）

1. 不实现 `IntentionClassifier` 的具体实现
2. 不改 `RealtimeSession` / `SessionConfig`
3. 不动 `DelegationReply` 类型
4. 不动 `runDelegation` / `start` / `DELEGATION_MARKER` / `DELEGATION_PROTOCOL` 常量
5. 不实现服务端历史片段的删除/替换（问题 3，后续讨论）；`onReplacementAck` 内仅追加 ack（`injectAndRespond(ack)` / `commitSpeechTextFrame(ack)`）即可
6. 不验证 SpeakerAdapter 内部缓冲是否在 stopPlayback 时清空
