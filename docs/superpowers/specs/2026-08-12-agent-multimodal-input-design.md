# Agent 多模态输入设计

> 日期：2026-08-12 · 状态：**Draft**（待用户审阅）
> 模块：`agent/core`（同时波及 `agent/providers/openai`、`agent/providers/anthropic`）
> 范围：把 `Agent.run / runStream` 的纯文本输入升级为支持图片 / 音频 / 视频的输入能力。LLM 主流输出仍为文本 + tool calls，**本轮不引入 Assistant 多模态输出**。

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 提案代号 | agent-multimodal-input |
| 关联模块 | `agent/core`（核心类型）/ `agent/providers/openai` / `agent/providers/anthropic` |
| 关联前置 | `agent` (`Agent` / `ReActAgent` / `AgentEvent` / `Memory`) / `agent/llm` (`ChatMessage` / `ChatRequest` / `LlmProvider`) |
| 破坏性变更 | 是（`Agent.run / runStream` 入参类型变化；`ChatMessage.User` 字段重塑；`AgentEvent.Initial.agentQuery` 类型变化） |
| 不在范围 | Assistant 多模态输出、`MediaSource.LocalPath`、`Video + Data(base64)` 类型层禁止、`AgentQuery` 内置 metadata、流式 partial chunk 多模态块 |

---

## 1. 动机

当前 SDK 全部 6 层接口都不承载多媒体资源：

| 层 | 文件 | 当前形态 |
|---|---|---|
| Agent 入口 | `Agent.kt:23,33` | `input: String` |
| 消息模型 | `ChatMessage.kt:35` | `User.content: String` |
| 事件 | `AgentEvent.kt:27` | `Initial.userInput: String` |
| Memory | `RoundsBoundedMemory.kt:86-89` | 摘要走 `msg.content: String` |
| LLM Provider 契约 | `LlmProvider.kt` | 中立（接口承载 `List<ChatMessage>`） |
| OpenAI 实现 | `OpenAiMapping.kt:21` | `OpenAiMessage.content: String` |
| Anthropic 实现 | `AnthropicMapping.kt:24-29` | `AnthropicContentBlock.Text(content)` only |

LLM 协议层（OpenAI `image_url`、Anthropic `image` block）早已支持多模态输入，但 SDK 整条链路没有任何一层把它暴露出来——任何上游 caller 都无法向 Agent 提交图片 / 音频 / 视频。

业务痛点：

1. 飞书 / 微信 / Telegram 等 IM 平台的 incoming message 天然带图片、语音、视频附件，agent-sdk-core 完全消费不了。
2. 多模态 LLM 能力（视觉理解、图表问答、语音转写）被白白浪费。
3. 当前让 caller 自己把图片塞进 system prompt 字符串（甚至 base64 拼进文本）会在 `RoundsBoundedMemory` 摘要路径里被一并压进 history，导致 token 爆炸 + 信息丢失——错误的位置做错的事。

---

## 2. 设计原则

- **包装而非重写**：不动 `ReActAgent` 主循环结构；只在"用户回合"边界（`Agent` 入口、`AgentEvent.Initial`、`ChatMessage.User`）引入新类型。
- **不引入新的传输通道**：`ChatRequest.messages: List<ChatMessage>` 已能承载扩展后的 `ChatMessage`，`LlmProvider` 契约不动。
- **不破坏性回退**（memory `feedback_no_backward_compat`）：既然改了就直接改干净——`String` 重载不保留、`ChatMessage.User.content` 不保留旧字段、所有 caller 同步更新。
- **`AgentQuery` 保持精简**（memory `feedback_no_intermediate_types`）：只持 `parts` 一个字段；不为 traceId / metadata / sessionContext 等假想需求预留空间，将来要加从外面扩展。
- **`MediaSource` 三模态统一**：Http / Data / FileId 对 image / audio / video 完全对称；不为每个模态造平行类（避免 shotgun parallel class）。
- **`Video + Data(base64)` 在 provider 实现层 fail-fast**，**不是 type 层**——保持 `MediaSource.Data` 对三模态开放，未来真有支持 video base64 的 provider 时只需放开校验分支。
- **不加 `LocalPath`**：SDK 是协议层，path 在序列化前必须先转 wire 三选一之一；caller 自己 `File.readBytes()` → `Base64.encode()` 是 3 行代码，无须 SDK 帮忙（memory `feedback_callers_dont_handle_receiver_concerns`）。
- **Assistant 输出保持原样**：LLM 主流输出仍是文本 + tool calls；多模态输出当前 0 业务需求，加 sealed extension 会导致 type 漂移，本轮不动。
- **三种模态一起实现**（用户的决策）——便于横向对比发现设计缺陷（image / audio / video 在 wire format、provider 支持、内存成本上都有不同）。

---

## 3. 新类型签名

### 3.1 `MediaSource` — 资源来源（统一抽象）

新文件：`agent/core/src/main/kotlin/io/github/yeyi/agent/llm/MediaSource.kt`

```kotlin
package io.github.yeyi.agent.llm

import kotlinx.serialization.Serializable

/**
 * 多媒体资源的统一来源抽象，三种模态 (image/audio/video) 共用同一组变体。
 *
 * - [Http]  : 公网 URL 或内网可路由 URL，由 LLM provider 主动 fetch。
 * - [Data]  : base64 内联；适用于 image 和短 audio；video 由 provider 实现层拒绝。
 * - [FileId]: provider 托管的文件 ID（OpenAI files API、Anthropic files API）。
 *
 * **不提供 [LocalPath]**：SDK 是协议层，不接触本地文件系统；caller 自己把本地
 * 资源编码为 [Data] 或上传到 provider 拿到 [FileId]。
 */
@Serializable
public sealed interface MediaSource {
    public data class Http(public val url: String) : MediaSource
    public data class Data(public val mimeType: String, public val base64: String) : MediaSource
    public data class FileId(public val id: String) : MediaSource
}
```

### 3.2 `ContentPart` — 单条内容块

新文件：`agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ContentPart.kt`

```kotlin
package io.github.yeyi.agent.llm

import kotlinx.serialization.Serializable

/**
 * 用户回合（user turn）中的单条内容块。`parts` 列表在 `AgentQuery` 和
 * `ChatMessage.User` 中均按出现顺序保留，LLM 端按顺序拼接。
 *
 * 4 个变体独立 sealed 而非合并为 `Media(kind, source)` —— 三种媒体在未来
 * 各自会演化出差异化约束（image 的 detail 参数、audio 的 format tag、
 * video 的 clip window），早分开省后期返工。
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
    public data class Text(public val text: String) : ContentPart

    @Serializable
    public data class Image(public val source: MediaSource) : ContentPart

    @Serializable
    public data class Audio(public val source: MediaSource) : ContentPart

    @Serializable
    public data class Video(public val source: MediaSource) : ContentPart
}
```

### 3.3 `AgentQuery` — Agent 入口的用户回合

新文件：`agent/core/src/main/kotlin/io/github/yeyi/agent/AgentQuery.kt`（根包 `io.github.yeyi.agent`，与 `Agent.kt` 同包）

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ContentPart
import kotlinx.serialization.Serializable

/**
 * Agent 入口的"用户回合"包装：把文本 + 多模态块以出现顺序一次性提交。
 *
 * 与 [io.github.yeyi.agent.llm.ChatMessage.User] 不互替：前者是 Agent 层
 * 输入视角，后者是 LLM/Memory 层消息视角；通过 `ChatMessage.User(query.parts)` 互转。
 *
 * **不预留 metadata**：当前没有 traceId / sessionContext 等需求；将来要加时
 * 走外层扩展（companion factory 或 wrap），不动 data class 字段，避免变成垃圾桶。
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

---

## 4. 现有类型迁移

### 4.1 `ChatMessage.User`

```kotlin
// 旧
public data class User(public val content: String) : ChatMessage
// 新
@Serializable
public data class User(public val parts: List<ContentPart>) : ChatMessage {
    override val role: Role = Role.User
}
```

- `init { require(parts.isNotEmpty()) }` —— 空 parts 等价于无消息，构造时直接拒。
- **不挂 `text` 派生属性**：摘要路径在 `RoundsBoundedMemory` 直接 inline 处理多 part 取值；当前 0 其他 caller，未来真有再加。

### 4.2 `Agent.run / runStream`

```kotlin
public interface Agent {
    public fun run(query: AgentQuery): Flow<AgentEvent>
    public fun runStream(query: AgentQuery): Flow<AgentEvent>
}
```

- **删除 `run(input: String)` / `runStream(input: String)` 重载**（方案 a：caller 一律走 `AgentQuery`）。
- 破坏性变更；所有 caller 同步更新（见 §10 测试策略）。

### 4.3 `AgentEvent.Initial`

```kotlin
public sealed interface AgentEvent {
    public data class Initial(public val query: AgentQuery) : AgentEvent
    // ... 其余子类不变
}
```

- 与 `AgentQuery` 类型对齐，避免上游 caller 拿到事件时还要自己转换。

### 4.4 `ReActAgent` 调用点改动

```kotlin
// ReActAgent.kt:103
emit(AgentEvent.Initial(query))
// ReActAgent.kt:106
memory.add(ChatMessage.User(query.parts))
```

- `loop(input: String, ...)` 改名为 `loop(query: AgentQuery, ...)`（内部签名同步）。
- `runStream` 的 `accumulatedText` / `toolCalls` 收集逻辑不变——只动"用户回合"边界。

---

## 5. Memory 摘要路径

### 5.1 `RoundsBoundedMemory` 摘要规则改动

```kotlin
// RoundsBoundedMemory.kt:86 当前
is ChatMessage.User -> msg.content
// 改为
is ChatMessage.User -> msg.parts.joinToString("\n") { part ->
    when (part) {
        is ContentPart.Text -> part.text
        is ContentPart.Image -> "[image:${part.source.shortLabel()}]"
        is ContentPart.Audio -> "[audio:${part.source.shortLabel()}]"
        is ContentPart.Video -> "[video:${part.source.shortLabel()}]"
    }
}
```

其中 `MediaSource.shortLabel()`：

```kotlin
internal fun MediaSource.shortLabel(): String = when (this) {
    is MediaSource.Http -> url.take(64)
    is MediaSource.Data -> "$mimeType, ${base64.length / 1024}KB"
    is MediaSource.FileId -> id
}
```

- 多模态块摘要为占位文本，**保留"此 user message 包含附件"的语义**，便于 LLM 在后续轮次知道上下文里有什么。
- 占位长度可控（URL 截断、base64 只报 KB 数、file id 全保），避免摘要膨胀。

### 5.2 不动 `Memory` 接口

- `Memory.add(message: ChatMessage)` 接口签名不变；`ChatMessage.User.parts` 是 ChatMessage 内部形态变化。
- `history()` / `rebuild()` 不变。

---

## 6. Provider 实现

### 6.1 OpenAI（`OpenAiDtos.kt` + `OpenAiMapping.kt`）

**`OpenAiMessage.content` 类型扩展**：从 `String?` 扩展为 `OpenAiContent = String?` ∪ `List<OpenAiContentPart>?`。JsonElement 多态即可，kotlinx.serialization 通过 `ContentPolymorphicSerializer` 处理。

```kotlin
// OpenAiDtos.kt 新增
@Serializable
internal sealed class OpenAiContentPart {
    @Serializable
    @SerialName("text")
    data class Text(val text: String) : OpenAiContentPart()

    @Serializable
    @SerialName("image_url")
    data class ImageUrl(
        val url: String,                           // "https://..." 或 "data:image/...;base64,..."
        @SerialName("detail") val detail: String? = null  // "auto" | "low" | "high"
    ) : OpenAiContentPart()

    @Serializable
    @SerialName("input_audio")
    data class InputAudio(
        val data: String,                          // base64
        val format: String                         // "wav" | "mp3"
    ) : OpenAiContentPart()
}

@Serializable
internal data class OpenAiMessage(
    val role: String,
    val content: OpenAiContent = OpenAiContent.StringValue(""),  // 多态
    @SerialName("tool_calls") val toolCalls: List<OpenAiToolCall>? = null,
    @SerialName("tool_call_id") val toolCallId: String? = null,
    val name: String? = null
)

internal sealed class OpenAiContent {
    @Serializable
    @SerialName("string")
    data class StringValue(val value: String) : OpenAiContent()
    @Serializable
    @SerialName("parts")
    data class PartsValue(val value: List<OpenAiContentPart>) : OpenAiContent()
}
```

**`mapToOpenAi` 改造**（`OpenAiMapping.kt`）：

```kotlin
is ChatMessage.User -> {
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
    OpenAiMessage(role = "user", content = content)
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
        // mimeType 形如 "audio/wav" → format = "wav"
        val format = source.mimeType.substringAfter("/").takeIf { it.isNotEmpty() } ?: "wav"
        OpenAiContentPart.InputAudio(
            data = source.base64,
            format = format
        )
    }
    is MediaSource.Http -> throw AgentException.UnsupportedContent(
        "OpenAI input_audio requires inline base64; use Data source for audio"
    )
    is MediaSource.FileId -> throw AgentException.UnsupportedContent(
        "OpenAI input_audio does not support file_id in Chat Completions"
    )
}
```

### 6.2 Anthropic（`AnthropicDtos.kt` + `AnthropicMapping.kt`）

**`AnthropicContentBlock` 扩展**：现有 `Text` / `ToolUse` / `ToolResult` 不动，新增 `Image` / `Audio` / `Video` 三个 sealed 子类。

```kotlin
// AnthropicDtos.kt 新增
@Serializable
internal sealed class AnthropicContentBlock {
    // ... 既有 Text / ToolUse / ToolResult 不变 ...

    @Serializable
    @SerialName("image")
    data class Image(
        val source: Source
    ) : AnthropicContentBlock() {
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

        @Serializable
        sealed class Source
    }

    @Serializable
    @SerialName("audio")
    data class Audio(val source: Image.Source) : AnthropicContentBlock()
        // 重用 Image.Source（Anthropic 的 audio 也是 base64/url/file 三选一）

    @Serializable
    @SerialName("video")
    data class Video(val source: Image.Source) : AnthropicContentBlock()
}
```

**`mapToAnthropic` 改造**（`AnthropicMapping.kt:24`）：

```kotlin
is ChatMessage.User -> {
    val blocks = msg.parts.map { part ->
        when (part) {
            is ContentPart.Text -> AnthropicContentBlock.Text(part.text)
            is ContentPart.Image -> AnthropicContentBlock.Image(mapImageToAnthropic(part.source))
            is ContentPart.Audio -> AnthropicContentBlock.Audio(mapImageToAnthropic(part.source))
            is ContentPart.Video -> AnthropicContentBlock.Video(
                source = mapVideoToAnthropic(part.source)  // 拒绝 Data
            )
        }
    }
    messages.add(AnthropicMessage(role = "user", content = blocks))
}

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

**Anthropic 流式 decoder**（`AnthropicStreamDecoder.kt`）不动：现有只解析 `text` / `tool_use` 两类块，多模态块不影响流式事件流（image / audio / video 在响应侧由 caller 后处理）。

### 6.3 统一 fail-fast 策略

- **Video + Data** 在两个 provider 都 throw `AgentException.UnsupportedContent`（message 友好说明）。
- **OpenAI file_id for image/audio** 当前不支持（同上 throw）。
- **OpenAI Http for audio** 不支持（同上 throw）。
- 不在 type 系统层静态禁止——给未来 provider 扩展留口子（memory `feedback_align_with_existing_design`）。

---

## 7. 错误处理

### 7.1 新增 `AgentException.UnsupportedContent`

扩展 `AgentException.kt`：

```kotlin
public sealed class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    // ... 既有子类不变 ...

    /** Provider 拒绝某种内容形态（如 OpenAI video、video base64 等）。 */
    public class UnsupportedContent(message: String) : AgentException(message)
}
```

### 7.2 错误流

- `mapToOpenAi` / `mapToAnthropic` 抛 `UnsupportedContent` 时，Provider `chat` / `chatStream` 沿用既有错误包装（`AgentException.LlmError(cause)` for transport；其他类型透传 `UnsupportedContent`）。
- `ReActAgent.loopOnce` 调用 LLM 时，`UnsupportedContent` 会被 `try { ... } catch (t: Throwable)` 捕获 → emit `AgentEvent.Failed(t)` → hook `onRunFailed`。caller 拿到清晰的语义错误，不是裸 `KtorException`。

---

## 8. 端到端消息流（多模态）

```
[caller]  agent.run(AgentQuery(listOf(
              ContentPart.Text("描述这张图"),
              ContentPart.Image(MediaSource.Http("https://.../cat.jpg")))))
   │
   ▼
[ReActAgent]  emit Initial(query)
              memory.add(ChatMessage.User(query.parts))
   │
   ▼
[ReActAgent.loopOnce]  buildRequest() → ChatRequest(messages=[System, User(parts)])
   │
   ▼
[LlmProvider.chat(request)]  mapToOpenAi / mapToAnthropic
                              ├─ Text      → OpenAiContentPart.Text  / AnthropicContentBlock.Text
                              ├─ Image+Http → image_url / Image.UrlSource
                              ├─ Image+Data → image_url(data URI) / Image.Base64Source
                              ├─ Audio+Data → input_audio / Audio.Base64Source
                              └─ Video+Http → throw UnsupportedContent (OpenAI) / Image.UrlSource (Anthropic)
   │
   ▼
[OpenAI / Anthropic API]  HTTP POST → SSE / JSON
   │
   ▼
[Provider mapFrom...]  ChatResponse(message=Assistant(content=..., toolCalls=...))
   │
   ▼
[ReActAgent.loopOnce]  memory.add(Assistant) → 结束或继续 tool 循环
   │
   ▼
[AgentEvent.Final(result)] / Failed(UnsupportedContent)
```

---

## 9. 不在范围

| 不做 | 理由 |
|---|---|
| Assistant 多模态输出（image/audio/video） | LLM 主流输出仍为文本；当前 0 业务需求；将来加 sealed extension 不破坏现有数据形状 |
| `MediaSource.LocalPath` | SDK 是协议层；caller 自己 `File.readBytes()` → `Base64.encode()` 是 3 行代码 |
| type 层禁止 `Video + Data` | 保留扩展性，未来 provider 支持时放开校验分支 |
| `AgentQuery` 内置 metadata | 当前 0 需求；为假想需求预留字段违反 YAGNI |
| 流式 partial chunk 多模态块 | 当前 LLM 输出仍为文本增量，多模态输出本轮不做 |
| LLM 端**主动 fetch** 公网 URL 的能力扩展 | 已是协议层既有行为，本轮不改变 provider 网络配置 |
| Anthropic Prompt Caching / Tool Search 等高级特性 | 与多模态正交，留待后续独立提案 |

---

## 10. 测试策略

### 10.1 单元测试矩阵

| 模块 | 用例 |
|---|---|
| `agent/core` AgentQuery | `text()` / `of()` / 空 parts throw / parts 顺序保留 |
| `agent/core` ContentPart | 4 个子类构造 + `kind` 派生 |
| `agent/core` MediaSource | 3 个子类构造 |
| `agent/core` ChatMessage.User | parts 非空校验 / 序列化 round-trip |
| `agent/core` Agent | `run(query)` / `runStream(query)` 签名可用 |
| `agent/core` AgentEvent.Initial | query 类型为 AgentQuery |
| `agent/core` RoundsBoundedMemory | User 多模态 parts → 摘要占位文本正确（`[image:...]` 等） |
| `agent/providers/openai` OpenAiMapping | `Text→StringValue` / `Image+Http→ImageUrl` / `Image+Data→data URI` / `Video→throw` / `Audio+Http→throw` |
| `agent/providers/anthropic` AnthropicMapping | `Text→Text` / `Image+Data→Base64Source` / `Video+Data→throw` / `Video+Http→UrlSource` |

### 10.2 集成测试

| 模块 | 用例 |
|---|---|
| `agent/core` ReActAgent | `run(AgentQuery(listOf(textPart, imagePart)))` → Provider mock 收到 multipart content；`run(AgentQuery.text("hi"))` 兼容旧行为（验证重载删除后等价） |
| `agent/core` ReActAgent | `run` 抛出 `UnsupportedContent` 时 emit `Failed(UnsupportedContent)`，hook `onRunFailed` 触发 |

### 10.3 现有调用点同步

按 memory `feedback_no_backward_compat`，**所有 caller 同步更新**：

- `agent/core` 自己的测试（`AgentHookTest.kt` / 任何 `agent.run(...)` 调用点）
- `agent/mcp` `McpTest.kt` / `LocalTransportTest.kt` 中 StubLlm 构造（仅实现接口，不直接构造 ChatMessage.User，但如有用到 User 须改）
- `agent/subagent` 测试（任何 `ChatMessage.User(input)` 调用）
- `agent/team` 所有测试 + `BossAgent` / `Beast` 实现（内部可能构造 User）
- `agent/skill` / `agent/capability` / `agent/toolset` / `agent/tool/compression` 测试
- `demos/agent` / `demos/team`
- `gateway/*`（`AgentRunner` 等用户回合入口；按用户指示本轮**不深入改动 gateway**，但同步改完才能编译通过）

每个 caller 的 `ChatMessage.User(input)` → `ChatMessage.User(listOf(ContentPart.Text(input)))`，可借助 helper extension：

```kotlin
internal fun String.toUserMessage(): ChatMessage.User =
    ChatMessage.User(listOf(ContentPart.Text(this)))
```

放在 `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentExtensions.kt`（已有），仅 `internal` 可见，避免外部 caller 偷懒绕过 `AgentQuery`。

---

## 11. 风险与权衡

### 11.1 破坏性 API 变更

`Agent.run / runStream` 入参类型变化 → 所有 caller 强制更新。但 SDK 还在 v1 内（见 commit log `feat(agent): ...`），用户群体小，破坏性可接受。Mitigation：同步更新所有调用点（§10.3），编译通过即视作完成。

### 11.2 OpenAI ContentPolymorphic 复杂度

`OpenAiMessage.content` 从 `String?` 扩展为 `OpenAiContent = StringValue | PartsValue`，需要 polymorphic serializer。**风险**：kotlinx serialization 对 polymorphic 默认值的处理有边界 case（之前 `kotlinx JsonPrimitive null quirk` 教训）。Mitigation：参考已有 `AnthropicContentBlock` sealed 处理模式；写 round-trip 测试覆盖。

### 11.3 OpenAI Chat Completions video / file_id 边界

按当前协议，多种输入形态在 OpenAI 不支持（video、file_id for image/audio、Http for audio）。fail-fast 抛 `UnsupportedContent` 给清晰错误，但**用户体感上可能"明明新接口支持三模态，为什么 OpenAI 还报错"**。Mitigation：异常 message 明确说明替代方案（"use Responses API instead"）。

### 11.4 摘要占位文本丢失信息

`[image:...]` 占位让 LLM 知道"之前有过图片"，但**内容丢失**。对于长对话多图场景，摘要会让 LLM "忘记"之前图的细节。Mitigation：本轮接受此限制；如果业务后续要求"摘要保留图片细节"，需独立提案（多模态摘要方案，e.g., LLM 端重新生成图片描述）。

### 11.5 `Memory` 接口不动 ≠ 无成本

`ChatMessage.User.parts` 列表序列化比原来的 `content: String` 字节大（每 part 多一层对象包装）。多模态 user 消息进 history 后每次 LLM call 都要重新序列化所有 parts——极端场景（百轮对话 × 每轮 5 张图）可能拖慢。Mitigation：本轮不优化；性能调优是独立工作流。

---

## 12. 落地步骤（高层）

1. 新增 `MediaSource.kt` / `ContentPart.kt` / `AgentQuery.kt`。
2. 改 `ChatMessage.kt`：`User.content: String` → `parts: List<ContentPart>` + 派生 `text` 属性。
3. 改 `Agent.kt`：`run / runStream(input: String)` → `(query: AgentQuery)`。
4. 改 `AgentEvent.kt`：`Initial.agentQuery: AgentQuery`。
5. 改 `ReActAgent.kt`：调用点同步（emit / memory.add / loop 签名）。
6. 改 `RoundsBoundedMemory.kt`：摘要路径按 parts 处理。
7. 改 `OpenAiDtos.kt` + `OpenAiMapping.kt`：DTO 扩展 + mapToOpenAi 多模态分支。
8. 改 `AnthropicDtos.kt` + `AnthropicMapping.kt`：DTO 扩展 + mapToAnthropic 多模态分支。
9. 加 `AgentException.UnsupportedContent`。
10. 同步所有 caller（`agent/*/test`、`demos`、`team`、`gateway/*` 编译通过）。
11. 跑全套测试。