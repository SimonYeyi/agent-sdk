# 2026-07-30 意图分类器路由设计 (v2)

## 背景

`RealtimeDelegation` 已暴露 `classifier: IntentionClassifier?` 字段（默认 `null`），
并定义了 `Intention.Delegated(ack, task)` / `Intention.Casual(ack?)`。当前
`DelegationProcessor` 通过 `InnerClassifyStrategy` / `OuterClassifyStrategy` 实现两种路径。

本次需求：让外部通过实现 `classifier` 字段接管委派决策，且让 handler 抑制
s2s 模型的本地 TTS 输出（文本 + 语音），改用 ack 作为替代回复。
`classifier != null` 时**取代**原 marker 路径，`classifier == null` 时维持
原 marker 路径。两条路径互斥，不共存。

### 待后续讨论
修改服务端 tts 记录（即从服务端历史里删除 cancel 之前的 assistant item，或用 ack 替换）。
涉及 `conversation.item.delete` / `update` / `truncate` 和
`speech_text_buffer.replacement`。本 spec 不涵盖，后续单独讨论。

## 设计

### 接口

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

public val Intention?.ack: String?
    get() = when (this) {
        is Intention.Delegated -> ack
        is Intention.Casual -> ack
        null -> null
    }
```

### 路由策略

| 条件 | 路径 |
| --- | --- |
| `classifier != null` | 意图分类路径（取代 marker） |
| `classifier == null` | 原 marker 路径（现状） |

### `DelegationProcessor` 变更

#### 构造参数

新增 `onReplacementAck: suspend (String) -> Unit` 回调，**必传**，由调用方实现，
**必须在内部完成取消本地与服务端的 tts 输出**：

- 取消 s2s 服务端响应（`session.cancelResponse()` 或 volc `DIRECTIVE_CANCEL_CURRENT_DIALOG`）
- 暂停本地音频播放（`speaker.stopPlayback()` 或 volc `DIRECTIVE_PAUSE_PLAYER`）
- 清空本地音频队列（`drainAudioChannel()`，仅 RealtimeAppliance；Volc 由 SDK 处理）

```kotlin
public class DelegationProcessor(
    private val delegation: RealtimeDelegation,
    private val scopeProvider: () -> CoroutineScope?,
    private val onReply: suspend (String) -> Unit,
    private val onReplacementAck: suspend (String) -> Unit,
)
```

`onReply` 与 `onReplacementAck` 的区别：
- `onReply` 用于普通 reply（marker 路径触发的 `Confirmation/Success/Failure`）
- `onReplacementAck` 用于 classifier 抑制路径，**自带 cancel + pause + drain**

#### `appendInstructions(base)`

```kotlin
public fun appendInstructions(base: String): String {
    if (delegation.classifier != null) return base
    val capabilityList = delegation.capabilities.joinToString("\n") { "- $it" }
    return "$base\n\n${DELEGATION_PROTOCOL.replace(CAPABILITIES_PLACEHOLDER, capabilityList)}"
}
```

`classifier != null` 时直接返回 `base`，不追加委派协议 —— 让模型不再生成
`|` marker。

#### `handle(event)` —— 改 suspend + nullable

```kotlin
public suspend fun process(event: RealtimeEvent): RealtimeEvent? {
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

**关键点**：
1. `handle` 改 suspend：`UserTranscriptCompleted` 内同步阻塞等 classify 结果。
   **契约**：`classifier.classify` 必须在 s2s 模型生成首个 AssistantTextDelta 之前返回。
2. `handle` 返回 `RealtimeEvent?`：null = 跳过本地副作用（`handleEvent`）与 emit。该语义由 collect 循环中 `delegationHandler != null && handled == null` 联合判断触发，区别于「无 handler」场景（后者走 `emit(event)` fall-through）。
3. `currentRoundIntent` 状态机：
   - `UserTranscriptStarted` 重置为 null（新一轮开始）
   - `UserTranscriptCompleted` 内 classify 后设置
   - `shouldSuppressTts()` 基于 `currentRoundIntent` 决定 assistant 事件是否放行
4. **事件流阻塞是有意的**：classify 阻塞期间，session.events 上到达的事件在 collect
   queue 里**按到达顺序堆积**，handler.handle 串行处理（每个事件处理完才处理下一个），
   不并行。堆积的 AssistantTextDelta / AudioDelta 被 `shouldSuppressTts()` 过滤返回 null。
5. **抑制路径与 onReplacementAck 同步触发**：`onReplacementAck` 在 handle(Completed) 内同步执行
   `cancelResponse + pause + drain`，确保 cancel 早于堆积事件的处理。

#### `dispatch` 方法移除

v1 的 `dispatch` 私有方法已并入 `handle(UserTranscriptCompleted)`，
不再单独存在。

#### 异常策略

`classifier.classify` 抛出的任何异常被捕获并返回 `Intention.Casual(null)`：
- handler 不委派、不调 onReplacementAck
- s2s 模型正常生成回复
- 异常不传播到 `scope`，不影响其他事件

### 调用方实现

#### RealtimeAppliance

```kotlin
private val delegationProcessor: DelegationProcessor? = delegation?.let { delegation ->
    DelegationProcessor(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = { text -> session.injectAndRespond(text) },
        onReplacementAck = { ack ->
            session.cancelResponse()
            session.events
                .filter { it is RealtimeEvent.ResponseDone || it is RealtimeEvent.ResponseCanceled || it is RealtimeEvent.Error }
                .first()
            session.injectAndRespond(ack)
        },
    )
}
```

collect 侧调整为：handle 决策先于本地副作用，三态用 `when` 压扁：

```kotlin
scope?.launch {
    session.events.collect { event ->
        val finalEvent = when {
            delegationHandler == null -> event
            else -> delegationProcessor.process(event) ?: return@collect
        }
        handleEvent(finalEvent)
        (events as MutableSharedFlow).emit(finalEvent)
    }
}
```

#### VolcRealtimeAppliance

Volc 已重构为工厂函数，直接复用 `DefaultRealtimeAppliance`，委派逻辑与
`DefaultRealtimeAppliance` 完全一致，无需单独实现。

### 测试补充

1. **classifier != null + Delegated**：`UserTranscriptCompleted` 后断言
   `onReplacementAck` 被调用一次且参数为 `"好的"`，且 `delegation.dispatched.receive() == task`。
2. **classifier != null + Casual(ack = "好的")**：asr 后断言 `onReplacementAck` 被调用一次
   且参数为 `"好的"`，`delegation.run` 未被调用。
3. **classifier != null + Casual(ack = null)**：asr 后断言 `onReplacementAck` 未被调用
   （no-op，由 s2s 模型 TTS 输出），`delegation.run` 未被调用。
4. **classifier != null + 异常**：mock classifier 抛 `RuntimeException`，asr 后
   不应抛到调用方；断言 `delegation.run` 未被调用、`onReplacementAck` 未被调用
   （等价于 `Casual(null)` 路径）。
5. **classifier != null + 抑制全部 assistant 事件**：asr 完成后依次发送 6 个
   事件（`AssistantTextDelta` / `AssistantAudioStarted` / `AssistantAudioDelta` /
   `AssistantAudioDone` / `ResponseDone` / `ResponseCanceled`），均断言 handle
   返回 null。6 个分支在 `handle` 的 `when` 中共享同一 `shouldSuppressTts()` 判定，
   单测覆盖全部类型避免 6 分支因类型擦除漏检。
6. **classifier != null + Casual(ack) 也抑制 assistant 事件**：`shouldSuppressTts()`
   判定条件是 `currentRoundIntent?.ack != null`，因此 `Casual(ack = "好的")` 与
   `Delegated(ack, task)` 同样抑制。本用例区分于 #2：#2 仅验证 `onReplacementAck`
   被调用，本用例验证后续 assistant 事件被抑制。
7. **classifier != null + UserTranscriptStarted 重置**：完成一轮 Delegated 后
   发送新的 `UserTranscriptStarted`，再发送 `AssistantTextDelta`，断言 handle
   返回该 event（非 null）。
8. **classifier == null + marker 路径不变**：原有 marker 测试不回归。
9. **classifier != null + appendInstructions**：返回 base，**不**包含"委派协议"。

### 范围边界（明确不做）

- 不实现 `IntentionClassifier` 的具体实现。
- 不改 `RealtimeSession` / `SessionConfig` —— classifier 入口已由 `delegation` 提供。
- 改 `Intention` 类型：`Casual` 由 `object` 改为 `data class Casual(val ack: String?)`。
- 不动 `DelegationReply` 类型。
- 不动 `runDelegation` / `start` / `DELEGATION_MARKER` / `DELEGATION_PROTOCOL`
  常量。
- 不修改服务端 tts 记录（问题 3，后续讨论）：
  - 不调 `conversation.item.delete` / `update` / `truncate`
  - 不调 `speech_text_buffer.replacement.append` / `.commit`
  - 不扩展 `RealtimeEvent.AssistantTextDelta` / `AudioDelta` 增加 itemId 字段
- 不验证 SpeakerAdapter 内部缓冲是否在 stopPlayback 时清空（依赖现有实现）。

## 契约

**classifier 实现方契约**：`classifier.classify(asr)` 必须在 s2s 模型生成首个
`AssistantTextDelta` 之前返回。否则：
- `onReplacementAck` 仍触发（cancel 已发出的响应），但服务端历史里已有 assistant 回复片段
- 问题 3（删除/替换服务端历史片段）未实现，无法擦除该片段
- 用户可能听到/看到部分 s2s 原 tts

**服务端 ASR-first 假设**：依赖服务端在生成首个 assistant 事件之前必先发出对应轮的
`UserTranscriptCompleted`。`UserTranscriptStarted` 与 `UserTranscriptCompleted` 之间
存在「真空期」：此期间 `currentRoundIntent = null`，`shouldSuppressTts()` 返回 `false`，
服务端若提前发出 `AssistantTextDelta` / `AssistantAudioStarted` 等不会被抑制。
此限制**不在 client spec 修复**；服务端升级到 speculative TTS 模式时需重新评估
（可选方案：保留上一轮 intent 直至新 ASR 完成，但需权衡 prev `Casual(null)` 漏抑制
及恢复语义）。

## 验收

- `classifier != null` 时 marker 路径完全失效：`appendInstructions` 返回 base；
  `AssistantTextDelta` 不再剥 `|`。
- `classifier != null` 时 `Delegated(ack, task)` 触发 `onReplacementAck(ack)` 与
  `delegation.run(task)`；`Casual(ack)` 当 `ack != null` 时触发 `onReplacementAck(ack)`，
  否则 no-op。
- `classifier != null` 时 `UserTranscriptCompleted` 之后到达的 assistant 事件
  （AssistantTextDelta / AssistantAudioStarted / AssistantAudioDelta / AssistantAudioDone /
  ResponseDone / ResponseCanceled，直到下一个 `UserTranscriptStarted`）被 handle 返回 null，
  **不** emit 到 events flow。
- `classifier == null` 时所有现有测试不回归。