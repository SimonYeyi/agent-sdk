# Modality MediaArchive 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在 `MediaSource` 上加 `Local` 变体 + 引入 `MediaArchive` 抽象 + 把 `ReActAgent.buildRequest` 的多模态适配下沉到 `ModalityAdapter` + 引入 `ArchivingMemory` 外层装饰器 + 持久化路径自动归档。

**Architecture:** 三层扩展点 —— `MediaArchive` 接口契约（agent/core）+ 文件系统默认实现 `FilesystemMediaArchive`（agent/session）+ caller 自定义实现。`ModalityAdapter` 是 agent/core 的 fun interface，默认 `DefaultModalityAdapter` 在送 LLM 前完成"末条 User 的 Local → Data resolve + 跨 round 占位 + 末条 ToolResult 拆 text + 合成 User"三件事。`ArchivingMemory` 是 agent/session 的外层装饰器，写侧硬编码 1KB 阈值把 `Data` 转 `Local`。`SessionRepository` 统一路径到 per-session 目录，整 `deleteRecursively` 清理。

**Tech Stack:** Kotlin (JVM) + kotlinx.serialization (Polymorphic via @SerialName) + kotlinx.coroutines (`runTest`, `Mutex`) + kotlin.test + JUnit-style assertions.

## Global Constraints

- **commit 不 push**：每次 commit 用 `<type>(<module>): <subject>`，绝不主动 `git push`
- **commit message 必须真实映射 `git diff` 实际变更**，写 commit 前必须先看 `git diff` 核实
- **批量提交相关编辑**，不要每次跑测试就 commit
- **每次创建新 commit 而非 `--amend`**
- **`Memory.mediaArchive` 是纯 abstract 字段**（无默认实现），所有 Memory 实现必须 override。装饰器必须显式转发：`RoundsBoundedMemory` / `ReadOnlyMemory` 用 `get() = underlying.mediaArchive`（**注意：两者目前用显式 override 而非 `Memory by` delegate，详见 Task 2 Step 3-4**）；`JsonlConversation` / `ArchivingMemory` 用 `Memory by` delegate 自动转发
- **`MediaArchive` 注入到最下层**：`InMemoryMemory` 内部 new 一个 `InMemoryMediaArchive`；`JsonlBackedMemory` 构造参数接收 archive（由 `SessionRepository.hydrateSession()` 注入 `FilesystemMediaArchive`）；上层一律通过 `Memory by` / `get() = inner.mediaArchive` 透明转发
- **占位文本截断前缀**：`toTextMessage` 的 `describeMediaSource` 中 `Local -> "local fileId=${source.fileId.take(8)}"`（跟现有 `FileId` 的 `take(8)` 同步；不加 `...` 后缀，保持与 `FileId` 行对称）；`ModalityAdapter` 注入末条 User 的 `[local] fileId=完整id`（**不截断**，模型可整串调工具）
- **`archive.resolve()` 抛 `IllegalStateException`**（入参错误，不包装成 `AgentException`），让 caller 决策
- **Provider fail-fast**：OpenAI/Anthropic mapping 收到 `Local` 立即抛 `AgentException.UnsupportedContent`（adapter 应已在 `buildRequest` 内 resolve）
- **1KB 阈值硬编码**：阈值 1024 = `JsonlConversation.pageSizeThreshold / 20`（避免单图占满整 page），下沉到 `ArchivingMemory.archiveIfLarge` 私有方法，不暴露构造参数
- **`adaptModality` 下沉到 `ModalityAdapter.kt`**：从 `AgentExtensions.kt` 移除，改为 `ModalityAdapter.kt` 内的 file-private 顶层扩展函数（`private fun ChatMessage.ToolResult.adaptModality(): List<ChatMessage>`）
- **`toTextMessage` 结构不动**：只在 `describeMediaSource` 的 when 加一个 `Local` 分支；保持 public（`RoundsBoundedMemory` 跨模块使用）
- **测试文件路径**：`agent/<module>/src/test/kotlin/io/github/yeyi/agent/...`（mirror 主源码路径）
- **测试风格**：使用 `kotlin.test.Test` + `kotlin.test.assertEquals` / `assertTrue` / `assertFailsWith`；异步测试用 `kotlinx.coroutines.test.runTest`

---

## File Structure

### 新增文件
- `agent/core/src/main/kotlin/io/github/yeyi/agent/ModalityAdapter.kt` — fun interface + DefaultModalityAdapter + file-private adaptModality 扩展
- `agent/core/src/test/kotlin/io/github/yeyi/agent/ModalityAdapterTest.kt` — 7 路分支覆盖
- `agent/session/src/main/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchive.kt` — 文件系统实现
- `agent/session/src/main/kotlin/io/github/yeyi/agent/session/ArchivingMemory.kt` — 外层归档装饰器
- `agent/session/src/test/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchiveTest.kt` — IO 往返 + 缺失 ID 异常
- `agent/session/src/test/kotlin/io/github/yeyi/agent/session/ArchivingMemoryTest.kt` — 1KB 阈值 + delegate 透传
- `agent/session/src/test/kotlin/io/github/yeyi/agent/session/SessionRepositoryTest.kt` — per-session 目录 + 构造链 + deleteSession

### 修改文件
- `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ChatRequest.kt` — `MediaSource.Local` 加进 sealed interface
- `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/Memory.kt` — 加 `mediaArchive` 抽象字段 + `MediaArchive` fun interface
- `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/InMemoryMemory.kt` — 实现 `mediaArchive`，嵌套 `InMemoryMediaArchive` private class
- `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt` — 显式 `override val mediaArchive: MediaArchive get() = underlying.mediaArchive`
- `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/ReadOnlyMemory.kt` — 同上
- `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ChatRequest.kt` — `toTextMessage.describeMediaSource` 加 Local 分支
- `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt` — 加 `modalityAdapter` 必填构造参数；`buildRequest` 改走 `modalityAdapter.adapt(...)`
- `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt` — 加 `modalityAdapter()` 设置方法 + `build()` 默认 `DefaultModalityAdapter()`
- `agent/session/src/main/kotlin/io/github/yeyi/agent/session/JsonlBackedMemory.kt` — 加 `mediaArchive: MediaArchive` 构造参数
- `agent/session/src/main/kotlin/io/github/yeyi/agent/session/SessionRepository.kt` — per-session 目录 + 构造链 + deleteSession deleteRecursively
- `agent/providers/openai/src/main/kotlin/.../OpenAiMapping.kt` — `mapImageToOpenAi` + `mapAudioToOpenAi` 加 Local fail-fast 分支
- `agent/providers/anthropic/src/main/kotlin/.../AnthropicMapping.kt` — `mapImageToAnthropic` + `mapAudioToAnthropic` + `mapVideoToAnthropic` 加 Local fail-fast 分支

### 不修改文件（确认）
- `agent/session/src/main/kotlin/io/github/yeyi/agent/session/JsonlConversation.kt` — 已用 `Memory by innerMemory`（line 17），自动转发 `mediaArchive`，**不动**

### 测试修改
- `agent/core/src/test/kotlin/io/github/yeyi/agent/llm/MediaSourceTest.kt` — 加 Local 序列化用例
- `agent/core/src/test/kotlin/io/github/yeyi/agent/memory/InMemoryMemoryTest.kt` — 加 `mediaArchive` 字段返回实例测试
- `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentExtensionsTest.kt` — 删除 4 条 `adaptModality` 用例（迁移到 `ModalityAdapterTest`），加 Local 分支占位测试
- `agent/providers/openai/src/test/kotlin/.../OpenAiMappingTest.kt` — 加 Local → `UnsupportedContent` 用例
- `agent/providers/anthropic/src/test/kotlin/.../AnthropicMappingTest.kt` — 同上

---

## Task 1: MediaSource.Local 变体

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ChatRequest.kt:147-167`（MediaSource sealed interface 段）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ChatRequest.kt:140-145`（KDoc 把"三种变体"改"四种变体"）
- Test: `agent/core/src/test/kotlin/io/github/yeyi/agent/llm/MediaSourceTest.kt`

**Interfaces:**
- Consumes: 现有 `MediaSource.Http` / `MediaSource.Data` / `MediaSource.FileId` 序列化形态不变
- Produces: `MediaSource.Local(fileId: String, mimeType: String)`，JSON 形式 `{"type":"local","fileId":"...","mimeType":"..."}`；下游消费方（`ChatRequest.toTextMessage` / `OpenAiMapping.mapImageToOpenAi` / `AnthropicMapping.mapImageToAnthropic`）将在 Task 4 / Task 10 添加对应 `when` 分支

- [ ] **Step 1: 在 `MediaSourceTest.kt` 加 4 条 Local 序列化用例**

```kotlin
// append to MediaSourceTest.kt class

@Test
fun `serializes Local variant with fileId and mimeType`() {
    val src: MediaSource = MediaSource.Local(
        fileId = "550e8400-e29b-41d4-a716-446655440000",
        mimeType = "image/jpeg",
    )
    assertEquals(
        """{"type":"local","fileId":"550e8400-e29b-41d4-a716-446655440000","mimeType":"image/jpeg"}""",
        json.encodeToString(src),
    )
}

@Test
fun `deserializes Local variant from JSON`() {
    val text = """{"type":"local","fileId":"abc-123","mimeType":"image/png"}"""
    val src: MediaSource = json.decodeFromString(text)
    assertEquals(MediaSource.Local("abc-123", "image/png"), src)
}

@Test
fun `Local equals by data class equality`() {
    val a = MediaSource.Local("id1", "image/jpeg")
    val b = MediaSource.Local("id1", "image/jpeg")
    val c = MediaSource.Local("id2", "image/jpeg")
    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
    assertEquals(false, a == c)
}

@Test
fun `Local is not equal to FileId even with same id field`() {
    // 边界:两个变体都有 String id, 但语义不同(谁持有)。
    val local = MediaSource.Local("id1", "image/jpeg")
    val fileId = MediaSource.FileId("id1")
    assertEquals(false, local == fileId)
}
```

- [ ] **Step 2: 运行测试验证失败**

Run:
```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" ls-files agent/core/src/test/kotlin/io/github/yeyi/agent/llm/MediaSourceTest.kt
cd "D:/yeyi/AI/agent-sdk" && ./gradlew :agent:core:test --tests "io.github.yeyi.agent.llm.MediaSourceTest" 2>&1 | tail -40
```
Expected: FAIL —— `MediaSource.Local` 未定义 / 编译报错（unresolved reference）。

- [ ] **Step 3: 实现 `MediaSource.Local` 变体**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ChatRequest.kt`：

a) 更新 KDoc（line 140-145）从"三种变体"改"四种变体"：
```kotlin
/**
 * 多媒体资源的统一来源抽象，四种模态 (image/audio/video) 共用同一组变体。
 *
 * - [Http]  : 公网 URL 或内网可路由 URL，由 LLM provider 主动 fetch。
 * - [Data]  : base64 内联；适用于 image 和短 audio；video 由 provider 实现层拒绝。
 * - [FileId]: provider 托管的文件 ID（OpenAI files API、Anthropic files API）。
 * - [Local] : agent 持有的本地文件引用（UUID），由 [io.github.yeyi.agent.memory.MediaArchive]
 *             解析为字节；caller 跨 query 复用同一图时用此避免重复 inline base64。
 *             Provider 实现层**不支持** [Local]，由 [io.github.yeyi.agent.ModalityAdapter]
 *             在送 LLM 前 resolve 为 [Data]。
 */
```

b) 在现有 `MediaSource.FileId` 后面（line 166 后）加 `Local` 变体：
```kotlin
    @Serializable
    @SerialName("local")
    public data class Local(
        public val fileId: String,
        public val mimeType: String,
    ) : MediaSource
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.llm.MediaSourceTest"`
Expected: PASS —— 4 条新用例 + 原有 4 条全过。

- [ ] **Step 5: Commit**

```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" add agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ChatRequest.kt agent/core/src/test/kotlin/io/github/yeyi/agent/llm/MediaSourceTest.kt
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" commit -F - <<'EOF'
feat(llm): MediaSource 加 Local 变体

新增第 4 个 MediaSource 变体表达 agent 持有的本地文件引用,
由 MediaArchive 解析为字节。Json 形式:
{"type":"local","fileId":"<uuid>","mimeType":"<mime>"}

为后续 ModalityAdapter 末条 User resolve + ArchivingMemory
外层归档装饰器提供 Local 表达。下游消费者在后续任务添加
对应 when 分支。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
```

---

## Task 2: MediaArchive 接口 + Memory.mediaArchive 字段 + 4 个 Memory 实现

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/Memory.kt:1-33`（加 `mediaArchive` 抽象字段 + 导入 MediaSource）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/InMemoryMemory.kt:1-23`（加 `override val mediaArchive` + 私有 `InMemoryMediaArchive` nested class + 导入）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt:12-49`（加 `override val mediaArchive` 转发 + 导入）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/ReadOnlyMemory.kt:9-17`（同上）
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/memory/InMemoryMemoryTest.kt`（加 mediaArchive 字段测试）

**Interfaces:**
- Consumes: Task 1 的 `MediaSource.Local` / `MediaSource.Data`
- Produces:
  - `Memory.mediaArchive: MediaArchive`（抽象字段，所有实现必须 override）
  - `MediaArchive` fun interface（`store(Data): Local` / `resolve(Local): Data`）
  - `InMemoryMemory.mediaArchive` 返回内部 `InMemoryMediaArchive()` 实例
  - `RoundsBoundedMemory.mediaArchive` = `underlying.mediaArchive`（getter 转发）
  - `ReadOnlyMemory.mediaArchive` = `delegate.mediaArchive`（getter 转发）

> **重要**：当前 `RoundsBoundedMemory`（line 12-16）和 `ReadOnlyMemory`（line 9）都用显式 `: Memory` + 显式 `override fun add/history/rebuild`，**不**是 `: Memory by inner` 委托。因此 `mediaArchive` 必须显式 `override val ... get() = ...`，不能用 `: Memory by`。
>
> `JsonlConversation`（`JsonlConversation.kt:17`）是 `: Conversation, Memory by innerMemory`，会自动转发 `mediaArchive`，**无需修改**。

- [ ] **Step 1: 在 `InMemoryMemoryTest.kt` 加 3 条 mediaArchive 用例**

```kotlin
// append to InMemoryMemoryTest.kt class
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.llm.ContentPart
import kotlin.test.assertEquals

@Test
fun `mediaArchive field returns working archive instance`() = runTest {
    val memory = InMemoryMemory()
    // 内部 InMemoryMediaArchive 实例 — 用行为测试验证可工作:
    // store 一个 Data 后 resolve 拿回原 base64
    val original = MediaSource.Data("image/jpeg", "BASE64DATA")
    val local = memory.mediaArchive.store(original)
    assertEquals("image/jpeg", local.mimeType)
    // local.fileId 是 UUID,仅断言非空且能 resolve
    val resolved = memory.mediaArchive.resolve(local)
    assertEquals(original.base64, resolved.base64)
    assertEquals(original.mimeType, resolved.mimeType)
}

@Test
fun `resolve missing fileId throws IllegalStateException`() = runTest {
    val memory = InMemoryMemory()
    val ghost = MediaSource.Local("ghost-id", "image/jpeg")
    val ex = assertFailsWith<IllegalStateException> {
        memory.mediaArchive.resolve(ghost)
    }
    assertTrue(ex.message!!.contains("ghost-id"))
}

@Test
fun `add does not auto-rewrite Data to Local — caller decides`() = runTest {
    // InMemoryMemory 是裸存储层,不做归档决策 — caller 自己 store
    val memory = InMemoryMemory()
    val data = MediaSource.Data("image/jpeg", "BASE64DATA")
    memory.add(ChatMessage.User(listOf(ContentPart.Image(data))))
    val history = memory.history()
    assertEquals(1, history.size)
    val userMsg = history[0] as ChatMessage.User
    val src = (userMsg.parts[0] as ContentPart.Image).source
    assertEquals(data, src)  // 透传, 不改写
}
```

注：先添加 `import kotlin.test.assertFailsWith` 和 `import kotlin.test.assertTrue` 到测试文件顶部（如果还没有）。

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.memory.InMemoryMemoryTest"`
Expected: FAIL —— `Memory` 接口未定义 `mediaArchive`，编译报错。

- [ ] **Step 3: 修改 `Memory.kt` 加 `MediaArchive` fun interface + `mediaArchive` 字段**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/Memory.kt`：

```kotlin
package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.MediaSource

/**
 * 对话历史存储接口，Agent 在多轮对话中通过它读写历史消息。
 *
 * 实现者需保证线程安全：ReActAgent 可能并发调用多个 suspend 方法。
 *
 * SDK 内部使用 [RoundsBoundedMemory] 装饰此接口，实现历史轮次上限和摘要压缩。
 */
public interface Memory {
    /**
     * 本实例持有的 [MediaArchive],用于在请求边界把 [io.github.yeyi.agent.llm.MediaSource.Local]
     * 解析为 [io.github.yeyi.agent.llm.MediaSource.Data]。
     *
     * 必须由实现类持有实例(而非给默认值):装饰链需要逐层透传到最下层那一档,
     * 默认值会被重复注入。
     */
    public val mediaArchive: MediaArchive

    /**
     * 添加一条消息到历史。
     *
     * @param message 支持 [ChatMessage.User]、[ChatMessage.Assistant]、[ChatMessage.ToolResult] 等
     */
    public suspend fun add(message: ChatMessage)

    /**
     * 返回完整对话历史，按时间顺序排列。
     *
     * 返回的消息列表会被拼入 [io.github.yeyi.agent.llm.ChatRequest.messages] 传给 LLM。
     */
    public suspend fun history(): List<ChatMessage>

    /**
     * 用给定消息列表整体替换当前历史。
     *
     * 用于 Memory 实现内部的压缩/摘要重建场景；调用方不应随意调用。
     */
    public suspend fun rebuild(messages: List<ChatMessage>)
}

/**
 * 媒体字节 ↔ [MediaSource.Local] 引用的双向 IO 抽象。
 *
 * - [store]  : 把 [MediaSource.Data] 的字节存起来,返回一个 opaque [MediaSource.Local]
 *              引用（实现负责生成 ID 并保证后续 [resolve] 能找到）。
 * - [resolve]: 解析 [MediaSource.Local] 引用,还原为 [MediaSource.Data]。
 *
 * **只**承担 IO 能力,不决定"什么 Data 值得单独存文件"——归档阈值由持久化
 * [Memory] 实现层（[io.github.yeyi.agent.session.ArchivingMemory]）在 `add()` 内决定。
 *
 * SDK 默认实现：
 * - 测试场景：[InMemoryMemory] 内部嵌套 `InMemoryMediaArchive`(纯内存 map)
 * - 持久化场景：[io.github.yeyi.agent.session.FilesystemMediaArchive](per-session `media/` 目录)
 *
 * 实现由 caller 注入（持久化场景）或 Memory 自己实例化（单 session 场景）。
 */
fun interface MediaArchive {
    fun store(data: MediaSource.Data): MediaSource.Local
    fun resolve(local: MediaSource.Local): MediaSource.Data
}
```

- [ ] **Step 4: 修改 `InMemoryMemory.kt` 加 `InMemoryMediaArchive` nested class**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/InMemoryMemory.kt`：

```kotlin
package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.MediaSource
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

public class InMemoryMemory : Memory {
    override val mediaArchive: MediaArchive = InMemoryMediaArchive()

    private val messages: MutableList<ChatMessage> = mutableListOf()
    private val mutex: Mutex = Mutex()

    override suspend fun add(message: ChatMessage): Unit = mutex.withLock {
        messages += message
    }

    override suspend fun history(): List<ChatMessage> = mutex.withLock {
        messages.toList()
    }

    override suspend fun rebuild(messages: List<ChatMessage>): Unit = mutex.withLock {
        this.messages.clear()
        this.messages.addAll(messages)
    }

    /**
     * 内存测试用 archive —— 直接存 base64 字符串,跳过 store 的 decode
     * 和 resolve 的 encode。内存场景下不需要还原 bytes —— base64 占内存
     * 略多但省两次编解码开销。生产场景用 [io.github.yeyi.agent.session.FilesystemMediaArchive]。
     */
    private class InMemoryMediaArchive : MediaArchive {
        private val store: MutableMap<String, String> = mutableMapOf()
        override fun store(data: MediaSource.Data): MediaSource.Local {
            val fileId = UUID.randomUUID().toString()
            store[fileId] = data.base64
            return MediaSource.Local(fileId, data.mimeType)
        }
        override fun resolve(local: MediaSource.Local): MediaSource.Data {
            val base64 = store[local.fileId]
                ?: throw IllegalStateException("MediaArchive missing fileId=${local.fileId}")
            return MediaSource.Data(local.mimeType, base64)
        }
    }
}
```

- [ ] **Step 5: 修改 `RoundsBoundedMemory.kt` 转发 mediaArchive**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt`：
- 添加 `import` （如果还没有 `MediaArchive`）：

```kotlin
import io.github.yeyi.agent.memory.MediaArchive  // already in same package, no import needed
```

实际 `MediaArchive` 在 `io.github.yeyi.agent.memory` 包，与本类同包，无需新增 import。

- 在类签名下方（约 line 16-17 之间）添加：

```kotlin
    override val mediaArchive: MediaArchive get() = underlying.mediaArchive
```

`MediaArchive` 类型同包，无需 import。

- [ ] **Step 6: 修改 `ReadOnlyMemory.kt` 转发 mediaArchive**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/ReadOnlyMemory.kt`，在 `delegate` 声明后添加：

```kotlin
internal class ReadOnlyMemory(private val delegate: Memory) : Memory {
    override val mediaArchive: MediaArchive get() = delegate.mediaArchive
    // ... 原有 add/history/rebuild 不动 ...
}
```

`MediaArchive` 同包，无需 import。

- [ ] **Step 7: 运行测试验证通过**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.memory.InMemoryMemoryTest"`
Expected: PASS —— 3 条新用例 + 原有所有用例通过。

- [ ] **Step 8: 运行全 core 模块测试确认装饰器未坏**

Run: `./gradlew :agent:core:test`
Expected: PASS —— `RoundsBoundedMemory` / `ReadOnlyMemory` 编译过、行为不变。

- [ ] **Step 9: Commit**

```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" add agent/core/src/main/kotlin/io/github/yeyi/agent/memory/Memory.kt agent/core/src/main/kotlin/io/github/yeyi/agent/memory/InMemoryMemory.kt agent/core/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt agent/core/src/main/kotlin/io/github/yeyi/agent/memory/ReadOnlyMemory.kt agent/core/src/test/kotlin/io/github/yeyi/agent/memory/InMemoryMemoryTest.kt
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" commit -F - <<'EOF'
feat(memory): Memory 接口加 mediaArchive 抽象字段

引入 MediaArchive fun interface 表达"Data ↔ Local"双向 IO
能力,作为 agent/core 的扩展点契约。三层分工:
- core: 接口契约 + InMemoryMediaArchive 测试用嵌套类
- session: 生产级 FilesystemMediaArchive
- caller app: 自定义实现(S3/DB/加密等)

Memory.mediaArchive 是纯 abstract 字段,无默认实现:
- InMemoryMemory 内部实例化 InMemoryMediaArchive()
- RoundsBoundedMemory/ReadOnlyMemory 显式 getter 转发
  underlying.mediaArchive(注意:这两个用显式 override 而非
  Memory by delegate,必须显式转发)
- JsonlConversation 维持 Memory by innerMemory 不动,
  Kotlin delegate 自动转发 mediaArchive

InMemoryMemory.add() 维持纯存储语义 — caller 自己决定
是否调 archive.store() 转 Local,避免 IO 副作用潜入 Memory。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
```

---

## Task 3: ModalityAdapter + DefaultModalityAdapter + adaptModality file-private extension

**Files:**
- Create: `agent/core/src/main/kotlin/io/github/yeyi/agent/ModalityAdapter.kt`
- Create: `agent/core/src/test/kotlin/io/github/yeyi/agent/ModalityAdapterTest.kt`

**Interfaces:**
- Consumes: Task 1 的 `MediaSource.Local` / `MediaSource.Data`；Task 2 的 `MediaArchive`；现有 `ChatMessage` / `ContentPart`
- Produces:
  - `ModalityAdapter` fun interface: `fun adapt(messages: List<ChatMessage>, archive: MediaArchive): List<ChatMessage>`
  - `DefaultModalityAdapter` 默认实现: 末条 User 的 Local → Data + 引用 text、跨 round 转占位、末条 ToolResult 拆 text + 合成 User
  - `private fun ChatMessage.ToolResult.adaptModality()` file-private extension（从 AgentExtensions.kt 下沉）

- [ ] **Step 1: 写 7 条 `ModalityAdapterTest` 用例**

创建 `agent/core/src/test/kotlin/io/github/yeyi/agent/ModalityAdapterTest.kt`：

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ModalityAdapterTest {

    private val dataPart = ContentPart.Image(MediaSource.Data("image/jpeg", "BASE64"))
    private val httpPart = ContentPart.Image(MediaSource.Http("https://example.com/x.png"))
    private val fileIdPart = ContentPart.Image(MediaSource.FileId("file-abc"))
    private val localPart = ContentPart.Image(
        MediaSource.Local(fileId = "550e8400-e29b-41d4-a716-446655440000", mimeType = "image/jpeg"),
    )

    /** Spy archive: 记录 store/resolve 调用次数,resolve 时按 fileId 找 base64 */
    private class SpyArchive(
        private val backing: MutableMap<String, String> = mutableMapOf(),
        var resolveCount: Int = 0,
        var storeCount: Int = 0,
    ) : MediaArchive {
        override fun store(data: MediaSource.Data): MediaSource.Local {
            storeCount++
            val id = "stored-${storeCount}"
            backing[id] = data.base64
            return MediaSource.Local(id, data.mimeType)
        }
        override fun resolve(local: MediaSource.Local): MediaSource.Data {
            resolveCount++
            return MediaSource.Data(
                mimeType = local.mimeType,
                base64 = backing[local.fileId] ?: "RESOLVED-${local.fileId}",
            )
        }
    }

    @Test
    fun `last User with Local resolves to Text ref + Data, archive called once`() {
        val archive = SpyArchive(backing = mutableMapOf("550e8400..." to "BASE64BYTES"))
        // 用一个确定性 fileId 的 Local 让 spy 找到 backing map
        val local = MediaSource.Local("550e8400...", "image/jpeg")
        val lastUser = ChatMessage.User(listOf(ContentPart.Image(local)))

        val out = DefaultModalityAdapter().adapt(listOf(lastUser), archive)

        assertEquals(1, out.size)
        val user = out[0] as ChatMessage.User
        assertEquals(2, user.parts.size)
        val ref = user.parts[0] as ContentPart.Text
        assertEquals("[local] fileId=550e8400...", ref.text)
        val data = (user.parts[1] as ContentPart.Image).source as MediaSource.Data
        assertEquals("BASE64BYTES", data.base64)
        assertEquals(1, archive.resolveCount)
    }

    @Test
    fun `last User with Data passes through unchanged`() {
        val archive = SpyArchive()
        val lastUser = ChatMessage.User(listOf(dataPart))

        val out = DefaultModalityAdapter().adapt(listOf(lastUser), archive)

        assertEquals(1, out.size)
        val user = out[0] as ChatMessage.User
        assertEquals(listOf(dataPart), user.parts)
        assertEquals(0, archive.resolveCount)
    }

    @Test
    fun `last User with Http and FileId passes through unchanged`() {
        val archive = SpyArchive()
        val lastUser = ChatMessage.User(listOf(httpPart, fileIdPart))

        val out = DefaultModalityAdapter().adapt(listOf(lastUser), archive)

        assertEquals(1, out.size)
        val user = out[0] as ChatMessage.User
        assertEquals(listOf(httpPart, fileIdPart), user.parts)
        assertEquals(0, archive.resolveCount)
    }

    @Test
    fun `cross-round User with Local becomes placeholder without archive resolve`() {
        val archive = SpyArchive()
        val history = ChatMessage.User(listOf(localPart))    // cross-round (i < lastUserIdx)
        val current = ChatMessage.User(listOf(ContentPart.Text("next question")))

        val out = DefaultModalityAdapter().adapt(listOf(history, current), archive)

        assertEquals(2, out.size)
        val crossRound = out[0] as ChatMessage.User
        val ph = crossRound.parts[0] as ContentPart.Text
        assertTrue(ph.text.startsWith("[image] local fileId=550e8400"))
        assertTrue(ph.text.endsWith("..."), "expected truncation marker, got: ${ph.text}")
        assertEquals(0, archive.resolveCount)  // 跨 round 不读盘
    }

    @Test
    fun `last User parts order preserved when Local expands mid-list`() {
        // 输入 [Text, Local, Http], Local 展开为 [Text引用, Data],
        // 期望 [Text, Text引用, Data, Http] (Http 相对 Local 位置保持)
        val archive = SpyArchive(backing = mutableMapOf("id1" to "B64"))
        val local = MediaSource.Local("id1", "image/png")
        val lastUser = ChatMessage.User(
            listOf(
                ContentPart.Text("caption"),
                ContentPart.Image(local),
                ContentPart.Image(MediaSource.Http("https://x/y.png")),
            ),
        )

        val out = DefaultModalityAdapter().adapt(listOf(lastUser), archive)

        val user = out[0] as ChatMessage.User
        assertEquals(4, user.parts.size)
        assertEquals("caption", (user.parts[0] as ContentPart.Text).text)
        assertEquals("[local] fileId=id1", (user.parts[1] as ContentPart.Text).text)
        assertEquals(
            MediaSource.Data("image/png", "B64"),
            (user.parts[2] as ContentPart.Image).source,
        )
        assertEquals(httpPart, user.parts[3])
    }

    @Test
    fun `last ToolResult with media splits into text-only ToolResult and synthetic User`() {
        val archive = SpyArchive()
        val tr = ChatMessage.ToolResult(
            toolCallId = "c1",
            toolName = "echo",
            parts = listOf(ContentPart.Text("result:"), httpPart),
        )

        val out = DefaultModalityAdapter().adapt(listOf(tr), archive)

        assertEquals(2, out.size)
        // 1. text-only ToolResult
        val textOnly = out[0] as ChatMessage.ToolResult
        assertEquals(listOf<ContentPart>(ContentPart.Text("result:")), textOnly.parts)
        // 2. 合成 User 是最后 User
        val synthetic = out[1] as ChatMessage.User
        assertEquals(2, synthetic.parts.size)
        assertEquals("[from echo]", (synthetic.parts[0] as ContentPart.Text).text)
        assertEquals(httpPart, synthetic.parts[1])
    }

    @Test
    fun `System and Assistant pass through unchanged`() {
        val archive = SpyArchive()
        val messages = listOf(
            ChatMessage.System("you are helpful"),
            ChatMessage.Assistant("ok"),
            ChatMessage.User(listOf(dataPart)),  // last User,不动
        )

        val out = DefaultModalityAdapter().adapt(messages, archive)

        assertEquals(3, out.size)
        assertEquals(ChatMessage.System("you are helpful"), out[0])
        assertEquals(ChatMessage.Assistant("ok"), out[1])
        assertEquals(messages[2], out[2])  // last User unchanged
    }

    @Test
    fun `archive resolve failure propagates as IllegalStateException`() {
        val archive = SpyArchive()  // backing 空,resolve 会返回 "RESOLVED-<id>" 但不抛
        // 用一个真正会抛的 archive:
        val throwing = object : MediaArchive {
            override fun store(data: MediaSource.Data) = MediaSource.Local("x", data.mimeType)
            override fun resolve(local: MediaSource.Local): MediaSource.Data =
                throw IllegalStateException("MediaArchive missing fileId=${local.fileId}")
        }
        val local = MediaSource.Local("x", "image/jpeg")
        val lastUser = ChatMessage.User(listOf(ContentPart.Image(local)))

        assertFailsWith<IllegalStateException> {
            DefaultModalityAdapter().adapt(listOf(lastUser), throwing)
        }
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.ModalityAdapterTest"`
Expected: FAIL —— `ModalityAdapter` / `DefaultModalityAdapter` 类未定义，编译报错。

- [ ] **Step 3: 实现 `ModalityAdapter.kt`**

创建 `agent/core/src/main/kotlin/io/github/yeyi/agent/ModalityAdapter.kt`：

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive

/**
 * 多模态消息适配器，在 LLM 请求边界做三件事：
 *
 * 1. **拆末条 ToolResult**：含 media 时拆成 text-only + 合成的 User（[ChatMessage.ToolResult.adaptModality]）
 * 2. **找最后 User**：从 messages 找到最后一条 User 的索引
 * 3. **渲染**：
 *    - 末条 User → [MediaArchive.resolve] 把 Local 转 Data，其他 media 透传
 *    - 其他消息 → [toTextMessage] 把 media 转 `[image] local fileId=xxx` 占位文本
 *
 * Adapter **不依赖**整个 [io.github.yeyi.agent.memory.Memory],只通过 [MediaArchive] 拿读桥。
 * 这是纯变换接口,IO 通过 [MediaArchive] 注入;测试里直接 lambda mock。
 *
 * `MediaArchive` 放在方法签名而非构造器 —— 适配工作的核心就是处理归档,显式化在契约里:
 * caller 一眼看得出"做适配需要 archive",实现类无隐藏状态,fun interface 允许 lambda 直接构造。
 */
fun interface ModalityAdapter {
    fun adapt(messages: List<ChatMessage>, archive: MediaArchive): List<ChatMessage>
}

/**
 * 默认实现：[ModalityAdapter] 的开箱即用版本。caller 未显式设置时由 [AgentBuilder.build]
 * 注入（[ReActAgent] 构造参数必填）。
 */
class DefaultModalityAdapter : ModalityAdapter {

    override fun adapt(
        messages: List<ChatMessage>,
        archive: MediaArchive,
    ): List<ChatMessage> {
        // 1. 末条 ToolResult 拆出 media (只对末条做,跨 round 历史在 mapIndexed 阶段占位)
        val history = adaptToolResult(messages)
        // 2. 当前 round 是最后一条 User —— 可能是原始 User,也可能是拆出来的合成 User。
        //    整个 round 内所有 iter 都保留原图,跨 round 的历史 User 才占位。
        //    这样 iter #2+ 仍可重看图,但旧 round 的图不再每轮重传,避免 token 膨胀。
        val lastUserIdx = history.indexOfLast { it is ChatMessage.User }
        return history.mapIndexed { i, message ->
            if (i == lastUserIdx && message is ChatMessage.User) {
                resolveUserMedia(message, archive)
            } else {
                message.toTextMessage()
            }
        }
    }

    /**
     * 若末条是 [ChatMessage.ToolResult],拆成 text-only ToolResult + 合成 User;
     * 否则原样返回 —— 避免无谓的 toMutableList 拷贝。
     *
     * 只在请求边界做这个拆分,memory 始终保留原始多模态信息。
     */
    private fun adaptToolResult(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.lastOrNull() !is ChatMessage.ToolResult) return messages
        val mutable = messages.toMutableList()
        val lastIdx = mutable.lastIndex
        val modalityMessages = (mutable[lastIdx] as ChatMessage.ToolResult).adaptModality()
        mutable.removeAt(lastIdx)
        mutable.addAll(modalityMessages)
        return mutable
    }

    /**
     * 末条 User 的 [MediaSource.Local] 经 [MediaArchive.resolve] 转 [MediaSource.Data],
     * 同时前置一条 `[local] fileId=xxx` 文本 part —— 模型既看得到图 (Data),也拿到
     * 完整 fileId,想用工具读/操作该文件时把整串传回即可。
     *
     * "末条 User" 的判断由 [adapt] 负责,本方法只做 resolve + 引用注入。
     */
    private fun resolveUserMedia(
        user: ChatMessage.User,
        archive: MediaArchive,
    ): ChatMessage.User =
        user.copy(parts = user.parts.flatMap { part -> resolveLocal(part, archive) })

    /**
     * Local → `[fileId 文本 part, resolve 后的 media part]`;其余 (Text / Http /
     * Data / FileId) 原样单 part 返回。
     */
    private fun resolveLocal(part: ContentPart, archive: MediaArchive): List<ContentPart> {
        val local = when (part) {
            is ContentPart.Image -> part.source
            is ContentPart.Audio -> part.source
            is ContentPart.Video -> part.source
            is ContentPart.Text -> null
        } as? MediaSource.Local ?: return listOf(part)

        val data = archive.resolve(local)
        return listOf(
            ContentPart.Text("[local] fileId=${local.fileId}"),
            when (part) {
                is ContentPart.Image -> part.copy(source = data)
                is ContentPart.Audio -> part.copy(source = data)
                is ContentPart.Video -> part.copy(source = data)
                is ContentPart.Text -> part
            },
        )
    }
}

/**
 * 把含 media 的 [ChatMessage.ToolResult] 拆成 text-only ToolResult + 合成的 User。
 * 从 `AgentExtensions.kt` 的 internal 扩展下沉为本文件内的 file-private extension
 * —— 只被 [DefaultModalityAdapter] 使用, 不再对外暴露。
 */
private fun ChatMessage.ToolResult.adaptModality(): List<ChatMessage> {
    val mediaParts = parts.filter { it !is ContentPart.Text }
    if (mediaParts.isEmpty()) return listOf(this)
    val textParts = parts.filterIsInstance<ContentPart.Text>()
    val textOnly = copy(parts = textParts)
    return listOf(
        textOnly,
        ChatMessage.User(parts = listOf(ContentPart.Text("[from $toolName]")) + mediaParts),
    )
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.ModalityAdapterTest"`
Expected: PASS —— 8 条全过。

- [ ] **Step 5: Commit**

```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" add agent/core/src/main/kotlin/io/github/yeyi/agent/ModalityAdapter.kt agent/core/src/test/kotlin/io/github/yeyi/agent/ModalityAdapterTest.kt
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" commit -F - <<'EOF'
feat(core): 新增 ModalityAdapter fun interface + DefaultModalityAdapter

agent/core 内置的 LLM 边界多模态适配器,把原本内联在
ReActAgent.buildRequest 的 16 行逻辑(adaptModality +
toTextMessage)下沉到独立单元。MediaArchive 放在方法签名
而非构造器 —— 适配工作核心就是处理归档,显式化在契约里。

DefaultModalityAdapter 三步:
1. 末条 ToolResult 拆 text + 合成 User(adaptToolResult)
2. 找最后 User 索引
3. 末条 User → archive.resolve Local → Data + 引用 text
   其他消息 → toTextMessage 占位(跨 round 不读盘)

adaptModality 从 AgentExtensions.kt 下沉为本文件内的
file-private extension —— adapter 内部 use case,不再
对外暴露。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
```

---

## Task 4: AgentExtensions — 删 adaptModality + toTextMessage 加 Local 分支

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentExtensions.kt:60-66`（`describeMediaSource` when 加 Local 分支）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentExtensions.kt:93-104`（删除 `internal fun ChatMessage.ToolResult.adaptModality()`）
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentExtensionsTest.kt`（删除 4 条 adaptModality 用例 + 加 Local 占位用例）

**Interfaces:**
- Consumes: Task 1 的 `MediaSource.Local`
- Produces: `toTextMessage` 对 Local 占位返回 `"local fileId=<前8字符>"`；不再导出 `adaptModality`（已迁移到 ModalityAdapter.kt）

- [ ] **Step 1: 修改 `AgentExtensionsTest.kt`：删 4 条 adaptModality 用例 + 加 Local 占位用例**

修改 `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentExtensionsTest.kt`：

- 删除 `import` 和以下 4 个 test 方法（第 18-68 行）：
  - `ToolResult with only text produces a single ToolResult with no follow-up`
  - `ToolResult with text and media splits into text-only ToolResult and User with prefix`
  - `ToolResult with only media produces empty ToolResult and User with prefix`
  - `isError propagates to split ToolResult`
  - `toolName propagates to User prefix`
- 这些用例的等价覆盖在 `ModalityAdapterTest.kt` 中存在（Step 1 已写）

- 加 2 条 Local 占位用例：
```kotlin
// append to AgentExtensionsTest.kt

@Test
fun `User with Local Image is replaced by truncated placeholder`() {
    val local = MediaSource.Local(
        fileId = "550e8400-e29b-41d4-a716-446655440000",
        mimeType = "image/jpeg",
    )
    val msg = ChatMessage.User(listOf(ContentPart.Image(local)))
    val out = msg.toTextMessage()
    val parts = (out as ChatMessage.User).parts
    assertEquals(1, parts.size)
    val ph = parts[0] as ContentPart.Text
    assertEquals("[image] local fileId=550e8400", ph.text)
}

@Test
fun `Local Audio and Video are replaced by truncated placeholder too`() {
    val local = MediaSource.Local("abcdef00-1234-5678-9abc-def012345678", "video/mp4")
    val msg = ChatMessage.User(listOf(ContentPart.Audio(local)))
    val ph = (msg.toTextMessage() as ChatMessage.User).parts[0] as ContentPart.Text
    assertEquals("[audio] local fileId=abcdef00", ph.text)

    val msg2 = ChatMessage.User(listOf(ContentPart.Video(local)))
    val ph2 = (msg2.toTextMessage() as ChatMessage.User).parts[0] as ContentPart.Text
    assertEquals("[video] local fileId=abcdef00", ph2.text)
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.AgentExtensionsTest"`
Expected: FAIL —— `when (source)` 缺少 Local 分支（编译错），且 Local 占位断言失败。

- [ ] **Step 3: 修改 `AgentExtensions.kt`：加 Local 分支 + 删 adaptModality**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentExtensions.kt`：

a) 修改 `describeMediaSource`（line 60-66）—— 加 Local 分支：

```kotlin
    fun describeMediaSource(source: MediaSource): String = when (source) {
        is MediaSource.Http -> source.url.substringAfterLast('/')
            .ifEmpty { source.url.take(64) }

        is MediaSource.Data -> "inline ${source.base64.length * 3 / 4 / 1024}KB"
        is MediaSource.FileId -> "file:${source.id.take(8)}"
        // 跨 round 占位截断前缀;末条 User 的引用走 ModalityAdapter 注入完整 fileId
        is MediaSource.Local -> "local fileId=${source.fileId.take(8)}"
    }
```

b) 删除 `internal fun ChatMessage.ToolResult.adaptModality()` 整个方法（line 86-104，包括 KDoc）—— 该方法已下沉到 `ModalityAdapter.kt`。

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.AgentExtensionsTest"`
Expected: PASS —— 仅 Local 占位 2 条新用例 + 原有 toTextMessage 用例（如有）通过。

- [ ] **Step 5: 编译整个 agent/core 模块确认 `RoundsBoundedMemory` 不再依赖 adaptModality**

Run: `./gradlew :agent:core:compileKotlin`
Expected: PASS —— `adaptModality` 已删除, `RoundsBoundedMemory` 不调用它（它只调 `toTextMessage`，确认搜索）。

确认命令：
```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" grep -rn "adaptModality" agent/ 2>&1 | head -20
```
Expected: 仅 `ModalityAdapter.kt` 和 `ModalityAdapterTest.kt` 中出现（说明迁移完成）。

- [ ] **Step 6: Commit**

```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" add agent/core/src/main/kotlin/io/github/yeyi/agent/AgentExtensions.kt agent/core/src/test/kotlin/io/github/yeyi/agent/AgentExtensionsTest.kt
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" commit -F - <<'EOF'
refactor(core): toTextMessage 加 Local 占位 + 移除 adaptModality

toTextMessage.describeMediaSource 加 Local 分支:
"local fileId=<前8字符>"(占位截断前缀,跟 FileId 的
take(8) 同步策略)。末条 User 的引用文本由 ModalityAdapter
注入完整 fileId,模型调工具时整串传回 —— 两处形态不同
但语义自洽。

internal fun ChatMessage.ToolResult.adaptModality 删除,
已下沉到 ModalityAdapter.kt 的 file-private extension。
原 AgentExtensionsTest 4 条 adaptModality 用例同步删除,
等价覆盖在 ModalityAdapterTest 中。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
```

---

## Task 5: ReActAgent + AgentBuilder 接入 ModalityAdapter

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt:24-32`（加 `modalityAdapter` 构造参数）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt:205-229`（`buildRequest` 改走 adapter）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt:26-108`（加 `modalityAdapter` 字段 + 设置方法 + build 默认）

**Interfaces:**
- Consumes: Task 3 的 `ModalityAdapter` / `DefaultModalityAdapter`；Task 2 的 `Memory.mediaArchive`
- Produces:
  - `ReActAgent(memory, modalityAdapter, ...)` 必填构造参数
  - `ReActAgent.buildRequest()` 内部调 `modalityAdapter.adapt(memory.history(), memory.mediaArchive)`
  - `AgentBuilder.modalityAdapter(adapter)` 设置方法；`build()` 默认 `DefaultModalityAdapter()`

- [ ] **Step 1: 修改 `ReActAgentMultimodalTest.kt`：确认现有测试覆盖 adapter 默认注入**

先检查 `agent/core/src/test/kotlin/io/github/yeyi/agent/ReActAgentMultimodalTest.kt` 是否存在。如不存在则不需修改。

Run: `find agent/core/src/test -name "ReActAgent*Test.kt"`
Expected: 列出 `ReActAgentMultimodalTest.kt`（说明已存在集成测试）。

读该文件确认它通过 `AgentBuilder` DSL 构造 agent —— DSL 默认 `DefaultModalityAdapter` 注入，集成测试不需要修改即可工作。这一步是**预检**，不修改测试。

- [ ] **Step 2: 运行现有 ReActAgentMultimodalTest 验证修改前基线**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.ReActAgentMultimodalTest"`
Expected: PASS（修改前应通过 —— 这是验证基线，修改后必须仍通过）。

- [ ] **Step 3: 修改 `ReActAgent.kt`：加 `modalityAdapter` 参数 + buildRequest 改走 adapter**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt`：

a) 修改类签名（line 24-32）：
```kotlin
public class ReActAgent internal constructor(
    private val persona: Persona,
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry,
    memory: Memory,
    private val modalityAdapter: ModalityAdapter,
    private val maxRounds: Int,
    private val maxIterations: Int,
    private val hook: AgentHook = NoOpAgentHook,
) : Agent {
```

b) 修改 `buildRequest()`（line 205-229）—— 替换为：
```kotlin
    private suspend fun buildRequest(): ChatRequest {
        val messages = modalityAdapter.adapt(memory.history(), memory.mediaArchive)
        return ChatRequest(
            messages = buildList {
                add(ChatMessage.System(persona.toString()))
                addAll(rendered)
            },
            tools = toolRegistry.all().map(Tool::toDefinition)
        )
    }
```

- [ ] **Step 4: 修改 `AgentBuilder.kt`：加 `modalityAdapter` 字段 + 设置方法 + build 默认**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt`：

a) 加 import（如果还没有）：
```kotlin
import io.github.yeyi.agent.memory.Memory  // 已存在
```
不需要新增 import（`ModalityAdapter` / `DefaultModalityAdapter` 同包 `io.github.yeyi.agent`）。

b) 加字段（line 33-35 之间，`private var hook` 之前/之后）：
```kotlin
    private var modalityAdapter: ModalityAdapter? = null
```

c) 加设置方法（line 84-85 之后，`public fun hook` 之后）：
```kotlin
    /**
     * 设置多模态适配器。`ModalityAdapter` 在 LLM 请求边界完成"末条 User 的 Local
     * → Data resolve + 跨 round 占位 + 末条 ToolResult 拆 text"三件事。
     *
     * 未设置时 [build] 内默认 [DefaultModalityAdapter] (无构造参数)。
     */
    public fun modalityAdapter(adapter: ModalityAdapter) {
        this.modalityAdapter = adapter
    }
```

d) 修改 `build()`（line 96-108）：
```kotlin
    public fun build(): Agent {
        val provider = requireNotNull(llmProvider) { "llmProvider must be set" }
        val modalityAdapter = modalityAdapter ?: DefaultModalityAdapter()

        return ReActAgent(
            persona = persona ?: Persona("You are a helpful assistant."),
            llmProvider = provider,
            toolRegistry = toolRegistry,
            memory = memory,
            modalityAdapter = modalityAdapter,
            maxRounds = maxRounds,
            maxIterations = maxIterations,
            hook = hook,
        )
    }
```

- [ ] **Step 5: 运行 ReActAgentMultimodalTest 验证通过**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.ReActAgentMultimodalTest"`
Expected: PASS —— DSL 路径默认注入 `DefaultModalityAdapter`，集成测试不需修改。

- [ ] **Step 6: 运行全 core 模块测试确认无回归**

Run: `./gradlew :agent:core:test`
Expected: PASS —— 全部 core 测试通过。

- [ ] **Step 7: Commit**

```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" add agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt agent/core/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" commit -F - <<'EOF'
refactor(core): ReActAgent 接入 ModalityAdapter 接管 buildRequest

ReActAgent 加 modalityAdapter 必填构造参数(与 memory/hook
pattern 一致,buildRequest 改走 modalityAdapter.adapt(
memory.history(), memory.mediaArchive),把 16 行内联多模态
适配逻辑整体下沉到 DefaultModalityAdapter。

AgentBuilder 加 modalityAdapter(adapter) 设置方法 +
build() 内默认 DefaultModalityAdapter()(无构造参数,
archive 在 adapt() 调时由 ReActAgent 传入 memory.mediaArchive)。

行为不变(默认配置下与原实现完全一致):末条 ToolResult 拆
text + 合成 User、跨 round 转占位、末条 User resolve Local。
新增能力:支持 caller 自定义 ModalityAdapter 实现做不同策略
(关占位、不同 IO archive 等)。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
```

---

## Task 6: FilesystemMediaArchive

**Files:**
- Create: `agent/session/src/main/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchive.kt`
- Create: `agent/session/src/test/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchiveTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `MediaArchive` 接口
- Produces: `FilesystemMediaArchive(rootDir: File): MediaArchive` 实现 —— `store` 写 UUID 文件 + base64 解码；`resolve` 读文件 + base64 编码；`resolve` 缺失 ID 抛 `IllegalStateException`

- [ ] **Step 1: 写 `FilesystemMediaArchiveTest.kt`（5 条用例）**

创建 `agent/session/src/test/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchiveTest.kt`：

```kotlin
package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.MediaSource
import org.junit.After
import org.junit.Before
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class FilesystemMediaArchiveTest {

    private lateinit var tempDir: File

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("archive-test").toFile()
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `store and resolve round-trips base64 bytes`() {
        val archive = FilesystemMediaArchive(File(tempDir, "media"))
        val data = MediaSource.Data("image/jpeg", "SGVsbG8=")  // "Hello" in base64
        val local = archive.store(data)

        assertEquals("image/jpeg", local.mimeType)
        val resolved = archive.resolve(local)
        assertEquals("SGVsbG8=", resolved.base64)
        assertEquals("image/jpeg", resolved.mimeType)
    }

    @Test
    fun `store generates unique fileId for each call`() {
        val archive = FilesystemMediaArchive(File(tempDir, "media"))
        val data = MediaSource.Data("image/jpeg", "SAME")

        val local1 = archive.store(data)
        val local2 = archive.store(data)

        assertTrue(local1.fileId != local2.fileId, "each store should produce unique UUID")
    }

    @Test
    fun `resolve throws IllegalStateException for missing fileId`() {
        val archive = FilesystemMediaArchive(File(tempDir, "media"))
        val ghost = MediaSource.Local("ghost-id-not-on-disk", "image/jpeg")

        val ex = assertFailsWith<IllegalStateException> {
            archive.resolve(ghost)
        }
        assertTrue(ex.message!!.contains("ghost-id-not-on-disk"))
    }

    @Test
    fun `init creates rootDir if not exists`() {
        val root = File(tempDir, "nested/media")
        assertTrue(!root.exists())

        FilesystemMediaArchive(root)

        assertTrue(root.exists())
        assertTrue(root.isDirectory)
    }

    @Test
    fun `large base64 round-trips correctly`() {
        // 用 ~10KB base64 (7680B 原始字节) 验证 base64 解码路径不走捷径
        val big = "A".repeat(10_240)
        val archive = FilesystemMediaArchive(File(tempDir, "media"))
        val data = MediaSource.Data("image/png", big)
        val local = archive.store(data)

        val resolved = archive.resolve(local)
        assertEquals(big, resolved.base64)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :agent:session:test --tests "io.github.yeyi.agent.session.FilesystemMediaArchiveTest"`
Expected: FAIL —— `FilesystemMediaArchive` 类未定义，编译报错。

- [ ] **Step 3: 实现 `FilesystemMediaArchive.kt`**

创建 `agent/session/src/main/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchive.kt`：

```kotlin
package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import java.io.File
import java.util.Base64
import java.util.UUID

/**
 * [MediaArchive] 的文件系统默认实现 —— 作为 agent/core 的 caller 由 agent/session 模块提供。
 * 纯 IO（store/resolve）,不决定"什么值不值得落盘"——阈值由 [ArchivingMemory] 内部决定。
 *
 * 注入点：[SessionRepository.hydrateSession] 把 archive 实例传给 [JsonlBackedMemory],
 * 所有上层 Memory 通过 `Memory by` delegate 自动转发。
 *
 * caller app 如需自定义 archive (S3/DB/加密/TTL等), 直接实现 [MediaArchive] 接口
 * 并在 [JsonlBackedMemory] 构造时注入即可。
 *
 * @param rootDir 字节文件存储根目录。init 时若不存在则 `mkdirs` 创建。
 *                路径失效语义(agent 重启 / 跨进程):[resolve] 找不到 fileId 时
 *                抛 [IllegalStateException],由 caller 决策恢复策略。
 */
public class FilesystemMediaArchive(
    private val rootDir: File,
) : MediaArchive {
    init {
        require(rootDir.exists() || rootDir.mkdirs()) {
            "Cannot create media root: $rootDir"
        }
    }

    override fun store(data: MediaSource.Data): MediaSource.Local {
        val fileId = UUID.randomUUID().toString()
        File(rootDir, fileId).writeBytes(Base64.getDecoder().decode(data.base64))
        return MediaSource.Local(fileId, data.mimeType)
    }

    override fun resolve(local: MediaSource.Local): MediaSource.Data {
        val file = File(rootDir, local.fileId)
        if (!file.exists()) throw IllegalStateException(
            "MediaArchive missing fileId=${local.fileId}",
        )
        return MediaSource.Data(
            mimeType = local.mimeType,
            base64 = Base64.getEncoder().encodeToString(file.readBytes()),
        )
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :agent:session:test --tests "io.github.yeyi.agent.session.FilesystemMediaArchiveTest"`
Expected: PASS —— 5 条全过。

- [ ] **Step 5: Commit**

```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" add agent/session/src/main/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchive.kt agent/session/src/test/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchiveTest.kt
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" commit -F - <<'EOF'
feat(session): 新增 FilesystemMediaArchive 文件系统默认实现

agent/session 作为 agent/core 的 caller,在 core 不内置
文件 IO 的前提下提供生产级默认实现。store 写 UUID 文件
+ base64 解码;resolve 读文件 + base64 编码;缺失 ID
抛 IllegalStateException(入参错误,不包装 AgentException,
让 caller 决策)。

注入路径:SessionRepository.hydrateSession 构造链
FilesystemMediaArchive → JsonlBackedMemory → JsonlConversation
→ ArchivingMemory,archive 实体只在最下层,其他层通过
Memory by 自动转发。

caller 自定义 archive(S3/DB/加密等)实现 MediaArchive
接口即可,无需修改 SDK。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
```

---

## Task 7: ArchivingMemory 外层归档装饰器

**Files:**
- Create: `agent/session/src/main/kotlin/io/github/yeyi/agent/session/ArchivingMemory.kt`
- Create: `agent/session/src/test/kotlin/io/github/yeyi/agent/session/ArchivingMemoryTest.kt`

**Interfaces:**
- Consumes: Task 2 的 `Memory` 接口 + `MediaArchive` 抽象字段
- Produces: `ArchivingMemory(decorated: Memory): Memory by decorated`，override `add()`：把超过 1KB base64 的 `Data` 转 `Local` 后转给 `decorated.add()`；`history()` / `rebuild()` / `mediaArchive` 透传

- [ ] **Step 1: 写 `ArchivingMemoryTest.kt`（7 条用例）**

创建 `agent/session/src/test/kotlin/io/github/yeyi/agent/session/ArchivingMemoryTest.kt`：

```kotlin
package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.memory.Memory
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class ArchivingMemoryTest {

    /**
     * 测试用下游 Memory: 记录所有 add 收到的 message,以及持有 archive 供检查 store 调用次数。
     */
    private class CapturingMemory(val archive: MediaArchive) : Memory {
        override val mediaArchive: MediaArchive = archive
        val added: MutableList<ChatMessage> = mutableListOf()

        override suspend fun add(message: ChatMessage) {
            added += message
        }
        override suspend fun history(): List<ChatMessage> = added.toList()
        override suspend fun rebuild(messages: List<ChatMessage>) {
            added.clear()
            added.addAll(messages)
        }
    }

    /** 测试用 archive: 每次 store 生成自增 ID,记录 store 调用次数 */
    private class CountingArchive : MediaArchive {
        var storeCount = 0
            private set
        private val backing: MutableMap<String, String> = mutableMapOf()
        override fun store(data: MediaSource.Data): MediaSource.Local {
            storeCount++
            val id = "stored-$storeCount"
            backing[id] = data.base64
            return MediaSource.Local(id, data.mimeType)
        }
        override fun resolve(local: MediaSource.Local): MediaSource.Data =
            MediaSource.Data(local.mimeType, backing[local.fileId] ?: "")
    }

    @Test
    fun `add User with Data at boundary 1024 passes through unchanged`() = runTest {
        val archive = CountingArchive()
        val decorated = CapturingMemory(archive)
        val archiving = ArchivingMemory(decorated)
        val data = MediaSource.Data("image/jpeg", "x".repeat(1024))

        archiving.add(ChatMessage.User(listOf(ContentPart.Image(data))))

        assertEquals(0, archive.storeCount, "1024 base64 chars should NOT trigger archive")
        assertEquals(1, decorated.added.size)
        val src = (decorated.added[0].parts[0] as ContentPart.Image).source
        assertEquals(data, src)  // 透传
    }

    @Test
    fun `add User with Data over 1024 archives to Local`() = runTest {
        val archive = CountingArchive()
        val decorated = CapturingMemory(archive)
        val archiving = ArchivingMemory(decorated)
        val data = MediaSource.Data("image/jpeg", "x".repeat(1025))

        archiving.add(ChatMessage.User(listOf(ContentPart.Image(data))))

        assertEquals(1, archive.storeCount)
        val src = (decorated.added[0].parts[0] as ContentPart.Image).source
        assertTrue(src is MediaSource.Local)
        assertEquals("image/jpeg", src.mimeType)
    }

    @Test
    fun `add User with empty base64 does not archive`() = runTest {
        val archive = CountingArchive()
        val decorated = CapturingMemory(archive)
        val archiving = ArchivingMemory(decorated)
        val data = MediaSource.Data("image/jpeg", "")

        archiving.add(ChatMessage.User(listOf(ContentPart.Image(data))))

        assertEquals(0, archive.storeCount)
    }

    @Test
    fun `add ToolResult with large Data archives to Local`() = runTest {
        val archive = CountingArchive()
        val decorated = CapturingMemory(archive)
        val archiving = ArchivingMemory(decorated)
        val data = MediaSource.Data("image/jpeg", "x".repeat(2000))

        archiving.add(
            ChatMessage.ToolResult(
                toolCallId = "c1",
                toolName = "echo",
                parts = listOf(ContentPart.Image(data)),
            )
        )

        assertEquals(1, archive.storeCount)
        val src = (decorated.added[0].parts[0] as ContentPart.Image).source
        assertTrue(src is MediaSource.Local)
    }

    @Test
    fun `add System and Assistant pass through without archive`() = runTest {
        val archive = CountingArchive()
        val decorated = CapturingMemory(archive)
        val archiving = ArchivingMemory(decorated)

        archiving.add(ChatMessage.System("you are helpful"))
        archiving.add(ChatMessage.Assistant("ok"))

        assertEquals(0, archive.storeCount)
        assertEquals(2, decorated.added.size)
    }

    @Test
    fun `history and rebuild forward to decorated without triggering archive`() = runTest {
        val archive = CountingArchive()
        val decorated = CapturingMemory(archive)
        val archiving = ArchivingMemory(decorated)
        val messages = listOf(
            ChatMessage.System("sys"),
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/jpeg", "x".repeat(2000)))))
        )

        archiving.rebuild(messages)
        assertEquals(0, archive.storeCount, "rebuild should NOT trigger archive")
        assertEquals(messages, decorated.added)

        val history = archiving.history()
        assertEquals(messages, history)
    }

    @Test
    fun `mediaArchive forwards to decorated archive instance`() = runTest {
        val archive = CountingArchive()
        val decorated = CapturingMemory(archive)

        assertSame(decorated.mediaArchive, ArchivingMemory(decorated).mediaArchive)
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :agent:session:test --tests "io.github.yeyi.agent.session.ArchivingMemoryTest"`
Expected: FAIL —— `ArchivingMemory` 类未定义，编译报错。

- [ ] **Step 3: 实现 `ArchivingMemory.kt`**

创建 `agent/session/src/main/kotlin/io/github/yeyi/agent/session/ArchivingMemory.kt`：

```kotlin
package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.Memory

/**
 * 归档外层装饰器 —— 写侧职责：把超过 1KB 的 [MediaSource.Data] 通过
 * [decorated] 的 [io.github.yeyi.agent.memory.MediaArchive] 转 [MediaSource.Local],
 * 减少 storage 体积。
 *
 * **不**持有 archive —— 通过 `Memory by decorated` delegate 拿到 `decorated.mediaArchive`。
 * archive 实体只注入最下层 (典型为持久化场景的 [JsonlBackedMemory] 或单 session 的
 * [io.github.yeyi.agent.memory.InMemoryMemory]),所有上层透明转发,避免重复注入。
 *
 * `history()` / `rebuild()` 透传 —— 下游返回的 message 已经是 archived 状态
 * (即:本装饰器写入时已转 Local, history() 读到一致)。
 *
 * 阈值 1024 = base64 长度 (≈ 768B 原始字节),设计依据:
 * [JsonlConversation.pageSizeThreshold] 默认 20KB 的 1/20,避免单图占满整 page。
 * 下沉到 [archiveIfLarge] 私有方法不暴露 caller —— 如未来真出现反馈需要调整,
 * 再考虑提升为构造参数。
 *
 * 仅 [ChatMessage.User] / [ChatMessage.ToolResult] 内的 Image/Audio/Video
 * parts 会被检查;System / Assistant 不含 media 透传。
 */
public class ArchivingMemory(
    private val decorated: Memory,
) : Memory by decorated {

    override suspend fun add(message: ChatMessage) {
        decorated.add(archiveLargeMedia(message))
    }

    private fun archiveLargeMedia(message: ChatMessage): ChatMessage = when (message) {
        is ChatMessage.User, is ChatMessage.ToolResult -> message.copy(
            parts = message.parts.map { archiveIfLarge(it) },
        )
        is ChatMessage.System, is ChatMessage.Assistant -> message
    }

    private fun archiveIfLarge(part: ContentPart): ContentPart {
        val src = part.source
        return if (src is MediaSource.Data && src.base64.length > 1024) {
            part.copy(source = decorated.mediaArchive.store(src))
        } else part
    }
}
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :agent:session:test --tests "io.github.yeyi.agent.session.ArchivingMemoryTest"`
Expected: PASS —— 7 条全过。

- [ ] **Step 5: Commit**

```bash
"D:/Program Files/Git/cmd/git.exe" -D:/yeyi/AI/agent-sdk add agent/session/src/main/kotlin/io/github/yeyi/agent/session/ArchivingMemory.kt agent/session/src/test/kotlin/io/github/yeyi/agent/session/ArchivingMemoryTest.kt
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" commit -F - <<'EOF'
feat(session): 新增 ArchivingMemory 外层归档装饰器

写侧职责:add() 先调 archiveLargeMedia 把超过 1KB 的
Data 转 Local,再把 archived 版本转给 decorated。
history()/rebuild() 透传(下游返回的 message 已是
archived 状态,保持一致)。

阈值 1024 = JsonlConversation.pageSizeThreshold / 20,
下沉到 archiveIfLarge 私有方法,不暴露构造参数。设计
依据:1KB 以下的纯色 logo / favicon 之类 inline 更划算,
避免磁盘 IO + 多一条 cleanup entry。

archive 实体不持有 —— 通过 Memory by decorated delegate
拿到 decorated.mediaArchive,归档时调 decorated.mediaArchive.store()。
单一注入点(最下层 JsonlBackedMemory 或 InMemoryMemory),
其他层透明转发。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
```

---

## Task 8: JsonlBackedMemory 加 mediaArchive 构造参数

**Files:**
- Modify: `agent/session/src/main/kotlin/io/github/yeyi/agent/session/JsonlBackedMemory.kt:13`（加 `mediaArchive` 构造参数 + override）
- Modify: `agent/session/src/test/kotlin/io/github/yeyi/agent/session/JsonlBackedMemoryTest.kt`（如果存在，加 mediaArchive 字段测试；如不存在则跳过此步）

**Interfaces:**
- Consumes: Task 2 的 `MediaArchive`
- Produces: `JsonlBackedMemory(file: File, override val mediaArchive: MediaArchive): Memory`

- [ ] **Step 1: 检查现有 `JsonlBackedMemoryTest.kt`**

Run: `find agent/session/src/test -name "JsonlBackedMemoryTest.kt"`
- 如不存在：本任务 Step 4 测试新增放最后，不写测试文件（现有 `JsonlBackedMemory` 测试由 SessionRepositoryTest 通过 hydrateSession 间接覆盖）
- 如存在：进入 Step 2 加测试

- [ ] **Step 2: （仅当测试文件存在时）在 `JsonlBackedMemoryTest.kt` 加 1 条 mediaArchive 字段用例**

```kotlin
// append to JsonlBackedMemoryTest.kt (if it exists)

import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.llm.MediaSource
import kotlin.test.assertSame

@Test
fun `mediaArchive field returns injected archive instance`() = runTest {
    val archive = object : MediaArchive {
        override fun store(data: MediaSource.Data) = MediaSource.Local("x", data.mimeType)
        override fun resolve(local: MediaSource.Local) = MediaSource.Data(local.mimeType, "B64")
    }
    val memory = JsonlBackedMemory(file, archive)
    assertSame(archive, memory.mediaArchive)
}
```

- [ ] **Step 3: 修改 `JsonlBackedMemory.kt`**

修改 `agent/session/src/main/kotlin/io/github/yeyi/agent/session/JsonlBackedMemory.kt` line 13：

```kotlin
public class JsonlBackedMemory(
    private val file: File,
    override val mediaArchive: MediaArchive,
) : Memory {
```

加 import（如果还没有 `MediaArchive`）：
```kotlin
import io.github.yeyi.agent.memory.MediaArchive
```

- [ ] **Step 4: 运行 session 模块测试确认未坏（archive 构造参数会破坏所有现有构造点）**

Run: `./gradlew :agent:session:test`
Expected: FAIL —— 现有 `JsonlBackedMemory(File)` 调用方（`SessionRepository.hydrateSession`）需要同步修改才能编译。这是预期的 —— Task 9 会修 SessionRepository。

确认错误是"JsonlBackedMemory 缺少 mediaArchive 参数"，而不是其他。

- [ ] **Step 5: Commit**

```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" add agent/session/src/main/kotlin/io/github/yeyi/agent/session/JsonlBackedMemory.kt
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" commit -F - <<'EOF'
feat(session): JsonlBackedMemory 加 mediaArchive 构造参数

JsonlBackedMemory 作为持久化路径的最下层,接收 archive
注入。archive 实体只在这一层持有,所有上层 Memory
(JsonlConversation / ArchivingMemory / RoundsBoundedMemory
/ ReadOnlyMemory) 通过 Memory by / get() = inner.mediaArchive
透明转发。

caller 不直接构造 JsonlBackedMemory —— 通过
SessionManager 拿到的 session.memory 已持有整条链
(ArchivingMemory → JsonlConversation → JsonlBackedMemory
→ FilesystemMediaArchive)。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
```

注意：此 commit 后 `./gradlew :agent:session:test` 会失败（SessionRepository 未更新），但 Task 9 修。

---

## Task 9: SessionRepository per-session 目录 + 构造链 + deleteSession

**Files:**
- Modify: `agent/session/src/main/kotlin/io/github/yeyi/agent/session/SessionRepository.kt:25-38`（删 `getMemoryFile` / `getConversationDir` 旧实现）
- Modify: `agent/session/src/main/kotlin/io/github/yeyi/agent/session/SessionRepository.kt:71-79`（`hydrateSession` 构造链 + 加 `getSessionDir` / `getMediaRoot` / 新 `getMemoryFile` / 新 `getConversationDir`）
- Modify: `agent/session/src/main/kotlin/io/github/yeyi/agent/session/SessionRepository.kt:108-127`（`deleteSession` 改 deleteRecursively）
- Create: `agent/session/src/test/kotlin/io/github/yeyi/agent/session/SessionRepositoryTest.kt`（新增测试）

**Interfaces:**
- Consumes: Task 6 的 `FilesystemMediaArchive`、Task 7 的 `ArchivingMemory`、Task 8 的 `JsonlBackedMemory`
- Produces:
  - `getSessionDir(accountId, sessionId)` → `sessions/{accountId}/{sessionId}/`
  - `getMemoryFile` / `getConversationDir` / `getMediaRoot` 三个 sibling
  - `hydrateSession` 构造链：`FilesystemMediaArchive → JsonlBackedMemory → JsonlConversation → ArchivingMemory`
  - `deleteSession` 用 `getSessionDir(...).deleteRecursively()`

- [ ] **Step 1: 写 `SessionRepositoryTest.kt`（5 条用例）**

创建 `agent/session/src/test/kotlin/io/github/yeyi/agent/session/SessionRepositoryTest.kt`：

```kotlin
package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import java.io.File
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class SessionRepositoryTest {

    private lateinit var tempDir: File
    private lateinit var repo: SessionRepository

    @Before
    fun setUp() {
        tempDir = Files.createTempDirectory("session-repo-test").toFile()
        repo = SessionRepository(tempDir)
    }

    @After
    fun tearDown() {
        tempDir.deleteRecursively()
    }

    @Test
    fun `createSession creates per-session directory with three siblings`() {
        val session = repo.createSession("alice", "chat1", null)
        val sessionDir = File(File(tempDir, "agent/sessions/alice"), session.id)
        assertTrue(sessionDir.isDirectory)

        assertTrue(File(sessionDir, "memory.jsonl").exists().not() ||
            File(sessionDir, "memory.jsonl").exists(),
            "memory.jsonl path should be defined")
        assertTrue(File(sessionDir, "conversations").isDirectory ||
            !File(sessionDir, "conversations").exists(),
            "conversations dir should exist after first add")
        assertTrue(File(sessionDir, "media").isDirectory,
            "media dir should exist after first archive")
    }

    @Test
    fun `hydrateSession builds ArchivingMemory containing JsonlConversation`() {
        val session = repo.createSession("alice", "chat1", null)

        // session.memory 真实类型应该是 ArchivingMemory
        assertTrue(session.memory is ArchivingMemory,
            "expected ArchivingMemory, got ${session.memory::class.simpleName}")
    }

    @Test
    fun `large Data added to session memory gets archived and resolves back`() = runTest {
        val session = repo.createSession("alice", "chat1", null)
        val data = MediaSource.Data("image/jpeg", "x".repeat(2048))

        session.memory.add(ChatMessage.User(listOf(ContentPart.Image(data))))
        val history = session.memory.history()
        val src = (history[0].parts[0] as ContentPart.Image).source

        assertTrue(src is MediaSource.Local,
            "Data > 1024 base64 should be archived to Local, got ${src::class.simpleName}")

        // 验证 mediaArchive 能 resolve 落盘字节
        val resolved = session.memory.mediaArchive.resolve(src)
        assertEquals("x".repeat(2048), resolved.base64)
    }

    @Test
    fun `deleteSession removes entire per-session directory but keeps sessions_jsonl`() = runTest {
        val session = repo.createSession("alice", "chat1", null)
        val sessionDir = File(File(tempDir, "agent/sessions/alice"), session.id)

        // 触发 archive 创建 media/
        session.memory.add(ChatMessage.User(listOf(ContentPart.Image(
            MediaSource.Data("image/jpeg", "x".repeat(2048))
        ))))
        assertTrue(sessionDir.isDirectory)
        assertTrue(File(sessionDir, "media").isDirectory)

        val sessionsFile = File(File(tempDir, "agent/sessions/alice"), "sessions.jsonl")
        assertTrue(sessionsFile.exists())

        val result = repo.deleteSession("alice", session.id)

        assertNotNull(result)
        assertTrue(!sessionDir.exists(),
            "per-session directory should be deleted: ${sessionDir.absolutePath}")

        // sessions.jsonl 索引同步移除（条目删除后文件内容应为空或不存在）
        if (sessionsFile.exists()) {
            val content = sessionsFile.readText().trim()
            assertEquals("", content, "sessions.jsonl should have no entries")
        }
    }

    @Test
    fun `findSession returns null after deleteSession`() {
        val session = repo.createSession("alice", "chat1", null)
        assertNotNull(repo.findSession("alice", session.id))

        repo.deleteSession("alice", session.id)
        assertEquals(null, repo.findSession("alice", session.id))
    }
}
```

- [ ] **Step 2: 运行测试验证失败**

Run: `./gradlew :agent:session:test --tests "io.github.yeyi.agent.session.SessionRepositoryTest"`
Expected: FAIL —— Task 8 改了 JsonlBackedMemory 构造参数但 SessionRepository 还没改，编译可能先错；即便编译过，新测试也会因 archive 没注入或 media dir 没创建而失败。

- [ ] **Step 3: 修改 `SessionRepository.kt`**

修改 `agent/session/src/main/kotlin/io/github/yeyi/agent/session/SessionRepository.kt`：

a) 删除旧 `getMemoryFile` / `getConversationDir`（line 25-38）：

```kotlin
    // 删除这两段旧实现,改为下面的 per-session 目录版本
    private fun getMemoryFile(accountId: String, sessionId: String): File { ... }
    private fun getConversationDir(accountId: String, sessionId: String): File { ... }
```

b) 在 `getSessionsFile` 之后、`readSessionsFromFile` 之前加新实现：

```kotlin
    /**
     * 每个 session 一个独立目录,位于 `sessions/{accountId}/{sessionId}/` 下,
     * 内部三块同级:
     * - `memory.jsonl` —— [JsonlBackedMemory] 持久化 (Memory 接口)
     * - `conversations/page*.jsonl` —— [JsonlConversation] 分页存储 (Conversation 接口)
     * - `media/{uuid}` —— [FilesystemMediaArchive] 字节存档
     *
     * deleteSession 时整 `getSessionDir()` 目录 deleteRecursively 一并清理。
     */
    private fun getSessionDir(accountId: String, sessionId: String): File =
        File(getUserDir(accountId), sanitizeForPath(sessionId))
            .also { it.mkdirs() }

    private fun getMemoryFile(accountId: String, sessionId: String): File =
        File(getSessionDir(accountId, sessionId), "memory.jsonl")

    private fun getConversationDir(accountId: String, sessionId: String): File =
        File(getSessionDir(accountId, sessionId), "conversations")

    private fun getMediaRoot(accountId: String, sessionId: String): File =
        File(getSessionDir(accountId, sessionId), "media")
```

c) 修改 `hydrateSession`（line 71-79）：

```kotlin
    /**
     * 构造链:FilesystemMediaArchive + ArchivingMemory(外层归档) →
     * JsonlConversation → JsonlBackedMemory。
     *
     * archive 由 session 模块内部创建,不暴露给 caller —— SDK 不该决定 IO 路径。
     * ArchivingMemory 在链的最外层负责归档,JsonlConversation / JsonlBackedMemory
     * 都只做纯存储,两边 (page*.jsonl + memory.jsonl) 落盘形态由 ArchivingMemory
     * 统一保证一致。
     */
    private fun hydrateSession(session: Session): Session {
        val archive = FilesystemMediaArchive(
            getMediaRoot(session.accountId, session.id),
        )
        val rawMemory = JsonlBackedMemory(
            getMemoryFile(session.accountId, session.id),
            archive,  // 注入到最下层,所有上层通过 Memory by 自动转发
        )
        val conversation = JsonlConversation(
            getConversationDir(session.accountId, session.id),
            rawMemory,
        )
        val memory = ArchivingMemory(conversation)
        return session.copy(
            _memory = memory,
            _conversation = conversation,
        )
    }
```

d) 修改 `deleteSession`（line 108-127）：

```kotlin
    /**
     * 删除 session 下的所有内容 (`memory.jsonl` + `conversations/` + `media/`),
     * 索引条目同步从 `sessions.jsonl` 移除。整 session 目录在 [getSessionDir]
     * 下,一行 deleteRecursively 覆盖三块。
     *
     * @return 被删除的 session（删除前状态），找不到返回 null
     */
    public fun deleteSession(accountId: String, sessionId: String): Session? {
        val sessions = readSessionsFromFile(accountId)
        val toDelete = sessions.firstOrNull { it.id == sessionId } ?: return null
        val remaining = sessions.filterNot { it.id == sessionId }

        val sessionsFile = getSessionsFile(accountId)
        sessionsFile.writeText(remaining.joinToString("\n") { json.encodeToString(it) })

        val sessionDir = getSessionDir(accountId, sessionId)
        if (sessionDir.exists()) {
            sessionDir.deleteRecursively()
        }

        return hydrateSession(toDelete)
    }
```

- [ ] **Step 4: 运行测试验证通过**

Run: `./gradlew :agent:session:test`
Expected: PASS —— 5 条新 SessionRepositoryTest + 现有 JsonlConversation 等测试 + JsonlBackedMemoryTest（如有）全过。

- [ ] **Step 5: 运行全 SDK 测试确认无回归**

Run: `./gradlew test`
Expected: PASS —— 整个 SDK 测试套件通过。

- [ ] **Step 6: Commit**

```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" add agent/session/src/main/kotlin/io/github/yeyi/agent/session/SessionRepository.kt agent/session/src/test/kotlin/io/github/yeyi/agent/session/SessionRepositoryTest.kt
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" commit -F - <<'EOF'
refactor(session): SessionRepository 改 per-session 目录 + 构造链 + deleteRecursively

统一路径到 sessions/{accountId}/{sessionId}/ 三块 sibling:
- memory.jsonl (JsonlBackedMemory)
- conversations/ (JsonlConversation 分页)
- media/ (FilesystemMediaArchive 字节)

hydrateSession 构造链:FilesystemMediaArchive →
JsonlBackedMemory(archive 注入最下层)→ JsonlConversation
(Memory by innerMemory 自动转发 archive)→ ArchivingMemory
(: Memory by decorated 自动转发 archive + 写侧硬编码
1KB 阈值转 Local)。

deleteSession 改为 getSessionDir(...).deleteRecursively()
一行清理 memory + conversations + media 三块,索引条目
从 sessions.jsonl 同步移除。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
```

---

## Task 10: Provider Local fail-fast

**Files:**
- Modify: `agent/providers/openai/src/main/kotlin/io/github/yeyi/agent/providers/openai/OpenAiMapping.kt:87-115`
- Modify: `agent/providers/anthropic/src/main/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicMapping.kt:101-118`
- Modify: `agent/providers/openai/src/test/kotlin/.../OpenAiMappingTest.kt`（如不存在则跳过测试）
- Modify: `agent/providers/anthropic/src/test/kotlin/.../AnthropicMappingTest.kt`（如不存在则跳过测试）

**Interfaces:**
- Consumes: Task 1 的 `MediaSource.Local`
- Produces: OpenAI/Anthropic mapping 收到 Local 时抛 `AgentException.UnsupportedContent`

- [ ] **Step 1: 检查两个测试文件**

Run:
```bash
find agent/providers/openai/src/test -name "OpenAiMappingTest.kt" 2>/dev/null
find agent/providers/anthropic/src/test -name "AnthropicMappingTest.kt" 2>/dev/null
```

如不存在：跳过测试新增，进入 Step 3。
如存在：进入 Step 2 加测试。

- [ ] **Step 2: （仅当测试存在时）在两个 Mapping 测试加 Local fail-fast 用例**

OpenAiMappingTest:
```kotlin
// append to OpenAiMappingTest.kt

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.MediaSource

@Test
fun `Local MediaSource throws UnsupportedContent for image`() {
    val local = MediaSource.Local("id1", "image/jpeg")
    val ex = assertFailsWith<AgentException.UnsupportedContent> {
        mapImageToOpenAiPublic(local)  // 或现有 helper,视测试结构而定
    }
    assertTrue(ex.message!!.contains("Local"))
}

@Test
fun `Local MediaSource throws UnsupportedContent for audio`() {
    val local = MediaSource.Local("id1", "audio/wav")
    val ex = assertFailsWith<AgentException.UnsupportedContent> {
        mapAudioToOpenAiPublic(local)
    }
    assertTrue(ex.message!!.contains("Local"))
}
```

AnthropicMappingTest:
```kotlin
// append to AnthropicMappingTest.kt

@Test
fun `Local MediaSource throws UnsupportedContent for image`() {
    val local = MediaSource.Local("id1", "image/jpeg")
    val ex = assertFailsWith<AgentException.UnsupportedContent> {
        mapImageToAnthropicPublic(local)
    }
    assertTrue(ex.message!!.contains("Local"))
}

@Test
fun `Local MediaSource throws UnsupportedContent for audio`() {
    val local = MediaSource.Local("id1", "audio/wav")
    val ex = assertFailsWith<AgentException.UnsupportedContent> {
        mapAudioToAnthropicPublic(local)
    }
    assertTrue(ex.message!!.contains("Local"))
}

@Test
fun `Local MediaSource throws UnsupportedContent for video`() {
    val local = MediaSource.Local("id1", "video/mp4")
    val ex = assertFailsWith<AgentException.UnsupportedContent> {
        mapVideoToAnthropicPublic(local)
    }
    assertTrue(ex.message!!.contains("Local"))
}
```

> 注意：测试方法名 + 调用方式需根据现有测试文件的 helper 调整。如测试文件是 internal fun 而非 public，调整调用为 `mapImageToOpenAi(local)` 等。如不存在 helper，需要先在测试文件内创建 helper wrapper 把 internal 提升为可见。

- [ ] **Step 3: 修改 `OpenAiMapping.kt`**

修改 `agent/providers/openai/src/main/kotlin/io/github/yeyi/agent/providers/openai/OpenAiMapping.kt`：

a) 修改 `mapImageToOpenAi`（line 87-100）的 when —— 在 `is MediaSource.FileId ->` 后加：
```kotlin
    is MediaSource.Local -> throw AgentException.UnsupportedContent(
        "MediaSource.Local requires ModalityAdapter to resolve to Data first"
    )
```

b) 修改 `mapAudioToOpenAi`（line 102-115）的 when —— 在 `is MediaSource.FileId ->` 后加：
```kotlin
    is MediaSource.Local -> throw AgentException.UnsupportedContent(
        "MediaSource.Local requires ModalityAdapter to resolve to Data first"
    )
```

- [ ] **Step 4: 修改 `AnthropicMapping.kt`**

修改 `agent/providers/anthropic/src/main/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicMapping.kt`：

a) 修改 `mapImageToAnthropic`（line 101-108）的 when —— 在 `is MediaSource.FileId ->` 前加：
```kotlin
    is MediaSource.Local -> throw AgentException.UnsupportedContent(
        "MediaSource.Local requires ModalityAdapter to resolve to Data first"
    )
```

b) 修改 `mapAudioToAnthropic`（line 110）—— 同 pattern（复用 `mapImageToAnthropic`，Local fail-fast 自动覆盖）。

如 `mapAudioToAnthropic` 当前是 `mapImageToAnthropic(source)` 的一行 delegate，则**已经自动覆盖** Local fail-fast，无需新增分支。

c) 修改 `mapVideoToAnthropic`（line 112-118）的 when —— 在 `is MediaSource.Data ->` 后加：
```kotlin
    is MediaSource.Local -> throw AgentException.UnsupportedContent(
        "MediaSource.Local requires ModalityAdapter to resolve to Data first"
    )
```

- [ ] **Step 5: 编译两个 provider 模块**

Run: `./gradlew :agent:providers:openai:compileKotlin :agent:providers:anthropic:compileKotlin`
Expected: PASS —— when 分支穷尽性满足（FileId/Data/Http/Local 都有处理）。

- [ ] **Step 6: 运行两个 provider 模块测试（如有）**

Run:
```bash
./gradlew :agent:providers:openai:test
./gradlew :agent:providers:anthropic:test
```
Expected: PASS —— 现有测试 + 新增 Local fail-fast 用例通过。

- [ ] **Step 7: 全 SDK 回归测试**

Run: `./gradlew test`
Expected: PASS —— 全 SDK 测试通过。

- [ ] **Step 8: Commit**

```bash
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" add agent/providers/openai/src/main/kotlin/io/github/yeyi/agent/providers/openai/OpenAiMapping.kt agent/providers/anthropic/src/main/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicMapping.kt
"D:/Program Files/Git/cmd/git.exe" -C "D:/yeyi/AI/agent-sdk" commit -F - <<'EOF'
feat(providers): OpenAI/Anthropic mapping 加 Local fail-fast

ModalityAdapter 正常路径下在 buildRequest 内 resolve 完所有
Local;非正常路径(边界情况,如 adapter 被关掉、custom 实现
不 resolve)由 provider 抛 UnsupportedContent 兜底,绝不让
Local 直接落到 provider wire format。

- OpenAiMapping.mapImageToOpenAi + mapAudioToOpenAi:
  Local → UnsupportedContent
- AnthropicMapping.mapImageToAnthropic (delegated to by
  mapAudioToAnthropic) + mapVideoToAnthropic:
  Local → UnsupportedContent

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
```

---

## Self-Review

### Spec 覆盖检查

| Spec 节 | 覆盖任务 |
|---|---|
| §0 元信息（破坏性变更）| T1, T2, T5 (各修改接口签名,符合 partial breaking change 声明) |
| §3.1 MediaSource.Local | T1 |
| §3.2 MediaArchive fun interface | T2 |
| §4 Memory 扩展（4 个实现）| T2 (InMemoryMemory/RoundsBoundedMemory/ReadOnlyMemory), T8 (JsonlBackedMemory), T6+T7+T9 间接覆盖（构造链）|
| §5 ModalityAdapter + DefaultModalityAdapter + adaptModality 下沉 | T3 |
| §5 toTextMessage Local 分支 + 占位截断策略 | T4 |
| §6 ReActAgent + AgentBuilder | T5 |
| §7 Provider fail-fast | T10 |
| §8 错误处理（IllegalStateException / 不包装）| T2 Step 4 (InMemoryMediaArchive), T6 Step 3 (FilesystemMediaArchive), T10 (UnsupportedContent) |
| §9 行为变更兼容性 | T5 默认注入 DefaultModalityAdapter, 行为与旧实现一致 |
| §11 文件清单 | T1-T10 全覆盖新增/修改文件 |
| §12 测试策略 | T1-T10 每个 task 都有测试, 覆盖 7 路 adapter 分支 + 1KB 阈值 + per-session 目录 + provider fail-fast |
| §13 风险缓解 | T2 (Memory 字段传播), T6 (mkdirs 兜底 + IllegalStateException), T7 (阈值下沉 + JsonlConversation 不动) |

### 占位符扫描

- 无 "TBD" / "TODO" / "implement later"
- 无 "Add appropriate error handling"
- 无 "Write tests for the above"
- 所有 Step 含具体代码（Kotlin 完整片段）
- 所有 commit 含完整 message body

### 类型一致性检查

| 符号 | 定义位置 | 使用位置 | 一致? |
|---|---|---|---|
| `MediaSource.Local(fileId, mimeType)` | T1 | T2 (InMemoryMediaArchive.store), T3 (resolveUserMedia), T4 (describeMediaSource), T6 (FilesystemMediaArchive.store), T7 (无直接使用,通过 archive 间接), T9 (隐式通过 JsonlBackedMemory), T10 (when 分支) | ✅ |
| `MediaArchive` fun interface | T2 | T3 (参数), T6/T7 (实现), T9 (注入 JsonlBackedMemory) | ✅ |
| `Memory.mediaArchive: MediaArchive` 抽象字段 | T2 | T2 (4 实现), T8 (JsonlBackedMemory override), T9 (通过 chain 间接) | ✅ |
| `ModalityAdapter` fun interface | T3 | T5 (ReActAgent 构造参数), T5 (AgentBuilder 默认), T3 测试 | ✅ |
| `DefaultModalityAdapter()` 无构造参数 | T3 | T5 (build 默认) | ✅ |
| `ArchivingMemory(decorated: Memory): Memory by decorated` | T7 | T9 (hydrateSession), T7 测试 | ✅ |
| `FilesystemMediaArchive(rootDir: File)` | T6 | T9 (hydrateSession 构造) | ✅ |
| `JsonlBackedMemory(file, mediaArchive)` | T8 | T9 (hydrateSession 构造) | ✅ |
| `getSessionDir/getMemoryFile/getConversationDir/getMediaRoot` 私有方法 | T9 | T9 (自身调用) | ✅ |
| `deleteRecursively()` 一行清理 | T9 | T9 deleteSession | ✅ |

### 行为不变性检查

- T1-T4 + T5 默认配置下，行为与原实现完全一致（`DefaultModalityAdapter` 三步 == 原 `buildRequest` 内联逻辑）
- T7 `ArchivingMemory` 是**新外层装饰器**（持久化路径默认开启，单 session 路径不变）—— `InMemoryMemory` 测试不受影响
- T6 `FilesystemMediaArchive` 是**新实现**（`InMemoryMediaArchive` 不变）—— `InMemoryMemory` 路径行为不变
- T9 `deleteRecursively()` 行为与原 `delete(memoryFile) + deleteRecursively(conversationDir)` 等价（多删 `media/`，但 `media/` 在原代码中不存在，所以无差别）
- T10 `Local` 在 provider 抛异常，是新行为（之前 `Local` 类型不存在）

### 范围检查

单 spec, 单一集成功能（多模态 media archive），未跨独立子系统。

### 边界情况检查

- T2 Step 4 `InMemoryMediaArchive.resolve` 缺失 fileId 抛 IllegalStateException（spec §8 要求）✓
- T6 Step 3 `FilesystemMediaArchive.resolve` 缺失 fileId 抛 IllegalStateException ✓
- T6 Step 3 `init` mkdirs 兜底（path 失效语义）✓
- T7 Step 3 `archiveIfLarge` 1KB 边界用 `> 1024`（spec §12 boundary 1024/1025/0 三个用例覆盖）✓
- T9 Step 3 `deleteRecursively` 包含 `media/`（新加 sibling）✓
- T10 Step 3-4 Local fail-fast 抛 `UnsupportedContent`（spec §7 要求）✓

---

## Execution Handoff

Plan 已保存到 `docs/superpowers/plans/2026-08-19-modality-media-archive.md`。

两种执行方式：

**1. Subagent-Driven (recommended)** — 每个 task 派一个独立 subagent 实施，task 间做 spec compliance + code quality 审查，最快迭代。

**2. Inline Execution** — 当前 session 直接按顺序执行，batch + checkpoint。

请选择执行方式。
