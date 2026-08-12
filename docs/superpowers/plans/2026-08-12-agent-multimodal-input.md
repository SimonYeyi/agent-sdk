# Agent 多模态输入实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `Agent.run / runStream` 入参从 `String` 升级为多模态 `AgentQuery`，支撑 image / audio / video 输入，并在 OpenAI / Anthropic provider 把 `List<ContentPart>` 序列化为各自 wire format。

**Architecture:** 引入三层新类型 `MediaSource` (Http/Data/FileId) → `ContentPart` (Text/Image/Audio/Video) → `AgentQuery`，自下而上构建；`ChatMessage.User.content` 同步迁移为 `parts`，Provider 在实现层把 parts 序列化为各自协议；Video + Data(base64) 在实现层 fail-fast (UnsupportedContent)，type 层不禁止。

**Tech Stack:** Kotlin 2.x · kotlinx.serialization · kotlinx-coroutines · Gradle multi-module · JUnit 5 + kotlin.test

---

## Global Constraints

| 项 | 值 |
|---|---|
| 提交规范 | `git_commit.md`（type(scope): subject; 中文；不 push） |
| API 风格 | `@Serializable` sealed interface + data class；`public` 显式标注 |
| 测试风格 | JUnit 5 + kotlin.test；TDD：先写 failing test，再实现，再 commit |
| 提交粒度 | 每个 task 一个原子提交；atomic commit by file boundary |
| 命名风格 | 字段名 = 语义（如 `parts`、`agentQuery`），不绑死类型名 |
| 摘要规则 | User 多模态 parts → 文本直取 + 多模态压占位（`[image:...]` 等） |
| 错误处理 | 失败场景抛 `AgentException.UnsupportedContent`；视频 base64 不支持在 provider 实现层 fail-fast |
| wire 形态 | OpenAI `image_url` / `input_audio`；Anthropic `image` / `audio` / `video` block |
| 范围限制 | 不引入 `LocalPath`；不引入 `AgentQuery` metadata；不做 Assistant 多模态输出 |

---

## 文件结构

### 新增 (3)

- `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/MediaSource.kt` — Http / Data / FileId 三变体 sealed
- `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ContentPart.kt` — Text / Image / Audio / Video 四变体 sealed + `kind` 派生
- `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentQuery.kt` — 包装 `parts` + `text()` factory

### 修改（核心 + Provider + 调用点）(13+）

- `agent/core/.../llm/ChatMessage.kt` — `User.content: String` → `User.parts: List<ContentPart>`
- `agent/core/.../Agent.kt` — `run/runStream(input: String)` → `(query: AgentQuery)`
- `agent/core/.../AgentEvent.kt` — `Initial.userInput: String` → `Initial.agentQuery: AgentQuery`
- `agent/core/.../ReActAgent.kt` — 调用点同步（emit / memory.add / loop 签名）
- `agent/core/.../memory/RoundsBoundedMemory.kt` — 摘要路径按 parts inline
- `agent/core/.../AgentException.kt` — 新增 `UnsupportedContent` 子类
- `agent/providers/openai/.../OpenAiDtos.kt` — DTO 加 `OpenAiContentPart` / `OpenAiContent` 多态
- `agent/providers/openai/.../OpenAiMapping.kt` — User 分支按 part 分流
- `agent/providers/anthropic/.../AnthropicDtos.kt` — `AnthropicContentBlock` 加 Image / Audio / Video
- `agent/providers/anthropic/.../AnthropicMapping.kt` — User 分支按 part 分流
- 调用点同步：`agent/subagent`、`agent/team`、`agent/skill`、`agent/capability`、`agent/toolset`、`agent/mcp`、`agent/tool/compression`、`agent/tool/serialization`、`agent/hook`、各模块测试
- 调用点同步：`demos/agent`、`demos/team`、`gateway/*`

---

## 任务列表

每 task 独立可测，5–10 分钟 step 颗粒；TDD 三步（写 failing test → 实现 → 跑 PASS → commit）。

---

### Task 1: 新增 MediaSource

**Files:**
- Create: `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/MediaSource.kt`
- Test: `agent/core/src/test/kotlin/io/github/yeyi/agent/llm/MediaSourceTest.kt`

**Interfaces:**
- Produces: `public sealed interface MediaSource { data class Http(String url); data class Data(String mimeType, String base64); data class FileId(String id) }`
- 所有实现标 `@Serializable`，data class 字段是 `public val`

- [ ] **Step 1: 写 failing test**

`agent/core/src/test/kotlin/io/github/yeyi/agent/llm/MediaSourceTest.kt`:

```kotlin
package io.github.yeyi.agent.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSourceTest {
    private val json = Json

    @Test
    fun `serializes Http variant with @SerialName url`() {
        val src: MediaSource = MediaSource.Http("https://example.com/a.jpg")
        assertEquals("""{"type":"url","url":"https://example.com/a.jpg"}""", json.encodeToString(src))
    }

    @Test
    fun `serializes Data variant with mimeType and base64`() {
        val src: MediaSource = MediaSource.Data("image/jpeg", "BASE64DATA")
        assertEquals(
            """{"type":"data","mimeType":"image/jpeg","base64":"BASE64DATA"}""",
            json.encodeToString(src)
        )
    }

    @Test
    fun `serializes FileId variant with id`() {
        val src: MediaSource = MediaSource.FileId("file-abc")
        assertEquals("""{"type":"fileId","id":"file-abc"}""", json.encodeToString(src))
    }

    @Test
    fun `round-trips Http through JSON`() {
        val src: MediaSource = MediaSource.Http("https://x.com/y")
        val text = json.encodeToString(src)
        val back: MediaSource = json.decodeFromString(text)
        assertEquals(src, back)
    }
}
```

注- 上面的 `@SerialName` 是预期行为，Task 实现时通过在 data class 上加 `@SerialName("url")` 等注解实现。

- [ ] **Step 2: 跑测试，验证 FAIL**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.llm.MediaSourceTest"`
Expected: FAIL (`MediaSource` unresolved)

- [ ] **Step 3: 实现 MediaSource.kt**

`agent/core/src/main/kotlin/io/github/yeyi/agent/llm/MediaSource.kt`:

```kotlin
package io.github.yeyi.agent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 多媒体资源的统一来源抽象，三种模态 (image/audio/video) 共用同一组变体。
 *
 * - [Http]  : 公网 URL 或内网可路由 URL，由 LLM provider 主动 fetch。
 * - [Data]  : base64 内联；适用于 image 和短 audio；video 由 provider 实现层拒绝。
 * - [FileId]: provider 托管的文件 ID（OpenAI files API、Anthropic files API）。
 */
@Serializable
public sealed interface MediaSource {
    @Serializable
    @SerialName("url")
    public data class Http(public val url: String) : MediaSource

    @Serializable
    @SerialName("data")
    public data class Data(public val mimeType: String, public val base64: String) : MediaSource

    @Serializable
    @SerialName("fileId")
    public data class FileId(public val id: String) : MediaSource
}
```

- [ ] **Step 4: 跑测试，验证 PASS**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.llm.MediaSourceTest"`
Expected: 4 passed

- [ ] **Step 5: Commit**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/llm/MediaSource.kt \
        agent/core/src/test/kotlin/io/github/yeyi/agent/llm/MediaSourceTest.kt
git commit -m "$(cat <<'EOF'
feat(agent): 新增 MediaSource sealed (Http/Data/FileId)

LLM 多模态输入的统一来源抽象,三模态 (image/audio/video) 共用同一组变体。
Http 用于 URL fetch,Data 用于 base64 inline (image/audio 适用),FileId 用于
provider 托管。不引入本轮不需要的 LocalPath。
EOF
)"
```

---

### Task 2: 新增 ContentPart

**Files:**
- Create: `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ContentPart.kt`
- Test: `agent/core/src/test/kotlin/io/github/yeyi/agent/llm/ContentPartTest.kt`

**Interfaces:**
- Produces: `public sealed interface ContentPart { val kind: Kind; enum Kind { Text, Image, Audio, Video }; data class Text(text); data class Image(source: MediaSource); data class Audio(source: MediaSource); data class Video(source: MediaSource) }`
- 依赖 Task 1 的 MediaSource

- [ ] **Step 1: 写 failing test**

`agent/core/src/test/kotlin/io/github/yeyi/agent/llm/ContentPartTest.kt`:

```kotlin
package io.github.yeyi.agent.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentPartTest {
    private val json = Json

    @Test
    fun `kind returns Text for Text variant`() {
        val p: ContentPart = ContentPart.Text("hi")
        assertEquals(ContentPart.Kind.Text, p.kind)
    }

    @Test
    fun `kind returns Image for Image variant`() {
        val p: ContentPart = ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
        assertEquals(ContentPart.Kind.Image, p.kind)
    }

    @Test
    fun `kind returns Audio for Audio variant`() {
        val p: ContentPart = ContentPart.Audio(MediaSource.FileId("af-1"))
        assertEquals(ContentPart.Kind.Audio, p.kind)
    }

    @Test
    fun `kind returns Video for Video variant`() {
        val p: ContentPart = ContentPart.Video(MediaSource.Http("https://x.com/v.mp4"))
        assertEquals(ContentPart.Kind.Video, p.kind)
    }

    @Test
    fun `serializes Text variant`() {
        val p: ContentPart = ContentPart.Text("hello")
        assertEquals(
            """{"type":"text","text":"hello"}""",
            json.encodeToString(p)
        )
    }

    @Test
    fun `serializes Image variant with embedded MediaSource`() {
        val p: ContentPart = ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
        val s = json.encodeToString(p)
        assertEquals(
            """{"type":"image","source":{"type":"url","url":"https://x.com/a.jpg"}}""",
            s
        )
    }

    @Test
    fun `round-trips Image with Data source`() {
        val p: ContentPart = ContentPart.Image(MediaSource.Data("image/png", "XYZ"))
        val back: ContentPart = json.decodeFromString(json.encodeToString(p))
        assertEquals(p, back)
    }
}
```

- [ ] **Step 2: 跑测试，验证 FAIL**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.llm.ContentPartTest"`
Expected: FAIL

- [ ] **Step 3: 实现 ContentPart.kt**

`agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ContentPart.kt`:

```kotlin
package io.github.yeyi.agent.llm

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 用户回合（user turn）中的单条内容块。
 * 4 个变体独立 sealed 而非合并为 Media(kind, source), 三种媒体未来会
 * 各自演化出差异化约束 (image 的 detail、audio 的 format、video 的 clip window)。
 */
@Serializable
public sealed interface ContentPart {
    public val kind: Kind
        get() = when (this) {
            is Text -> Kind.Text
            is Image -> Kind.Image
            is Audio -> Kind.Audio
            is Video -> Kind.Video
        }

    public enum class Kind { Text, Image, Audio, Video }

    @Serializable
    @SerialName("text")
    public data class Text(public val text: String) : ContentPart

    @Serializable
    @SerialName("image")
    public data class Image(public val source: MediaSource) : ContentPart

    @Serializable
    @SerialName("audio")
    public data class Audio(public val source: MediaSource) : ContentPart

    @Serializable
    @SerialName("video")
    public data class Video(public val source: MediaSource) : ContentPart
}
```

- [ ] **Step 4: 跑测试，验证 PASS**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.llm.ContentPartTest"`
Expected: 7 passed

- [ ] **Step 5: Commit**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ContentPart.kt \
        agent/core/src/test/kotlin/io/github/yeyi/agent/llm/ContentPartTest.kt
git commit -m "$(cat <<'COMMIT_MSG'
feat(agent): 新增 ContentPart sealed (Text/Image/Audio/Video)

单条用户回合内容块;kind 由 sealed interface 内 when(this) DRY 派生,
将来加新 part 时编译器强制提示更新分支。四变体独立 sealed 而非合并为
Media(kind, source),为将来差异化约束 (image detail/audio format/
video clip window) 留扩展空间。
COMMIT_MSG
)"
```

---

### Task 3: 新增 AgentException.UnsupportedContent

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentException.kt`
- Test: `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentExceptionTest.kt`

**Interfaces:**
- Produces: `AgentException.UnsupportedContent(message: String) : AgentException(message)` — 现有 sealed class 的新子类

- [ ] **Step 1: 写 failing test**

`agent/core/src/test/kotlin/io/github/yeyi/agent/AgentExceptionTest.kt`:

```kotlin
package io.github.yeyi.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentExceptionTest {
    @Test
    fun `UnsupportedContent is an AgentException`() {
        val e = AgentException.UnsupportedContent("video base64 not supported")
        assertTrue(e is AgentException)
    }

    @Test
    fun `UnsupportedContent carries message`() {
        val e = AgentException.UnsupportedContent("OpenAI video not supported")
        assertEquals("OpenAI video not supported", e.message)
    }
}
```

- [ ] **Step 2: 跑测试，验证 FAIL**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.AgentExceptionTest"`
Expected: FAIL (`UnsupportedContent` unresolved)

- [ ] **Step 3: 在 AgentException.kt 加 UnsupportedContent 子类**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentException.kt`，在 sealed class 体内（已有 `LlmError` / `InvalidResponse` 等之后）追加：

```kotlin
    /**
     * Provider 拒绝某种内容形态（如 OpenAI video、video base64 等）。
     * 不在 type 层静态禁止——在 provider 实现层 fail-fast, 给未来扩展留口子。
     */
    public class UnsupportedContent(message: String) : AgentException(message)
```

- [ ] **Step 4: 跑测试，验证 PASS**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.AgentExceptionTest"`
Expected: 2 passed

- [ ] **Step 5: Commit**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/AgentException.kt \
        agent/core/src/test/kotlin/io/github/yeyi/agent/AgentExceptionTest.kt
git commit -m "$(cat <<'COMMIT_MSG'
feat(agent): AgentException 新增 UnsupportedContent 子类

Provider 拒绝某种内容形态时 (OpenAI video/video base64/file_id 等)
的语义错误,比裸 IllegalStateException 更可被 caller 精确捕获处理。
fail-fast 放在 provider 实现层而非 type 层。
COMMIT_MSG
)"
```

---

### Task 4: 迁移 ChatMessage.User.content → User.parts

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ChatMessage.kt:33-37` — `User.content: String` → `User.parts: List<ContentPart>`
- Test: `agent/core/src/test/kotlin/io/github/yeyi/agent/llm/ChatMessageTest.kt`
- Modify: 所有 caller（ReActAgent.kt:106、RoundsBoundedMemory.kt:86-89、各处 `ChatMessage.User("...")` 构造点）

**Interfaces:**
- Produces: `public data class User(public val parts: List<ContentPart>) : ChatMessage { init { require(parts.isNotEmpty()) }; override val role: Role = Role.User }`
- 依赖 Task 2 的 ContentPart

- [ ] **Step 1: 写 failing test**

`agent/core/src/test/kotlin/io/github/yeyi/agent/llm/ChatMessageTest.kt`（新建或追加）：

```kotlin
package io.github.yeyi.agent.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ChatMessageTest {
    private val json = Json

    @Test
    fun `User constructs with parts list`() {
        val msg = ChatMessage.User(listOf(ContentPart.Text("hi")))
        assertEquals(Role.User, msg.role)
    }

    @Test
    fun `User rejects empty parts`() {
        assertFailsWith<IllegalArgumentException> {
            ChatMessage.User(emptyList())
        }
    }

    @Test
    fun `User round-trips through JSON`() {
        val msg = ChatMessage.User(listOf(
            ContentPart.Text("look"),
            ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
        ))
        val s = json.encodeToString<ChatMessage>(msg)
        val back = json.decodeFromString<ChatMessage>(s)
        assertEquals(msg, back)
    }
}
```

- [ ] **Step 2: 跑测试，验证 FAIL**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.llm.ChatMessageTest"`
Expected: FAIL

- [ ] **Step 3: 改 ChatMessage.kt**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ChatMessage.kt`，把：

```kotlin
/** 用户消息。 */
@Serializable
public data class User(public val content: String) : ChatMessage {
    override val role: Role = Role.User
}
```

替换为：

```kotlin
/**
 * 用户消息,承载单条/多条内容块 (文本 + image/audio/video)。
 * 空 parts 等价于无消息,构造时拒。
 */
@Serializable
public data class User(public val parts: List<ContentPart>) : ChatMessage {
    init {
        require(parts.isNotEmpty()) { "ChatMessage.User.parts must not be empty" }
    }
    override val role: Role = Role.User
}
```

- [ ] **Step 4: 跑测试，验证 FAIL（编译错误：所有 caller 还未同步）**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.llm.ChatMessageTest"`
Expected: COMPILATION FAIL — callers using `User(content)` broken

- [ ] **Step 5: 同步所有 caller**

用 grep 找 codebase 所有 `ChatMessage.User(` 调用点：

```bash
grep -rn "ChatMessage\.User(" agent/ demos/ gateway/
```

每个调用点改造：
- `ChatMessage.User("text")` → `ChatMessage.User(listOf(ContentPart.Text("text")))`
- `ChatMessage.User(content)` 其中 `content: String` → 同上

**主要位置**（写 plan 时已知，需要 grep 确认完整）：
- `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt:106`
- `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt:129`
- 各测试文件中任何 `User(...)` 构造

**摘要路径暂时保留旧逻辑**（RoundsBoundedMemory.kt:86-89 当前用 `msg.content`）——这一步只改构造点，不动摘要。摘要迁移到 Task 8。

- [ ] **Step 6: 跑测试，验证 PASS**

Run: `./gradlew :agent:core:test`
Expected: ChatMessageTest 3 passed；但其他测试可能因 User 字段变化失败 —— 列出失败 caller，下一 task 逐个处理

- [ ] **Step 7: Commit**

```bash
git add -u agent/
git commit -m "$(cat <<'COMMIT_MSG'
feat(agent): ChatMessage.User.content 迁移为 parts

User 从单 content 字符串迁移为 List<ContentPart> 多块, 承载文本 +
image/audio/video。破坏性变更: 所有 caller (ReActAgent/RoundsBoundedMemory
/各测试) 同步更新。User 不挂 text 派生属性 (0 caller, 摘要路径 inline
处理)。type 层不挂 metadata。
COMMIT_MSG
)"
```

---

### Task 5: 新增 AgentQuery

**Files:**
- Create: `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentQuery.kt`
- Test: `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentQueryTest.kt`

**Interfaces:**
- Produces: `public data class AgentQuery(public val parts: List<ContentPart>) { init { require(parts.isNotEmpty()) }; companion object { public fun text(String) } }`
- 依赖 Task 2 的 ContentPart

- [ ] **Step 1: 写 failing test**

`agent/core/src/test/kotlin/io/github/yeyi/agent/AgentQueryTest.kt`:

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentQueryTest {
    private val json = Json

    @Test
    fun `text factory wraps string in Text part`() {
        val q = AgentQuery.text("hi")
        assertEquals(listOf(ContentPart.Text("hi")), q.parts)
    }

    @Test
    fun `rejects empty parts`() {
        assertFailsWith<IllegalArgumentException> {
            AgentQuery(emptyList())
        }
    }

    @Test
    fun `accepts multi-modal parts in order`() {
        val q = AgentQuery(listOf(
            ContentPart.Text("look"),
            ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
        ))
        assertEquals(2, q.parts.size)
        assertEquals(ContentPart.Kind.Text, q.parts[0].kind)
        assertEquals(ContentPart.Kind.Image, q.parts[1].kind)
    }

    @Test
    fun `round-trips through JSON`() {
        val q = AgentQuery(listOf(
            ContentPart.Text("look"),
            ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
        ))
        val s = json.encodeToString(q)
        val back = json.decodeFromString<AgentQuery>(s)
        assertEquals(q, back)
    }
}
```

- [ ] **Step 2: 跑测试，验证 FAIL**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.AgentQueryTest"`
Expected: FAIL

- [ ] **Step 3: 实现 AgentQuery.kt**

`agent/core/src/main/kotlin/io/github/yeyi/agent/AgentQuery.kt`:

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ContentPart
import kotlinx.serialization.Serializable

/**
 * Agent 入口的"用户回合"包装：把文本 + 多模态块以出现顺序一次性提交。
 *
 * 与 ChatMessage.User 不互替：前者是 Agent 层输入视角，后者是 LLM/Memory
 * 层消息视角；通过 ChatMessage.User(query.parts) 互转。
 *
 * 不预留 metadata：当前没有 traceId / sessionContext 等需求；将来要加时
 * 走外层扩展，不动 data class 字段，避免变成垃圾桶。
 */
@Serializable
public data class AgentQuery(public val parts: List<ContentPart>) {
    init {
        require(parts.isNotEmpty()) { "AgentQuery.parts must not be empty" }
    }

    public companion object {
        /** 纯文本便捷入口。 */
        public fun text(content: String): AgentQuery =
            AgentQuery(listOf(ContentPart.Text(content)))
    }
}
```

- [ ] **Step 4: 跑测试，验证 PASS**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.AgentQueryTest"`
Expected: 4 passed

- [ ] **Step 5: Commit**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/AgentQuery.kt \
        agent/core/src/test/kotlin/io/github/yeyi/agent/AgentQueryTest.kt
git commit -m "$(cat <<'COMMIT_MSG'
feat(agent): 新增 AgentQuery 包装 + text() 便捷工厂

Agent 入口的"用户回合": 文本 + 多模态块按出现顺序提交。仅留 text()
factory (自动包 ContentPart.Text); of() 等价主构造器的 factory 已
删除 (YAGNI)。init 拒绝空 parts。
COMMIT_MSG
)"
```

---

### Task 6: 迁移 Agent.run / runStream 入参

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/Agent.kt:14-34` — `run/runStream(input: String)` → `(query: AgentQuery)`
- Test: `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentInterfaceTest.kt`（新建）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt:35-39` — 实现签名同步

**Interfaces:**
- Produces: `public interface Agent { fun run(query: AgentQuery): Flow<AgentEvent>; fun runStream(query: AgentQuery): Flow<AgentEvent> }`
- 依赖 Task 5 的 AgentQuery

- [ ] **Step 1: 写 failing test**

`agent/core/src/test/kotlin/io/github/yeyi/agent/AgentInterfaceTest.kt`:

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentInterfaceTest {
    @Test
    fun `Agent run accepts AgentQuery parameter`() = runTest {
        // 通过 ReActAgent 间接验证接口签名: run 接受 AgentQuery 而不是 String。
        // 这里只验证编译期 + AgentQuery.text() 入口能跑通。
        val provider = FakeLlmProvider(textResponse = "hello back")
        // ReActAgent 构造细节不在此测试范围; 仅确认 Agent 接口接受 AgentQuery。
        val query: AgentQuery = AgentQuery.text("hi")
        assertEquals(listOf(io.github.yeyi.agent.llm.ContentPart.Text("hi")), query.parts)
    }
}
```

注: Agent.kt 改完后这个测试自然编译通过；测试主体只做编译期验证。

- [ ] **Step 2: 跑测试，验证当前编译失败**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.AgentInterfaceTest"`
Expected: FAIL — `Agent` 接口仍是 `run(String)`，但 FakeLlmProvider 没声明，但 Kotlin 会先报告 Agent 接口 signature mismatch（如果你在测试里调用 `agent.run(query)`）。

注：本测试不直接调 `agent.run(...)`（避免引入 ReActAgent 依赖细节）；只验证编译期 `AgentQuery` 类型存在。

- [ ] **Step 3: 改 Agent.kt**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/Agent.kt`，把：

```kotlin
public fun run(input: String): Flow<AgentEvent>
```

改为：

```kotlin
public fun run(query: AgentQuery): Flow<AgentEvent>
```

同理改 `runStream`：

```kotlin
public fun runStream(query: AgentQuery): Flow<AgentEvent>
```

更新 KDoc：

```kotlin
/**
 * 批式（非流式）执行路径。
 *
 * 内部使用 Memory 维护对话历史，调用 LlmProvider.chat 单次 RTT。
 * 入参 [AgentQuery] 承载文本 + 多模态块。
 *
 * 适用场景：响应速度优先、无需流式输出。
 */
public fun run(query: AgentQuery): Flow<AgentEvent>

/**
 * 流式执行路径。
 *
 * 内部使用 Memory 维护对话历史，调用 LlmProvider.chatStream 推送
 * AgentEvent.TextDelta 增量文本。
 * 入参 [AgentQuery] 承载文本 + 多模态块。
 *
 * 适用场景：需要实时展示 LLM 输出文字、工具调用进度等。
 */
public fun runStream(query: AgentQuery): Flow<AgentEvent>
```

- [ ] **Step 4: 改 ReActAgent.kt 实现 + 同步 AgentEvent.Initial.agentQuery**

**4a. 改 ReActAgent.kt:**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt`：

```kotlin
// Line 35
override fun run(query: AgentQuery): Flow<AgentEvent> = flow {
    loop(query, { req -> llmProvider.chat(req) }, { emit(it) })
}

// Line 39
override fun runStream(query: AgentQuery): Flow<AgentEvent> = flow {
    // ... body 用 query 替代 input ...
}
```

`loop(input: String, ...)` 改名为 `loop(query: AgentQuery, ...)`，body 内 emit 调用 `AgentEvent.Initial(query)`。

**4b. 同步改 AgentEvent.Initial.agentQuery（合并原 Task 7）:**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentEvent.kt:27`：

```kotlin
// 旧
public data class Initial(public val userInput: String) : AgentEvent
// 新
public data class Initial(public val agentQuery: AgentQuery) : AgentEvent
```

注: 把 Initial.agentQuery 改名放在本 task 一并完成，避免分两个 task 留中间编译失败状态。原 plan 的独立 Task 7 合并到此。

- [ ] **Step 5: 跑测试，验证编译通过**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.AgentInterfaceTest"`
Expected: PASS

- [ ] **Step 6: 跑全套 agent/core 测试看破坏面**

Run: `./gradlew :agent:core:test`
Expected: 大批测试因 `run(String)` / `User(String)` 变更失败 —— 列出失败点，下个 task 处理

- [ ] **Step 7: Commit**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/Agent.kt \
        agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt \
        agent/core/src/test/kotlin/io/github/yeyi/agent/AgentInterfaceTest.kt
git commit -m "$(cat <<'COMMIT_MSG'
feat(agent): Agent.run/runStream 入参迁移到 AgentQuery

删除 run(input: String)/runStream(input: String) 重载,直接接受 AgentQuery。
ReActAgent loop 签名同步 (input: String -> query: AgentQuery)。破坏性变更,
后续 task 逐模块同步 caller。
COMMIT_MSG
)"
```

---

### Task 7: (已合并到 Task 6 Step 4b)

原 plan 中 Task 7 (改 AgentEvent.Initial.userInput → agentQuery) 已合并到 Task 6 Step 4b，避免分两个 task 留中间编译失败状态。后续 task 编号保持原序。

---

### Task 8: RoundsBoundedMemory 摘要路径按 parts 处理

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt:84-90`
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt:127-130`（rebuild 路径）
- Test: `agent/core/src/test/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemorySummaryTest.kt`

**Interfaces:**
- Produces: 摘要路径对 User 多模态 parts 输出"文本直取 + 多模态压占位"
- `MediaSource.shortLabel()` extension

- [ ] **Step 1: 写 failing test**

`agent/core/src/test/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemorySummaryTest.kt`:

```kotlin
package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RoundsBoundedMemorySummaryTest {
    @Test
    fun `text-only User summarises to its text`() = runTest {
        val mem = RoundsBoundedMemory(InMemoryMemory(), maxRounds = 1, llmProvider = stubProvider())
        mem.add(ChatMessage.User(listOf(ContentPart.Text("hello world"))))
        // 触发压缩:再添一轮让 maxRounds=1 触发
        mem.add(ChatMessage.Assistant("reply"))
        mem.add(ChatMessage.User(listOf(ContentPart.Text("second turn"))))
        // history 应包含摘要 System 消息,文本应来自 User parts
        val hist = mem.history()
        val summary = hist.firstOrNull { it is ChatMessage.System } as? ChatMessage.System
        assertTrue(summary != null, "summary should exist")
        assertTrue("hello world" in summary.content)
    }

    @Test
    fun `multimodal User summarises text parts and placeholder for media`() = runTest {
        val mem = RoundsBoundedMemory(InMemoryMemory(), maxRounds = 1, llmProvider = stubProvider())
        mem.add(ChatMessage.User(listOf(
            ContentPart.Text("see this:"),
            ContentPart.Image(MediaSource.Http("https://x.com/cat.jpg"))
        )))
        mem.add(ChatMessage.Assistant("ok"))
        mem.add(ChatMessage.User(listOf(ContentPart.Text("next"))))
        val summary = mem.history().firstOrNull { it is ChatMessage.System } as? ChatMessage.System
        assertTrue(summary != null)
        assertTrue("see this:" in summary.content)
        assertTrue("[image:" in summary.content)
        assertTrue("cat.jpg" in summary.content)
    }

    private fun stubProvider(): io.github.yeyi.agent.llm.LlmProvider =
        throw NotImplementedError("not used in summary test path")
}
```

注: 摘要是否触发 LLM 取决于现有 `RoundsBoundedMemory.compressOldRounds` 的实现 —— 写测试时跑出真实压缩行为，必要时调整 maxRounds / 测试顺序使压缩被触发。如果 LLM provider 在压缩路径必须 stub，看现有 FakeLlmProvider 用法。

- [ ] **Step 2: 跑测试，验证当前摘要丢失多模态信息**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.memory.RoundsBoundedMemorySummaryTest"`
Expected: FAIL（现有 `msg.content` 编译报错 —— 因为 Task 4 已经把 User.content 删了）

- [ ] **Step 3: 改 RoundsBoundedMemory.kt 摘要路径**

修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt`，把：

```kotlin
is ChatMessage.User -> msg.content
```

改为：

```kotlin
is ChatMessage.User -> msg.parts.joinToString("\n") { part ->
    when (part) {
        is ContentPart.Text -> part.text
        is ContentPart.Image -> "[image:${part.source.shortLabel()}]"
        is ContentPart.Audio -> "[audio:${part.source.shortLabel()}]"
        is ContentPart.Video -> "[video:${part.source.shortLabel()}]"
    }
}
```

**rebuild 路径**（同文件 ~line 127-130）：当前用 `ChatMessage.User(content)` 构造旧 user —— 改为：

```kotlin
ChatMessage.User(listOf(ContentPart.Text(content)))
```

并加 import `io.github.yeyi.agent.llm.ContentPart`。

- [ ] **Step 4: 加 MediaSource.shortLabel() extension**

新文件 `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/MediaSourceExtensions.kt`：

```kotlin
package io.github.yeyi.agent.llm

/** 摘要路径用的紧凑标签: 避免 URL/base64 过长膨胀摘要。 */
internal fun MediaSource.shortLabel(): String = when (this) {
    is MediaSource.Http -> url.take(64)
    is MediaSource.Data -> "$mimeType, ${base64.length / 1024}KB"
    is MediaSource.FileId -> id
}
```

- [ ] **Step 5: 跑测试，验证 PASS**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.memory.RoundsBoundedMemorySummaryTest"`
Expected: 2 passed

- [ ] **Step 6: 跑全套 agent/core 测试**

Run: `./gradlew :agent:core:test`
Expected: PASS（核心模块全部测试通过）

- [ ] **Step 7: Commit**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt \
        agent/core/src/main/kotlin/io/github/yeyi/agent/llm/MediaSourceExtensions.kt \
        agent/core/src/test/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemorySummaryTest.kt
git commit -m "$(cat <<'COMMIT_MSG'
feat(agent): RoundsBoundedMemory 摘要按 ContentPart 多模态处理

User parts 文本直取, image/audio/video 压成 [image:...] 等占位; 加
MediaSource.shortLabel() extension 控长 (URL 截 64, base64 报 KB)。
保留"user 含附件"语义, 便于 LLM 后续轮次知道上下文。
COMMIT_MSG
)"
```

---

### Task 9: OpenAI DTO 多态扩展

**Files:**
- Modify: `agent/providers/openai/src/main/kotlin/io/github/yeyi/agent/providers/openai/OpenAiDtos.kt:25-31` — `OpenAiMessage.content: String?` → 多态 `OpenAiContent`
- Modify: `agent/providers/openai/src/main/kotlin/io/github/yeyi/agent/providers/openai/OpenAiDtos.kt` — 新增 `OpenAiContentPart` sealed + `OpenAiContent` sealed

**Interfaces:**
- Produces:
  - `public sealed class OpenAiContent { data class StringValue(value); data class PartsValue(value: List<OpenAiContentPart>) }`
  - `public sealed class OpenAiContentPart { @SerialName("text") Text(text); @SerialName("image_url") ImageUrl(url, detail?); @SerialName("input_audio") InputAudio(data, format) }`

- [ ] **Step 1: 加 OpenAiContentPart + OpenAiContent sealed**

在 `agent/providers/openai/src/main/kotlin/io/github/yeyi/agent/providers/openai/OpenAiDtos.kt` 末尾追加：

```kotlin
@Serializable
internal sealed class OpenAiContentPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : OpenAiContentPart()

    @Serializable
    @SerialName("image_url")
    data class ImageUrl(
        val url: String,
        @SerialName("detail") val detail: String? = null
    ) : OpenAiContentPart()

    @Serializable
    @SerialName("input_audio")
    data class InputAudio(
        val data: String,
        val format: String
    ) : OpenAiContentPart()
}

@Serializable
internal sealed class OpenAiContent {
    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : OpenAiContent()

    @Serializable
    @SerialName("parts")
    data class PartsValue(val value: List<OpenAiContentPart>) : OpenAiContent()
}
```

- [ ] **Step 2: 改 OpenAiMessage.content 类型**

修改 `OpenAiDtos.kt:25-31`：

```kotlin
// 旧
@Serializable
internal data class OpenAiMessage(
    val role: String,
    val content: String? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)
// 新
@Serializable
internal data class OpenAiMessage(
    val role: String,
    val content: OpenAiContent? = null,
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)
```

- [ ] **Step 3: 跑测试，验证现有 OpenAi mapping 测试 FAIL**

Run: `./gradlew :agent:providers:openai:test`
Expected: FAIL（mapToOpenAi 现在传 String 给 content: OpenAiContent? 类型不匹配）

- [ ] **Step 4: Commit（本 task 只动 DTO + 故意破坏现有 mapping 测试，下一 task 修）**

```bash
git add agent/providers/openai/src/main/kotlin/io/github/yeyi/agent/providers/openai/OpenAiDtos.kt
git commit -m "$(cat <<'COMMIT_MSG'
feat(providers/openai): DTO 加 OpenAiContent/OpenAiContentPart 多态

OpenAiMessage.content 从 String? 扩展为 OpenAiContent? (StringValue |
PartsValue); OpenAiContentPart 三变体 Text/ImageUrl/InputAudio 严格对齐
OpenAI wire format。DTO 改动会让现有 mapToOpenAi 编译失败 - 下个 task
修复 mapping 层。
COMMIT_MSG
)"
```

---

### Task 10: OpenAI mapToOpenAi 多模态分支

**Files:**
- Modify: `agent/providers/openai/src/main/kotlin/io/github/yeyi/agent/providers/openai/OpenAiMapping.kt:17-60`
- Test: `agent/providers/openai/src/test/kotlin/io/github/yeyi/agent/providers/openai/OpenAiMappingMultimodalTest.kt`

**Interfaces:**
- Produces: `mapToOpenAi` 在 `is ChatMessage.User` 分支按 ContentPart 分流；Video 抛 UnsupportedContent；FileId for image/audio 抛 UnsupportedContent；Http for audio 抛 UnsupportedContent

- [ ] **Step 1: 写 failing test**

`agent/providers/openai/src/test/kotlin/io/github/yeyi/agent/providers/openai/OpenAiMappingMultimodalTest.kt`:

```kotlin
package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiMappingMultimodalTest {
    @Test
    fun `text-only User uses StringValue`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Text("hi")))
        ))
        val mapped = mapToOpenAi("gpt-4o", req, stream = false)
        val msg = mapped.messages.last()
        assertTrue(msg.content is OpenAiContent.StringValue)
        assertEquals("hi", (msg.content as OpenAiContent.StringValue).value)
    }

    @Test
    fun `Image with Http source becomes image_url`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(
                ContentPart.Text("see"),
                ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
            ))
        ))
        val mapped = mapToOpenAi("gpt-4o", req, stream = false)
        val content = mapped.messages.last().content as OpenAiContent.PartsValue
        assertEquals(2, content.value.size)
        val imagePart = content.value[1] as OpenAiContentPart.ImageUrl
        assertEquals("https://x.com/a.jpg", imagePart.url)
    }

    @Test
    fun `Image with Data source becomes data URI`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/png", "XYZ"))))
        ))
        val mapped = mapToOpenAi("gpt-4o", req, stream = false)
        val imagePart = (mapped.messages.last().content as OpenAiContent.PartsValue)
            .value[0] as OpenAiContentPart.ImageUrl
        assertEquals("data:image/png;base64,XYZ", imagePart.url)
    }

    @Test
    fun `Audio with Data source becomes input_audio with parsed format`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Audio(MediaSource.Data("audio/wav", "AAAA"))))
        ))
        val mapped = mapToOpenAi("gpt-4o", req, stream = false)
        val audio = (mapped.messages.last().content as OpenAiContent.PartsValue)
            .value[0] as OpenAiContentPart.InputAudio
        assertEquals("AAAA", audio.data)
        assertEquals("wav", audio.format)
    }

    @Test
    fun `Video throws UnsupportedContent`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Video(MediaSource.Http("https://x.com/v.mp4"))))
        ))
        assertFailsWith<AgentException.UnsupportedContent> {
            mapToOpenAi("gpt-4o", req, stream = false)
        }
    }

    @Test
    fun `Image with FileId throws UnsupportedContent`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.FileId("file-1"))))
        ))
        assertFailsWith<AgentException.UnsupportedContent> {
            mapToOpenAi("gpt-4o", req, stream = false)
        }
    }

    @Test
    fun `Audio with Http throws UnsupportedContent`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Audio(MediaSource.Http("https://x.com/a.mp3"))))
        ))
        assertFailsWith<AgentException.UnsupportedContent> {
            mapToOpenAi("gpt-4o", req, stream = false)
        }
    }
}
```

- [ ] **Step 2: 跑测试，验证 FAIL**

Run: `./gradlew :agent:providers:openai:test --tests "io.github.yeyi.agent.providers.openai.OpenAiMappingMultimodalTest"`
Expected: FAIL（mapToOpenAi 未实现多模态）

- [ ] **Step 3: 改 mapToOpenAi**

修改 `agent/providers/openai/src/main/kotlin/io/github/yeyi/agent/providers/openai/OpenAiMapping.kt:17-60`：

```kotlin
internal fun mapToOpenAi(model: String, request: ChatRequest, stream: Boolean): OpenAiChatRequest {
    val messages = request.messages.map { msg ->
        when (msg) {
            is ChatMessage.System -> OpenAiMessage(role = "system", content = OpenAiContent.StringValue(msg.content))
            is ChatMessage.User -> mapUserToOpenAi(msg)
            is ChatMessage.Assistant -> OpenAiMessage(
                role = "assistant",
                content = OpenAiContent.StringValue(msg.content ?: ""),
                toolCalls = if (msg.toolCalls.isEmpty()) null else msg.toolCalls.map { tc ->
                    OpenAiToolCall(
                        id = tc.id,
                        function = OpenAiFunctionCall(
                            name = tc.name,
                            arguments = Mapper.encodeToString(tc.arguments)
                        )
                    )
                }
            )
            is ChatMessage.ToolResult -> OpenAiMessage(
                role = "tool",
                content = OpenAiContent.StringValue(msg.content),
                toolCallId = msg.toolCallId,
                name = msg.toolName
            )
        }
    }
    // ... tools / rest 不变 ...
}

private fun mapUserToOpenAi(msg: ChatMessage.User): OpenAiMessage {
    val parts: List<OpenAiContentPart> = msg.parts.map { part ->
        when (part) {
            is ContentPart.Text -> OpenAiContentPart.Text(part.text)
            is ContentPart.Image -> mapImageToOpenAi(part.source)
            is ContentPart.Audio -> mapAudioToOpenAi(part.source)
            is ContentPart.Video -> throw AgentException.UnsupportedContent(
                "OpenAI Chat Completions does not support video input; " +
                "use a vision-only model via Responses API instead"
            )
        }
    }
    val content: OpenAiContent = if (parts.size == 1 && parts[0] is OpenAiContentPart.Text) {
        OpenAiContent.StringValue((parts[0] as OpenAiContentPart.Text).text)
    } else {
        OpenAiContent.PartsValue(parts)
    }
    return OpenAiMessage(role = "user", content = content)
}

private fun mapImageToOpenAi(source: MediaSource): OpenAiContentPart.ImageUrl = when (source) {
    is MediaSource.Http -> OpenAiContentPart.ImageUrl(source.url)
    is MediaSource.Data -> OpenAiContentPart.ImageUrl(
        url = "data:${source.mimeType};base64,${source.base64}"
    )
    is MediaSource.FileId -> throw AgentException.UnsupportedContent(
        "OpenAI Chat Completions image_url does not support file_id; " +
        "use Responses API or upload first and inline as Data"
    )
}

private fun mapAudioToOpenAi(source: MediaSource): OpenAiContentPart.InputAudio = when (source) {
    is MediaSource.Data -> {
        val format = source.mimeType.substringAfter("/").takeIf { it.isNotEmpty() } ?: "wav"
        OpenAiContentPart.InputAudio(data = source.base64, format = format)
    }
    is MediaSource.Http -> throw AgentException.UnsupportedContent(
        "OpenAI input_audio requires inline base64; use Data source for audio"
    )
    is MediaSource.FileId -> throw AgentException.UnsupportedContent(
        "OpenAI input_audio does not support file_id in Chat Completions"
    )
}
```

- [ ] **Step 4: 跑测试，验证 PASS**

Run: `./gradlew :agent:providers:openai:test --tests "io.github.yeyi.agent.providers.openai.OpenAiMappingMultimodalTest"`
Expected: 7 passed

- [ ] **Step 5: 跑 OpenAI 全套测试**

Run: `./gradlew :agent:providers:openai:test`
Expected: 既有测试也通过（mapFromOpenAi 不变）

- [ ] **Step 6: Commit**

```bash
git add agent/providers/openai/src/main/kotlin/io/github/yeyi/agent/providers/openai/OpenAiMapping.kt \
        agent/providers/openai/src/test/kotlin/io/github/yeyi/agent/providers/openai/OpenAiMappingMultimodalTest.kt
git commit -m "$(cat <<'COMMIT_MSG'
feat(providers/openai): mapToOpenAi 支持多模态 ContentPart

User parts 按 kind 分流到 OpenAiContentPart (text/image_url/input_audio)。
Video 一律 throw UnsupportedContent (OpenAI Chat Completions 当前不接)。
FileId for image/audio / Http for audio 同样 throw, 错误信息指引替代方案。
单 Text part 优化为 StringValue (旧 wire 兼容); 多 part 走 PartsValue。
COMMIT_MSG
)"
```

---

### Task 11: Anthropic DTO 扩 Image / Audio / Video block

**Files:**
- Modify: `agent/providers/anthropic/src/main/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicDtos.kt:26-46`

**Interfaces:**
- Produces: `AnthropicContentBlock` 新增 Image / Audio / Video 三个 sealed 子类；Image 内嵌 `Source` sealed (Base64/Url/File)

- [ ] **Step 1: 扩 AnthropicContentBlock**

修改 `agent/providers/anthropic/src/main/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicDtos.kt`，在现有 `Text` / `ToolUse` / `ToolResult` 之后追加：

```kotlin
    @Serializable
    @SerialName("image")
    data class Image(val source: Source) : AnthropicContentBlock() {

        @Serializable
        sealed class Source

        @Serializable
        @SerialName("base64")
        data class Base64Source(
            @SerialName("media_type") val mediaType: String,
            val data: String
        ) : Source()

        @Serializable
        @SerialName("url")
        data class UrlSource(val url: String) : Source()

        @Serializable
        @SerialName("file")
        data class FileSource(@SerialName("file_id") val fileId: String) : Source()
    }

    @Serializable
    @SerialName("audio")
    data class Audio(val source: Image.Source) : AnthropicContentBlock()

    @Serializable
    @SerialName("video")
    data class Video(val source: Image.Source) : AnthropicContentBlock()
```

- [ ] **Step 2: 跑测试，看现有 Anthropic 测试是否被影响**

Run: `./gradlew :agent:providers:anthropic:test`
Expected: FAIL（mapToAnthropic 仍是旧分支，未支持 Image/Audio/Video，下一 task 修）

- [ ] **Step 3: Commit（本 task 只动 DTO）**

```bash
git add agent/providers/anthropic/src/main/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicDtos.kt
git commit -m "$(cat <<'COMMIT_MSG'
feat(providers/anthropic): DTO 加 Image/Audio/Video block

AnthropicContentBlock 新增三种媒体 block; Image 内嵌 Source sealed
(Base64/Url/File), Audio/Video 复用 Image.Source。Anthropic 的三种
媒体在 wire format 上确实是同 source 形态。下一 task 修 mapping。
COMMIT_MSG
)"
```

---

### Task 12: Anthropic mapToAnthropic 多模态分支

**Files:**
- Modify: `agent/providers/anthropic/src/main/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicMapping.kt:24-29`
- Test: `agent/providers/anthropic/src/test/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicMappingMultimodalTest.kt`

**Interfaces:**
- Produces: mapToAnthropic 在 User 分支按 ContentPart 分流；Video + Data 抛 UnsupportedContent

- [ ] **Step 1: 写 failing test**

`agent/providers/anthropic/src/test/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicMappingMultimodalTest.kt`:

```kotlin
package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnthropicMappingMultimodalTest {
    @Test
    fun `Image with Http source becomes image url block`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))))
        ))
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        val blocks = mapped.messages.last().content
        assertEquals(1, blocks.size)
        val img = blocks[0] as AnthropicContentBlock.Image
        assertTrue(img.source is AnthropicContentBlock.Image.UrlSource)
        assertEquals("https://x.com/a.jpg", (img.source as AnthropicContentBlock.Image.UrlSource).url)
    }

    @Test
    fun `Image with Data source becomes image base64 block`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/png", "XYZ"))))
        ))
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        val src = (mapped.messages.last().content[0] as AnthropicContentBlock.Image).source
                as AnthropicContentBlock.Image.Base64Source
        assertEquals("image/png", src.mediaType)
        assertEquals("XYZ", src.data)
    }

    @Test
    fun `Video with Http becomes video url block`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Video(MediaSource.Http("https://x.com/v.mp4"))))
        ))
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        assertTrue(mapped.messages.last().content[0] is AnthropicContentBlock.Video)
    }

    @Test
    fun `Video with Data throws UnsupportedContent`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Video(MediaSource.Data("video/mp4", "BIN"))))
        ))
        assertFailsWith<AgentException.UnsupportedContent> {
            mapToAnthropic("claude-sonnet-4-6", req)
        }
    }

    @Test
    fun `Audio with Data becomes audio base64 block`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Audio(MediaSource.Data("audio/mp3", "MP3DATA"))))
        ))
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        assertTrue(mapped.messages.last().content[0] is AnthropicContentBlock.Audio)
    }

    @Test
    fun `text + image parts preserve order`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(
                ContentPart.Text("see"),
                ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
            ))
        ))
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        val blocks = mapped.messages.last().content
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is AnthropicContentBlock.Text)
        assertTrue(blocks[1] is AnthropicContentBlock.Image)
    }
}
```

- [ ] **Step 2: 跑测试，验证 FAIL**

Run: `./gradlew :agent:providers:anthropic:test --tests "io.github.yeyi.agent.providers.anthropic.AnthropicMappingMultimodalTest"`
Expected: FAIL（mapToAnthropic 还没改）

- [ ] **Step 3: 改 mapToAnthropic**

修改 `agent/providers/anthropic/src/main/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicMapping.kt:24-29`：

```kotlin
is ChatMessage.User -> {
    val blocks = msg.parts.map { part ->
        when (part) {
            is ContentPart.Text -> AnthropicContentBlock.Text(part.text)
            is ContentPart.Image -> AnthropicContentBlock.Image(mapImageToAnthropic(part.source))
            is ContentPart.Audio -> AnthropicContentBlock.Audio(mapImageToAnthropic(part.source))
            is ContentPart.Video -> AnthropicContentBlock.Video(mapVideoToAnthropic(part.source))
        }
    }
    messages.add(AnthropicMessage(role = "user", content = blocks))
}
```

并在文件末尾（companion area 之外）加 helpers：

```kotlin
private fun mapImageToAnthropic(source: MediaSource): AnthropicContentBlock.Image.Source = when (source) {
    is MediaSource.Http -> AnthropicContentBlock.Image.UrlSource(source.url)
    is MediaSource.Data -> AnthropicContentBlock.Image.Base64Source(
        mediaType = source.mimeType,
        data = source.base64
    )
    is MediaSource.FileId -> AnthropicContentBlock.Image.FileSource(source.id)
}

private fun mapVideoToAnthropic(source: MediaSource): AnthropicContentBlock.Image.Source = when (source) {
    is MediaSource.Http -> AnthropicContentBlock.Image.UrlSource(source.url)
    is MediaSource.FileId -> AnthropicContentBlock.Image.FileSource(source.id)
    is MediaSource.Data -> throw AgentException.UnsupportedContent(
        "Anthropic does not support video base64 inline; use Http or FileId"
    )
}
```

并加 import：`io.github.yeyi.agent.AgentException`、`io.github.yeyi.agent.llm.ContentPart`、`io.github.yeyi.agent.llm.MediaSource`。

- [ ] **Step 4: 跑测试，验证 PASS**

Run: `./gradlew :agent:providers:anthropic:test --tests "io.github.yeyi.agent.providers.anthropic.AnthropicMappingMultimodalTest"`
Expected: 6 passed

- [ ] **Step 5: 跑 Anthropic 全套测试**

Run: `./gradlew :agent:providers:anthropic:test`
Expected: PASS

- [ ] **Step 6: Commit**

```bash
git add agent/providers/anthropic/src/main/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicMapping.kt \
        agent/providers/anthropic/src/test/kotlin/io/github/yeyi/agent/providers/anthropic/AnthropicMappingMultimodalTest.kt
git commit -m "$(cat <<'COMMIT_MSG'
feat(providers/anthropic): mapToAnthropic 支持多模态 ContentPart

User parts 按 kind 分流到 AnthropicContentBlock (text/image/audio/video)。
Video+Data 抛 UnsupportedContent (Anthropic 当前不接 base64 video);
Video+Http/Video+FileId 走 Url/FileSource。Audio 复用 Image.Source
形态 (Anthropic wire 一致)。
COMMIT_MSG
)"
```

---

### Task 13: 端到端 ReActAgent 集成测试

**Files:**
- Test: `agent/core/src/test/kotlin/io/github/yeyi/agent/ReActAgentMultimodalTest.kt`

**Interfaces:**
- 验证：`run(AgentQuery)` 通过 FakeLlmProvider 接收 multipart content；`run` 抛出 UnsupportedContent 时 emit Failed

- [ ] **Step 1: 写测试**

`agent/core/src/test/kotlin/io/github/yeyi/agent/ReActAgentMultimodalTest.kt`:

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class ReActAgentMultimodalTest {

    private fun makeAgent(provider: FakeLlmProvider): Agent = ReActAgent(
        persona = Persona("you are helpful"),
        llmProvider = provider,
        toolRegistry = ToolRegistry(),
        memory = InMemoryMemory(),
        maxRounds = 20,
        maxIterations = 5
    )

    @Test
    fun `run with AgentQuery text equivalent to old String run`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    message = ChatMessage.Assistant(content = "hello back"),
                    finishReason = FinishReason.Stop
                )
            )
        )
        val agent = makeAgent(provider)
        val finalEvent = agent.run(AgentQuery.text("hi")).toList()
            .filterIsInstance<AgentEvent.Final>().first()
        assertEquals("hello back", finalEvent.result.message.content)
    }

    @Test
    fun `Initial event carries AgentQuery`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    message = ChatMessage.Assistant(content = "ok"),
                    finishReason = FinishReason.Stop
                )
            )
        )
        val agent = makeAgent(provider)
        val events = agent.run(AgentQuery.text("hi")).toList()
        val initial = events.filterIsInstance<AgentEvent.Initial>().first()
        assertEquals(AgentQuery.text("hi"), initial.agentQuery)
    }
}
```

注：实际 API 校正（pre-flight review）：
- `Persona("...")` 主构造，无 `Persona.of(...)` factory
- `FakeLlmProvider` 用 `nonStreamResponses: List<ChatResponse>`（无 `textResponse` 单参）
- `ToolRegistry()` 空注册；`NoOpTool` 不存在
- `ChatMessage.Assistant(content)` + `FinishReason.Stop` + `ChatResponse(message, finishReason)` 完整构造

- [ ] **Step 2: 跑测试，验证 PASS**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.ReActAgentMultimodalTest"`
Expected: 2 passed

- [ ] **Step 3: Commit**

```bash
git add agent/core/src/test/kotlin/io/github/yeyi/agent/ReActAgentMultimodalTest.kt
git commit -m "$(cat <<'COMMIT_MSG'
test(agent): ReActAgent.run(AgentQuery) 端到端集成测试

验证 text() 便捷入口等价旧 String 行为; Initial event 携带 AgentQuery
供 caller 上游消费。
COMMIT_MSG
)"
```

---

### Task 14: 同步所有调用点 (其他模块 + demos + gateway)

**Files:**
- Modify: `agent/subagent/...` (测试 + 源码)
- Modify: `agent/team/...`
- Modify: `agent/skill/...`
- Modify: `agent/capability/...`
- Modify: `agent/toolset/...`
- Modify: `agent/mcp/...`
- Modify: `agent/tool/compression/...`
- Modify: `agent/tool/serialization/...`
- Modify: `agent/hook/...`
- Modify: `demos/agent/...`
- Modify: `demos/team/...`
- Modify: `gateway/app/...`、`gateway/jvm/...`、`gateway/core/...`

- [ ] **Step 1: 跑全套 build，列出失败 caller**

Run: `./gradlew build -x test`
Expected: COMPILATION FAIL — 列出所有 `run(String)` / `User(String)` / `userInput` 引用点

- [ ] **Step 2: 逐模块同步 caller**

按失败顺序处理（每个模块一个 commit）：

```bash
# agent/subagent
git add agent/subagent/
git commit -m "refactor(subagent): ChatMessage.User/AgentQuery 同步迁移"

# agent/team
git add agent/team/
git commit -m "refactor(team): ChatMessage.User/AgentQuery 同步迁移"

# agent/skill
git add agent/skill/
git commit -m "refactor(skill): ChatMessage.User/AgentQuery 同步迁移"

# agent/capability
git add agent/capability/
git commit -m "refactor(capability): ChatMessage.User/AgentQuery 同步迁移"

# agent/toolset
git add agent/toolset/
git commit -m "refactor(toolset): ChatMessage.User/AgentQuery 同步迁移"

# agent/mcp
git add agent/mcp/
git commit -m "refactor(mcp): ChatMessage.User/AgentQuery 同步迁移"

# agent/tool/compression + agent/tool/serialization (Tool/ToolRegistry 在 agent/core)
git add agent/tool/compression/ agent/tool/serialization/
git commit -m "refactor(tool): ChatMessage.User/AgentQuery 同步迁移"

# agent/hook
git add agent/hook/
git commit -m "refactor(hook): ChatMessage.User/AgentQuery 同步迁移"
```

同步规则：
- `agent.run("text")` / `agent.runStream("text")` → `agent.run(AgentQuery.text("text"))` / `agent.runStream(AgentQuery.text("text"))`
- `agent.run(query)` 保持不变
- `ChatMessage.User("text")` → `ChatMessage.User(listOf(ContentPart.Text("text")))`
- 读 `Initial.userInput` 的代码 → `Initial.agentQuery`

- [ ] **Step 3: demos + gateway 同步**

```bash
git add demos/
git commit -m "refactor(demos): ChatMessage.User/AgentQuery 同步迁移"

git add gateway/
git commit -m "refactor(gateway): ChatMessage.User/AgentQuery 同步迁移"
```

- [ ] **Step 4: 跑全套编译，验证零失败**

Run: `./gradlew compileKotlin compileTestKotlin`
Expected: SUCCESS

- [ ] **Step 5: Commit 任何剩余修复**

```bash
git add -u
git commit -m "chore(*): 编译失败点 final pass 修复"
```

---

### Task 15: 全套测试通过 + Gradle build

**Files:** （无源码修改，只跑测试）

- [ ] **Step 1: 跑全套 unit + integration 测试**

Run: `./gradlew test`
Expected: PASS（除非发现 Task 13/14 漏改的边界）

- [ ] **Step 2: 跑 Gradle build**

Run: `./gradlew build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 修复任何剩余测试失败**

按失败点定位 → 修代码或测试 → commit（每个修复一个 commit）

- [ ] **Step 4: 最终 commit（如果有修复）**

```bash
git add -u
git commit -m "fix(*): 端到端 build pass 修复"
```

---

### Task 16: 提交 plan 完成总结到 PR description 草稿

**Files:**
- Create: `docs/superpowers/plans/2026-08-12-agent-multimodal-input-summary.md`（可选，给 reviewer 一页纸）

- [ ] **Step 1: 写一页纸总结**

```markdown
# Agent 多模态输入 — 实施完成总结

## 改了什么

- 新增 `MediaSource` (Http/Data/FileId) / `ContentPart` (Text/Image/Audio/Video) / `AgentQuery` 三层类型
- `Agent.run / runStream` 入参从 `String` → `AgentQuery`（删除重载）
- `ChatMessage.User.content` → `parts`；`AgentEvent.Initial.userInput` → `agentQuery`
- OpenAI / Anthropic provider mapping 按 ContentPart 分流；Video+Data 在 provider 层抛 `UnsupportedContent`
- `RoundsBoundedMemory` 摘要路径按多模态 parts 处理（文本直取 + 多模态压占位）
- 加 `AgentException.UnsupportedContent`

## 没改什么

- `LlmProvider` / `Memory` / `AgentBuilder` / `AgentContext` / `AgentHook` 契约不动
- 不引入 `LocalPath` / `AgentQuery` metadata / Assistant 多模态输出
- 不在 type 层禁止 Video+Data（保留扩展性）

## 验证

- `./gradlew build` 通过
- 新增测试：`MediaSourceTest` / `ContentPartTest` / `AgentQueryTest` / `OpenAiMappingMultimodalTest` / `AnthropicMappingMultimodalTest` / `RoundsBoundedMemorySummaryTest` / `ReActAgentMultimodalTest`
- 现有调用点同步: agent/{subagent,team,skill,capability,toolset,mcp,hook,tool/} + demos + gateway

## 风险与缓解

- 破坏性 API 变更: 所有 caller 同步更新（Task 14）
- OpenAI Chat Completions 视频不支持: 清晰错误信息 + 替代方案指引
- 摘要丢失图片细节: 占位保留"含附件"语义，未来独立提案
```

- [ ] **Step 2: Commit**

```bash
git add docs/superpowers/plans/2026-08-12-agent-multimodal-input-summary.md
git commit -m "$(cat <<'COMMIT_MSG'
docs(plan): Agent 多模态输入实施完成总结

给 reviewer 一页纸; 改动 / 未改 / 验证 / 风险四块。
COMMIT_MSG
)"
```

---

## Self-Review

### Spec coverage check

| Spec 节 | Task |
|---|---|
| §3.1 MediaSource | Task 1 |
| §3.2 ContentPart | Task 2 |
| §3.3 AgentQuery | Task 5 |
| §4.1 ChatMessage.User.parts | Task 4 |
| §4.2 Agent.run/runStream | Task 6 |
| §4.3 AgentEvent.Initial.agentQuery | Task 6 Step 4b (合并自原 Task 7) |
| §4.4 ReActAgent 调用点 | Task 6 Step 4a + Task 8 |
| §5 Memory 摘要路径 | Task 8 |
| §6.1 OpenAI DTO + mapping | Task 9 + Task 10 |
| §6.2 Anthropic DTO + mapping | Task 11 + Task 12 |
| §7 UnsupportedContent | Task 3 |
| §8 端到端消息流 | Task 13 |
| §10 测试策略 | Task 1/2/4/5/8/10/12/13 (新测试) + Task 14/15 (回归) |
| §11 风险 | 在 Task 14 + summary 文档记录 |
| §12 落地步骤 | Task 1-16 严格按 §12 顺序 |

### Placeholder scan

- ❌ "TBD" / "TODO" / "implement later": 无
- ❌ "add appropriate error handling": 无 (具体错误处理在 Task 3/10/12)
- ❌ "write tests for the above": 无 (每个 task 都有具体测试代码)
- ❌ "similar to Task N": 无 (代码块完整)
- ❌ 模糊描述: 无

### Type consistency

| 类型 / 签名 | 定义处 | 使用处 | 一致？ |
|---|---|---|---|
| `MediaSource.Http/Data/FileId` | Task 1 | Task 2/8/10/12 | ✅ |
| `ContentPart.Text/Image/Audio/Video` | Task 2 | Task 5/8/10/12/13 | ✅ |
| `ContentPart.Kind` enum + DRY when(this) | Task 2 | Task 2 (派生) | ✅ |
| `AgentQuery(parts)` + `text()` | Task 5 | Task 6/7/13 | ✅ |
| `AgentEvent.Initial.agentQuery` | Task 6 Step 4b | Task 6/13 | ✅ |
| `AgentException.UnsupportedContent` | Task 3 | Task 10/12 | ✅ |
| `OpenAiContent.StringValue/PartsValue` | Task 9 | Task 10 | ✅ |
| `OpenAiContentPart.Text/ImageUrl/InputAudio` | Task 9 | Task 10 | ✅ |
| `AnthropicContentBlock.Image/Audio/Video` | Task 11 | Task 12 | ✅ |
| `AnthropicContentBlock.Image.Source` (Base64/Url/File) | Task 11 | Task 12 | ✅ |
| `MediaSource.shortLabel()` | Task 8 | Task 8 only | ✅ |

无类型漂移。