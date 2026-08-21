# Modality MediaArchive 设计

> 日期：2026-08-19 · 状态：**Draft v3**(实施回顾后修正)
> 模块：`agent/core` + `agent/memory` + `agent/session`
> 范围：用 `MediaSource.Local` 表达本地文件持久化引用 + `MediaArchive` 抽象封装 IO + `ModalityAdapter` 在 agent/core 内置做 LLM 边界适配 + `ArchivingMemory` 外层装饰器在 agent/session 默认开启归档
> 偏离记录：详见 §14 (DEV-1 plain interface / DEV-2 suspend contract / DEV-3 T1 commit body 误述)

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 提案代号 | modality-media-archive |
| 关联模块 | `agent/core`、`agent/memory`、`agent/session`、`agent/providers/openai`、`agent/providers/anthropic` |
| 关联前置 | 2026-08-12 agent-multimodal-input（`MediaSource.Http/Data/FileId`、`ContentPart.Image/Audio/Video`、`adaptModality`、`toTextMessage`）|
| 破坏性变更 | 部分（`MediaSource` 加 4th variant `Local`；`Memory` 接口加 `mediaArchive` 字段；`ReActAgent.buildRequest` 改走 `ModalityAdapter`）|
| 不在范围 | 异步 IO、文件压缩、远端 archive、provider 层面 Local 自动转 Data、archive 自动清理策略（跟随 session 生命周期）、archive 单 fileId 清理 API（不暴露给 caller, 整 session 删除走 `deleteRecursively`） |

---

## 1. 动机

`ReActAgent.buildRequest` 当前有 16 行内联多模态适配逻辑（`adaptModality` + `toTextMessage`），与本地文件、持久化场景脱节：

1. **caller 写本地文件样板代码**：拿到 `/sdcard/photo.jpg` 必须自己 `File.readBytes()` + `Base64.encode()` + `MediaSource.Data(...)`，SDK 0 价值
2. **持久化无压缩空间**：`ChatMessage` 序列化为 base64 inline，大图吃满 storage；`Memory` 没有"已落盘可重读"的引用表达
3. **跨 round 占位策略不可定制**：caller 想要不同策略只能改 SDK 源码
4. **协议层与 IO 层耦合**：`MediaSource` 类型遇到"本地文件"概念时不知道该不该纳入

业务场景：

- Android caller 拿到本地图片想让 Agent 看图——目前要写 5 行样板
- 长 session 累积大量含图片的 `ToolResult`/历史 `User`——storage 浪费
- caller 想关掉占位（按字节成本算过，宁可每轮重传原图）——做不到

---

## 2. 设计原则

- **`MediaSource.Local` 是 ID-based 持久化引用**：跟 `FileId` 同形，区别仅"谁持有"。ID opaque，路径由 `MediaArchive` 内部管理。
- **`ArchivingMemory` 是归档外层装饰器**：写侧（Data → Local）职责独立成 `ArchivingMemory(decorated: Memory)` —— 继承 `Memory by decorated` 转发 `mediaArchive`/`history()`/`rebuild()`,只 override `add()`:内部硬编码 1KB 阈值,超过则调 `decorated.mediaArchive.store()` 转 Local,再把 archived message 转给下游 `decorated.add()`。`JsonlBackedMemory` / `JsonlConversation` 恢复纯存储(`add()` 直接落盘 caller 给的 message),不掺归档逻辑。阈值("什么 Data 值得单独存")不上提到 `MediaArchive` 接口契约,由 `ArchivingMemory` 自己决定。
- **持久化路径默认开归档**：`SessionRepository.hydrateSession()` 把 `ArchivingMemory` 嵌在链最外层（`ArchivingMemory → JsonlConversation → JsonlBackedMemory`），保证 `page*.jsonl` + `memory.jsonl` 两处落盘形态一致。caller 拿到的 `session.memory` 已经是归档路径；裸 `JsonlConversation` 是"未归档 raw 存储"模式，文档需注明。
- **`Memory.mediaArchive` 单一注入点,装饰链透明转发**：`Memory` 接口的 `mediaArchive` 是纯 abstract contract(无默认实现);archive 实体只注入装饰链最下层 —— 持久化场景 `JsonlBackedMemory` 在构造时接收 archive,`InMemoryMemory` 内部实例化 `InMemoryMediaArchive()`。其他层 `JsonlConversation` / `ArchivingMemory` / `RoundsBoundedMemory` / `ReadOnlyMemory` 通过 Kotlin `Memory by` delegate 或 `get() = inner.mediaArchive` 透明转发,`add()` 自身不接触 archive(archiving 逻辑由 `ArchivingMemory` 通过 `decorated.mediaArchive` 访问)。caller 拿到的最外层 `Memory.mediaArchive` 永远有效。
- **装饰器零决策**：`RoundsBoundedMemory` 装饰 `ArchivingMemory` 时，`add()` 直接转发到 inner；归档是 `ArchivingMemory` 单一职责，别的装饰器不掺和。
- **`ModalityAdapter` 是 `agent/core` 内置 interface,不是 hook**:跟 caller 写的扩展点(`AgentHook`)严格分开。`MediaArchive` 放在 `adapt(messages, archive)` 方法签名而非构造器 —— 适配工作核心是处理归档,显式化在契约里;实现类无隐藏状态。使用 plain `interface`(非 `fun interface`)是为 future extensibility 预留 —— 当前只 1 个 `adapt` 方法但将来加多个抽象方法(如 `resolveLocal` / `describePlaceholders`)无需破坏 SAM 兼容性。
- **`ModalityAdapter` 默认 ON**:`ReActAgent` 构造参数必填 `modalityAdapter: ModalityAdapter`;默认值由 `AgentBuilder.build()` 决定(caller 未显式设置时填 `DefaultModalityAdapter()` 无参构造,archive 在 `adapt()` 时由 `ReActAgent` 传 `memory.mediaArchive`)。
- **跨 round 不读盘**：Adapter 看到 Local 不一定 resolve——跨 round 直接转 `[image] local fileId=<前8字符>` 占位文本（占位截断前缀;模型若需读图,在末条 User 的 `[local] fileId=<完整 uuid>` 引用处用整串调工具）。
- **Provider fail-fast 兜底**：正常路径下 `ModalityAdapter` 在 `buildRequest` 里 resolve 完所有 Local；非正常路径（边界情况）由 provider 抛 `UnsupportedContent`。
- **IO 异常正常传播**：不吞 caller 数据错误，让 caller 决定重试或丢弃。

---

## 3. 新类型

### 3.1 `MediaSource.Local`（4th variant）

新增到 `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ChatRequest.kt` 的现有 `MediaSource` sealed interface：

```kotlin
@Serializable
public sealed interface MediaSource {
    // ... Http/Data/FileId 现状不变 ...

    /**
     * 媒体文件的本地存储引用，由 [io.github.yeyi.agent.memory.MediaArchive] 持有。
     *
     * - [fileId]  : opaque 文件 ID（UUID），由 MediaArchive 生成;跟 [FileId] 同模式,
     *               区别仅 [FileId] 由 provider 持有、Local 由 agent 持有。
     *               两处出现 fileId 形态不同: 跨 round 占位截断前缀（仅识别）,
     *               末条 User 引用完整（模型工具调用）;详见 §5。
     * - [mimeType]: 媒体 MIME 类型,由 caller 在构造 [Data] 时确定并落盘。
     *
     * Provider 实现层**不支持** [Local] —— 收到必须抛
     * [io.github.yeyi.agent.AgentException.UnsupportedContent]。
     * Adapter ([ModalityAdapter]) 在送 LLM 前会 resolve 为 [Data]。
     */
    @Serializable
    @SerialName("local")
    public data class Local(
        public val fileId: String,
        public val mimeType: String,
    ) : MediaSource
}
```

更新 `MediaSource` KDoc 把"三种变体"改为"四种变体"，列出 Local 的用途与限制。

### 3.2 `MediaArchive`（plain interface）

合入 `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/Memory.kt`：

```kotlin
public interface MediaArchive {
    /**
     * 存储 [MediaSource.Data] 的字节并返回 [MediaSource.Local] 引用。
     * 实现负责生成 opaque ID 并保证后续 [resolve] 能找到对应字节。
     * 与 [resolve] 返回 [MediaSource.Data] 对称——archive 边界统一用
     * `Data` 类型表达字节内容。
     *
     * 声明为 `suspend` 是为了让实现内部用 [kotlinx.coroutines.sync.Mutex]
     * 序列化并发 IO(与 [Memory] 的线程安全契约保持一致);同步实现可直接
     * `return` 不挂起。
     */
    public suspend fun store(data: MediaSource.Data): MediaSource.Local

    /**
     * 解析 [MediaSource.Local] 引用，返回 [MediaSource.Data]。archive 内部
     * 缺失该 id 时抛 [IllegalStateException]——这是 caller 数据错误，不该被吞。
     */
    public suspend fun resolve(local: MediaSource.Local): MediaSource.Data
}
```

`MediaArchive` 是 plain `interface` 而非 `fun interface`(原 spec v2 写了 `fun interface`,但 2 个抽象方法不允许 `fun interface` —— 见 §14 DEV-1)。

`MediaArchive` 只表达 IO 能力（store / resolve），**不**承担"是否落盘"的策略。归档阈值（什么 Data 算值得单独存文件）由持久化 `Memory` 实现层在 `add()` 内决定，不暴露在 archive 接口契约里。

SDK 默认实现位于 `InMemoryMemory.kt` 内，作为 `private class InMemoryMediaArchive`
嵌套类（`InMemoryMemory` 实例化持有, 测试用）。`MediaArchive` 扩展点按三层划分：

- **`agent/core`**：仅提供接口契约 + 内存测试用 `InMemoryMediaArchive`（不内置文件 IO）。
- **`agent/session`**：作为 core 的 caller, 在 `FilesystemMediaArchive.kt` 提供生产级
  文件系统默认实现（per-session `media/` 目录）。持久化场景 caller 通过 `SessionManager`
  拿到的 `session.memory` 已持有 `FilesystemMediaArchive`, 无需自行构造。
- **caller app**：可实现 `MediaArchive` 接口提供自定义 archive（S3 / DB / 加密 / TTL 等）,
  在 `JsonlBackedMemory` 构造时注入; 接口契约目前仅 `store`/`resolve` 两个方法,
  异步/加密等 caller 在自己的实现内处理。

---

## 4. Memory 扩展

`Memory` 接口加 `mediaArchive` 字段（**纯 abstract,无默认实现**）—— 这是
interface 的 contract,所有实现必须 override,无例外:

```kotlin
package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage

public interface Memory {
    /**
     * 本实例持有的 [MediaArchive],用于在请求边界把 [io.github.yeyi.agent.llm.MediaSource.Local]
     * 解析为 [io.github.yeyi.agent.llm.MediaSource.Data]。
     *
     * 必须由实现类持有实例(而非给默认值):装饰链需要逐层透传到最下层那一档,
     * 默认值会被重复注入。
     */
    public val mediaArchive: MediaArchive

    // ...其他接口维持现状...
}
```

`MediaArchive` 仅承担两个职责：(1) 供 `ModalityAdapter` 在末条 User 上读 Local；
(2) caller 自己 `store()` 把字节转 Local 写进 ChatMessage。`Memory.add()` 不做
自动重写——传什么存什么，是否转 Local 由 caller 决定（避免 IO 副作用潜入 Memory）。

- `InMemoryMemory` 内部实例化 `InMemoryMediaArchive()` 作为 nested private class，
  **不**接受 caller 注入——`mediaArchive` 字段是 `Memory` 接口的 public contract
 （供 `ModalityAdapter` 读取），但实现细节由 Memory 自己决定。单 session / 测试
  场景下不需要 caller 关心 archive 配置。

```kotlin
public class InMemoryMemory : Memory {
    override val mediaArchive: MediaArchive = InMemoryMediaArchive()
    // add() / history() / rebuild() 维持现状

    private class InMemoryMediaArchive : MediaArchive {
        // 直接存 base64 字符串,跳过 store 的 decode 和 resolve 的 encode。
        // 内存场景下不需要还原 bytes——base64 占内存略多但省两次编解码开销。
        private val store = mutableMapOf<String, String>()
        override suspend fun store(data: MediaSource.Data): MediaSource.Local {
            val fileId = UUID.randomUUID().toString()
            store[fileId] = data.base64
            return MediaSource.Local(fileId, data.mimeType)
        }
        override suspend fun resolve(local: MediaSource.Local): MediaSource.Data {
            val base64 = store[local.fileId]
                ?: throw IllegalStateException("MediaArchive missing fileId=${local.fileId}")
            return MediaSource.Data(local.mimeType, base64)
        }
    }
}
```

- `RoundsBoundedMemory(underlying: Memory)` / `ReadOnlyMemory(delegate: Memory)`
  是装饰器，转发 inner 的 `mediaArchive`，**不**做归档决策 —— 归档是
  `ArchivingMemory` 的单一职责，别的装饰器零决策透传:

```kotlin
internal class RoundsBoundedMemory(...) : Memory {
    override val mediaArchive: MediaArchive get() = underlying.mediaArchive
    // add() / history() / rebuild() 现状不变
}

internal class ReadOnlyMemory(private val delegate: Memory) : Memory {
    override val mediaArchive: MediaArchive get() = delegate.mediaArchive
    // add() / history() / rebuild() 现状不变
}
```

- 持久化场景由 `agent/session` 模块的 `SessionRepository` 内部完成
  构造链（详见下面 `SessionRepository.kt` 改动）：caller 通过 `SessionManager`
  拿到的 `session.memory` 内部已经持有 `ArchivingMemory` → `JsonlConversation`
  → `JsonlBackedMemory` → `FilesystemMediaArchive` 整条链。caller 不直接接触
  `JsonlBackedMemory` 或 `FilesystemMediaArchive` —— SDK core 不内置这些实现,
  因为 IO 路径 / 清理策略 / 跨进程语义属于 session 模块职责。

修改 `agent/session/src/main/kotlin/io/github/yeyi/agent/session/JsonlBackedMemory.kt`：
构造参数加 `mediaArchive: MediaArchive`,`override val mediaArchive = ...`。
archive 在最下层 `JsonlBackedMemory` 注入,所有上层 memory 通过 `Memory by`
delegate / `get() = inner.mediaArchive` 透明转发,caller 拿到的最外层
`session.memory.mediaArchive` 永远有效。

```kotlin
public class JsonlBackedMemory(
    private val file: File,
    override val mediaArchive: MediaArchive,
) : Memory { /* add() / history() / rebuild() 现状不变 */ }
```

修改 `agent/session/src/main/kotlin/io/github/yeyi/agent/session/ArchivingMemory.kt`
（新增）—— 归档作为外层装饰器，独立职责：`add()` 先归档 message，再把 archived
版本转给下游 `decorated`。`history()` / `rebuild()` 透传（下游返回的 message
已经是 archived 状态）。**archive 实体不在 ArchivingMemory 持有**，通过
`Memory by decorated` delegate `mediaArchive`,归档时调
`decorated.mediaArchive.store()` 拿 archive —— 单一注入点(最下层
`JsonlBackedMemory`),所有上层透明转发,避免重复注入:

```kotlin
public class ArchivingMemory(
    private val decorated: Memory,
) : Memory by decorated {

    override suspend fun add(message: ChatMessage) {
        decorated.add(archiveLargeMedia(message))
    }

    /**
     * 把超过 1KB 的 [MediaSource.Data] 通过 [decorated.mediaArchive.store] 转
     * [MediaSource.Local]，减少存储体积。1KB 以下的纯色 logo / favicon
     * 之类 inline 更划算 —— 避免磁盘 IO + 多一条 cleanup entry。
     *
     * 阈值 1024 = base64 长度（≈ 768B 原始字节），设计依据：
     * [JsonlConversation.pageSizeThreshold] 默认 10KB 的 1/10，避免单图占满整 page。
     *
     * 仅 [ChatMessage.User] / [ChatMessage.ToolResult] 内的 Image/Audio/Video
     * parts 会被检查；System / Assistant 不含 media 透传。
     */
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

`JsonlConversation` **完全不动** —— 当前实现 `: Conversation, Memory by innerMemory`
已经通过 `Memory by` 委托自动转发 `mediaArchive` 字段，`add()` 不知道也不关心
归档存在,收到的 message 直接落盘 + 转给 innerMemory。归档由外层
`ArchivingMemory` 装饰器在 message 进入 `JsonlConversation.add()` **之前**完成,
所以 `page*.jsonl` 与 `memory.jsonl` 两处落盘形态天然一致(同一份 archived
message 流过两个 `add()`)。

修改 `agent/session/src/main/kotlin/io/github/yeyi/agent/session/SessionRepository.kt`：
统一 session 相关文件到 per-session 目录。完整布局如下：

```
sessions/{accountId}/
├── sessions.jsonl                          ← 会话元数据索引 (per-account)
└── {sessionId}/                            ← 每个 session 独立目录
    ├── memory.jsonl                        ← JsonlBackedMemory (Memory 接口)
    ├── conversations/                      ← JsonlConversation 分页 (Conversation 接口)
    │   └── page*.jsonl
    └── media/                              ← FilesystemMediaArchive (archive 字节)
        └── {uuid}
```

原 `memories/{sessionId}.jsonl` 扁平文件和 `conversations/{sessionId}/` 子目录都迁进
per-session 目录。`sessions.jsonl` 是 per-account 索引（不属于任何 session），维持
在 `sessions/{accountId}/` 这一层。`deleteSession()` 一行
`getSessionDir(...).deleteRecursively()` 即可清理该 session 下的全部内容
（memory + conversations + media），不动 `sessions.jsonl`（索引条目同步删）。
`hydrateSession()` 内 wiring 改为"构造链：archive → JsonlBackedMemory →
JsonlConversation"，把 media archive 注入底层 memory。

```kotlin
class SessionRepository(baseDir: File) {

    // 现有 sessionsDir / json / sanitizeForPath / getUserDir / getSessionsFile
    // / readSessionsFromFile / createSession / findSessions / findSession
    // / saveSession 维持原状

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

    /**
     * 构造链:FilesystemMediaArchive + ArchivingMemory(外层归档) →
     * JsonlConversation → JsonlBackedMemory。
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

    /**
     * 删除 session 下的所有内容 (`memory.jsonl` + `conversations/` + `media/`),
     * 索引条目同步从 `sessions.jsonl` 移除。整 session 目录在 [getSessionDir]
     * 下,一行 deleteRecursively 覆盖三块。
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
}

caller 通过 `SessionManager` 拿到的 `session.memory` 内部已经持有
`ArchivingMemory` → `JsonlConversation` → `JsonlBackedMemory` → `FilesystemMediaArchive`
整条链,归档逻辑在 `ArchivingMemory` 装饰器层。caller 不直接接触 archive 或
ArchivingMemory 实现 —— 持久化路径已经默认打开归档(裸 `JsonlConversation`
是未归档 raw 存储模式,文档需注明)。

新增 `agent/session/src/main/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchive.kt`：

```kotlin
class FilesystemMediaArchive(
    private val rootDir: File,
) : MediaArchive {
    init { require(rootDir.exists() || rootDir.mkdirs()) }

    /**
     * 位于 [agent/session] 模块 —— 作为 core 的 caller 提供的生产级默认实现。
     * `agent/session` 通过 `SessionRepository.hydrateSession()` 注入到
     * `JsonlBackedMemory`，所有上层 Memory 通过 `Memory by` delegate 透明转发。
     * caller app 如需自定义 archive（S3 / DB / 加密），实现 [MediaArchive] 接口即可。
     */

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

`FilesystemMediaArchive` 只负责字节 ↔ 文件，**不**决定"什么值不值得落盘"。阈值是 `ArchivingMemory.archiveLargeMedia()` 内部的事——archive 是被动 IO 设备。

---

## 5. ModalityAdapter（agent/core 内置接口）

新增 `agent/core/src/main/kotlin/io/github/yeyi/agent/ModalityAdapter.kt`：

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.memory.MediaArchive

/**
 * 多模态消息适配器，在 LLM 请求边界做三件事：
 *
 * 1. **拆末条 ToolResult**：含 media 时拆成 text-only + 合成的 User（[adaptModality]）
 * 2. **找最后 User**：从 messages 找到最后一条 User 的索引
 * 3. **渲染**：
 *    - 末条 User → `archive.resolve()` 把 Local 转 Data，其他 media 透传
 *    - 其他消息 → [toTextMessage] 把 media 转 `[image] local fileId=xxx` 占位文本
 *
 * Adapter **不依赖** 整个 [Memory]，只通过 [MediaArchive] 拿读桥。
 * 这是纯变换接口，IO 通过 [MediaArchive] 注入；测试里直接 lambda mock。
 *
 * `MediaArchive` 放在方法签名而非构造器 —— 适配工作的核心就是处理归档，
 * 把它显式化在契约里：caller 一眼看得出"做适配需要 archive",实现类无隐藏
 * 状态。
 *
 * plain `interface` 而非 `fun interface`,为 future extensibility 预留
 * (若加 `resolveLocal` / `describePlaceholders` 等方法,SAM 兼容性自动保留)。
 * `adapt` 声明为 `suspend` 是因为其内会调 [MediaArchive.resolve](后者 suspend) —— 同步实现可直接 `return` 不挂起。
 */
public interface ModalityAdapter {
    public suspend fun adapt(messages: List<ChatMessage>, archive: MediaArchive): List<ChatMessage>
}
```

默认实现 `DefaultModalityAdapter`：

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive

class DefaultModalityAdapter : ModalityAdapter {

    override suspend fun adapt(messages: List<ChatMessage>, archive: MediaArchive): List<ChatMessage> {
        // 1. 末条 ToolResult 拆出 media(只对末条做,跨 round 历史在 mapIndexed 阶段占位)
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
     * 末条 User 的 [MediaSource.Local] 经 [MediaArchive.resolve] 转 [MediaSource.Data]，
     * 同时前置一条 `[local] fileId=xxx` 文本 part —— 模型既看得到图(Data),也拿到
     * 完整 fileId,想用工具读/操作该文件时把整串传回即可。
     * "末条 User"的判断由 [adapt] 负责,本方法只做 resolve + 引用注入。
     */
    private fun resolveUserMedia(user: ChatMessage.User, archive: MediaArchive): ChatMessage.User =
        user.copy(parts = user.parts.flatMap { part -> resolveLocal(part, archive) })

    /**
     * Local → `[fileId 文本 part, resolve 后的 media part]`;其余(Text / Http /
     * Data / FileId)原样单 part 返回。
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
```

`adaptToolResult` 内调用的 `ChatMessage.ToolResult.adaptModality()` 下沉为本文件内的
file-private extension function（从 `AgentExtensions.kt` 的 `internal` 扩展移过来）——
adapter 自己的 use case, 不该继续放在 agent/core 的公共 API 表面:

```kotlin
/**
 * 把含 media 的 [ChatMessage.ToolResult] 拆成 text-only ToolResult + 合成的 User。
 * 从 `AgentExtensions.kt` 的 internal 扩展下沉为本文件内的 file-private
 * extension —— 只被 [DefaultModalityAdapter] 使用, 不再对外暴露。
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

`toTextMessage`（`AgentExtensions.kt`）只在内部 `describeMediaSource` 的 when 里加一个分支，
函数结构不动：

```kotlin
is MediaSource.Local -> "local fileId=${source.fileId.take(8)}"   // 截断前缀,占位识别
// 跨 round 只生成占位文本,不调 archive.resolve(),不读盘
```

`FileId` 变体的占位截断策略跟 `Local` 同步（详见 §11 文件清单）。

占位文本由 `toTextMessage` 生成、引用文本由 adapter 生成，两处**不共用代码**
（`toTextMessage` 是按整条 message 变换的单函数，拆出 part 级工具函数得不偿失）；
两处形态不同：跨 round 占位截断前缀，末条 User 引用完整 id,模型用工具调时后者可整串传回。

**关键设计**：

- 末条 User 的 Local → 产出双 part:`Text("[local] fileId=xxx")` + `archive.resolve()` 后的 `Data`(`FilesystemMediaArchive` 读盘 + Base64 编码;`InMemoryMediaArchive` 直接取 base64 字符串)。模型既可看到图,又持有 fileId 引用供后续工具调使用
- 跨 round 的 Local → 单 part 占位 `Text("[image] local fileId=<前8字符>")`,**不读盘** —— 模型若要操作,把末条 User 引用处的完整 fileId 传回工具即可,语义自洽

---

## 6. ReActAgent 改动

`agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt`：`modalityAdapter` 构造器必填
（默认值由 `AgentBuilder.build()` 注入，与 `memory` / `hook` 的 pattern 一致）：

```kotlin
class ReActAgent(
    ...,
    private val memory: Memory,
    private val modalityAdapter: ModalityAdapter,
) {
    private suspend fun buildRequest(): ChatRequest {
        val messages = modalityAdapter.adapt(memory.history(), memory.mediaArchive)
        return ChatRequest(
            messages = buildList {
                add(ChatMessage.System(persona.toString()))
                addAll(messages)
            },
            tools = toolRegistry.all().map(Tool::toDefinition),
        )
    }
}
```

`agent/core/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt`：caller 可显式
设置 `modalityAdapter`，未设置时 `build()` 内填默认（`DefaultModalityAdapter` 无构造
参数,archive 在 `adapt()` 调时由 `ReActAgent` 传入 `memory.mediaArchive`）：

```kotlin
public class AgentBuilder {
    ...
    private var modalityAdapter: ModalityAdapter? = null

    public fun modalityAdapter(adapter: ModalityAdapter) {
        this.modalityAdapter = adapter
    }

    public fun build(): Agent {
        ...
        val modalityAdapter = modalityAdapter ?: DefaultModalityAdapter()
        return ReActAgent(..., modalityAdapter = modalityAdapter)
    }
}
```

`ModalityAdapter.adapt(messages, memory.mediaArchive)`(buildRequest 末端)负责 LLM 边界的多模态适配——Memory 不做 IO 重写,传什么存什么,caller 决定是否转 Local。

---

## 7. Provider fail-fast

OpenAI mapping（`agent/providers/openai/src/main/kotlin/.../OpenAiMapping.kt`）：

```kotlin
when (source) {
    is MediaSource.Http -> ...
    is MediaSource.Data -> ...
    is MediaSource.FileId -> ...
    is MediaSource.Local -> throw AgentException.UnsupportedContent(
        "MediaSource.Local requires ModalityAdapter to resolve to Data first"
    )
}
```

Anthropic mapping 同上结构。

---

## 8. 错误处理

| 场景 | 行为 |
|---|---|
| `archive.resolve()` 找不到 id | `IllegalStateException` 由 archive 实现抛 → Adapter 不 catch → 传播 |
| Caller 错误用 Local 但 archive 缺失 | 同上 |
| Local 在跨 round | `toTextMessage` 不调 archive，只生成占位文本，不出错 |
| Local 在末条 User 且 archive 缺失 | `archive.resolve` 抛 `IllegalStateException` → 传播 |
| `archive.store()` IO 失败（caller 主动 store 时）| `IOException` 传播，caller 决定重试或丢弃 |
| Provider 收到 Local | 抛 `AgentException.UnsupportedContent`（非正常路径兜底） |

**核心原则**：IO 异常正常传播，不吞。caller 数据错误（archive 缺失、文件不存在）应当显式抛，让 caller 决定。`Memory.add()` 不做 IO 重写，IO 责任只在 caller 自己调 `archive.store()` 和 `ModalityAdapter` 调 `archive.resolve()` 两个边界。

---

## 9. 行为变更（兼容性）

| 场景 | 旧行为 | 新行为（默认配置）|
|---|---|---|
| 末条 ToolResult 含 media | 内联拆 text + 合成 User | 同（ModalityAdapter 接管）|
| 跨 round User/ToolResult 含 media | 转 `[image] xxx` 占位 | 同，且 Local 也走占位路径 |
| 末条 User 含 Local | 当前没这类型 | `archive.resolve()` 转 Data 后发 LLM |
| 跨 round User 含 Local | 当前没这类型 | 转 `[image] local fileId=xxx` 占位，不读盘 |
| Caller 传大 Data 到 `ArchivingMemory` | 当前没这类型 | `ArchivingMemory.add()` 内部 `archiveLargeMedia` 转 Local，再 `decorated.add(archived)` 落盘 |
| System / Assistant | 透传 | 透传（不变） |

**默认配置下行为与旧实现完全一致**（除了新加的 Local 处理能力）。`ContentPart.Image.source: MediaSource` 类型签名零变更，caller 现有代码不受影响。

---

## 10. Caller 用法

```kotlin
// (A) 单 session / 测试场景：SDK 默认 InMemoryMemory（内部用 InMemoryMediaArchive）
val memory = InMemoryMemory()
val agent = ReActAgent(memory = memory)               // 默认 adapter 已注入

// (B) 持久化场景：caller 直接传 Data，ArchivingMemory.add() 内部自动转 Local
val sessionManager = SessionManager(baseDir = cacheDir)
val session = sessionManager.create(accountId = "alice", sessionName = "chat")
// session.memory 内部指向 ArchivingMemory(JsonlConversation(JsonlBackedMemory))
// —— SessionRepository 内部构造链,ArchivingMemory 自动归档
val agent = ReActAgent(memory = session.memory)

val bytes = File("/sdcard/photo.jpg").readBytes()
val data = MediaSource.Data(
    mimeType = "image/jpeg",
    base64 = Base64.getEncoder().encodeToString(bytes),
)
// 直接传 Data —— ReActAgent → memory.add() → ArchivingMemory.add() 内部
// 硬编码 1KB 阈值,超过 1KB 就调 archive.store() 写文件、转 Local 入库;
// 1KB 以下 inline 进 JSONL。下游 JsonlConversation + JsonlBackedMemory 都是
// 纯存储,转发 archived 版本 —— 两处落盘一致。
agent.query(parts = listOf(ContentPart.Image(data)))

// (C) caller 跨 query 复用同一图:store 一次拿 Local,后续 query 直接传 Local
//     —— base64 只在 archive 里存一份,不再每次 inline。适用于 InMemoryMemory
//     (它不自动归档,显式 store 是 caller 唯一去重手段);持久化场景
//     ArchivingMemory 自动转 Local,caller 显式 store 通常没必要。
val data = MediaSource.Data(
    mimeType = "image/jpeg",
    base64 = Base64.getEncoder().encodeToString(File("/sdcard/photo.jpg").readBytes()),
)
val local = memory.mediaArchive.store(data)        // memory 来自 (A),InMemoryMemory
agent.query(parts = listOf(ContentPart.Image(local)))
// 复用:再一次 query 直接传同一个 Local,不再 store、不再 inline base64
agent.query(parts = listOf(ContentPart.Image(local)))

// (D) 已有 wire-bound 用法不变
agent.query(parts = listOf(ContentPart.Image(MediaSource.Http("https://..."))))
```

---

## 11. 文件清单

### 新增

- `agent/core/src/main/kotlin/io/github/yeyi/agent/ModalityAdapter.kt` — `ModalityAdapter` plain `interface`(`suspend fun adapt(messages, archive)`,把 MediaArchive 显式化在契约里) + `DefaultModalityAdapter` 默认实现(无构造参数) + `ChatMessage.ToolResult.adaptModality()` file-private extension(从 `AgentExtensions.kt` 下沉)
- `agent/session/src/main/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchive.kt` — 持久化 archive 实现；纯 IO（store/resolve），不持有归档阈值策略
- `agent/session/src/main/kotlin/io/github/yeyi/agent/session/ArchivingMemory.kt` — 归档外层装饰器；`add()` 先调 `archiveLargeMedia(message)` 把超过 1KB 的 Data 转 Local，再把 archived 版本转给 downstream `Memory`；`history()` / `rebuild()` 透传（下游已是 archived 状态）

### 修改

- `agent/core/src/main/kotlin/io/github/yeyi/agent/llm/ChatRequest.kt` — `MediaSource.Local` 加进现有 sealed interface
- `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/Memory.kt` — 加 `mediaArchive` 字段 + `MediaArchive` plain `interface`(`suspend fun store` / `suspend fun resolve`)
- `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/InMemoryMemory.kt` — 实现 `mediaArchive`（内部 `InMemoryMediaArchive` private nested class）
- `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt` — 转发 inner `mediaArchive`
- `agent/core/src/main/kotlin/io/github/yeyi/agent/memory/ReadOnlyMemory.kt` — 转发 inner `mediaArchive`
- `agent/session/src/main/kotlin/io/github/yeyi/agent/session/JsonlBackedMemory.kt` — 加 `mediaArchive` 构造参数(实现 `Memory` 接口新增字段),其他不动
- `agent/session/src/main/kotlin/io/github/yeyi/agent/session/JsonlConversation.kt` — 完全不动(`Memory by innerMemory` 自动转发 `mediaArchive`,`add()` 不掺归档)
- `agent/session/src/main/kotlin/io/github/yeyi/agent/session/SessionRepository.kt` — 统一路径到 `sessions/{accountId}/{sessionId}/`(`memory.jsonl` + `conversations/` + `media/` 三者同级 sibling);`hydrateSession()` 构造链:`FilesystemMediaArchive(getMediaRoot(...))` → `JsonlBackedMemory(getMemoryFile(...), archive)`(archive 注入到最下层)→ `JsonlConversation(getConversationDir(...), innerMemory)`(`Memory by` 透明转发)→ `ArchivingMemory(conversation)`(自身不持 archive,通过 `decorated.mediaArchive` 访问);`deleteSession()` 改为 `getSessionDir(...).deleteRecursively()` 一行清理全部
- `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt` — 加 `modalityAdapter` 必填构造参数 + `buildRequest` 改走 `modalityAdapter.adapt(raw, memory.mediaArchive)`(每次调用传入 archive)
- `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt` — 加 `modalityAdapter()` 设置方法 + `build()` 内默认 `DefaultModalityAdapter()`(无构造参数)
- `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentExtensions.kt` — `toTextMessage` 内部 `describeMediaSource` 的 when 加 `Local -> "local fileId=${fileId.take(8)}"` 分支（占位截断前缀, 跟 `FileId` 同步策略），函数结构不动
- `agent/providers/openai/src/main/kotlin/.../OpenAiMapping.kt` — Local fail-fast
- `agent/providers/anthropic/src/main/kotlin/.../AnthropicMapping.kt` — Local fail-fast

### 测试新增/修改

- `agent/core/src/test/kotlin/io/github/yeyi/agent/ModalityAdapterTest.kt` — adapter 三路分支
- `agent/session/src/test/kotlin/io/github/yeyi/agent/session/ArchivingMemoryTest.kt` — `add()` 1KB 阈值 + `archive.store` 调用次数 + 透传 `history()` / `rebuild()` + `mediaArchive` 字段
- `agent/session/src/test/kotlin/io/github/yeyi/agent/session/JsonlBackedMemoryTest.kt` — 验证 `mediaArchive` 字段返回构造时注入的 archive(其他行为与现有测试一致)
- `agent/session/src/test/kotlin/io/github/yeyi/agent/session/SessionRepositoryTest.kt` — `hydrateSession()` 构造链：`ArchivingMemory(JsonlConversation(JsonlBackedMemory(...)))`；archive 在 per-session `media/` 目录创建（`sessions/{accountId}/{sessionId}/media/`）、`session.memory.mediaArchive` 能 resolve 落盘的 Local；`deleteSession()` 清理后整个 session 目录不存在
- `agent/core/src/test/kotlin/io/github/yeyi/agent/memory/InMemoryMemoryTest.kt` — `mediaArchive` 字段返回内部 archive 实例
- `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentExtensionsTest.kt` — `toTextMessage` Local 分支
- `agent/providers/openai/src/test/kotlin/.../OpenAiMappingTest.kt` — Local fail-fast
- `agent/providers/anthropic/src/test/kotlin/.../AnthropicMappingTest.kt` — Local fail-fast
- `agent/session/src/test/kotlin/io/github/yeyi/agent/session/FilesystemMediaArchiveTest.kt` — store/resolve 往返 + 路径失效语义

---

## 12. 测试策略

### `ModalityAdapterTest`

- 末条 User + Local → `archive.resolve()` 调用一次，该 part 展开为两个 part：`Text("[local] fileId=xxx")` + Data
- 跨 round User + Local → 不调 archive，parts 变为 Text 占位（`[image] local fileId=<前8字符>`，前缀截断）
- 末条 User + Data → 不动
- 末条 User + Http → 不动
- 末条 User + FileId → 不动
- 末条 User parts 顺序保留 → `[Text, Local, Http]` 输入时, Local 展开为 `[Text引用, Data]`, 跟 Http 的相对顺序保持：`[Text, Text引用, Data, Http]`
- 末条 ToolResult 含 media → 拆 text + 合成 User，合成 User 是最后 User
- 含 System/Assistant → 透传
- 跨 round 含多种 media → 都转占位（Http/FileId/Local/Data 各走对应占位分支）

用自定义 `MediaArchive` 实现做 spy(测试里 `adapter.adapt(messages, spyArchive)` 显式传 archive,验证 `resolve` 调用次数和输出 message 结构)。

### `InMemoryMemoryTest`

- `mediaArchive` 字段访问 → 返回内部 `InMemoryMediaArchive` 实例（private，通过行为测试：调 `add` 写入 Local 后 `history` 能取回 Local，调 `mediaArchive.resolve()` 能拿到原始 Data）
- `add()` 任何消息（Data / Http / FileId / Local）→ 原样存储，不做改写

### `FilesystemMediaArchiveTest`（`agent/session` 模块）

- `store()` → `resolve()` → bytes 相等
- `resolve()` 缺失 id → 抛 `IllegalStateException`
- `store()` 同 bytes 不同 id（每次 UUID）
- `init` 时 rootDir 不存在 → 触发 `mkdirs`
- rootDir 失效（agent 重启后）→ `resolve()` 抛 `IllegalStateException`，由 caller 决定恢复策略

### `ArchivingMemoryTest`（`agent/session` 模块）

- `add(User 含 Data base64.length = 1024)` → 透传给下游（mock 验证 `decorated.add` 收到的 message 与入参相等，且 `archive.store` 0 次调用）
- `add(User 含 Data base64.length = 1025)` → `archive.store` 调用 1 次，downstream 收到 message 的 `Data` 已替换为 `Local`
- `add(User 含 Data base64.length = 0)` → 透传给下游（空 base64 不归档），`archive.store` 0 次调用
- `add(ToolResult 含 Data base64.length > 1024)` → 同样归档
- `add(System / Assistant)` → 透传不调 archive
- `history()` / `rebuild()` → 透传给 `decorated`，不触发 archive
- `mediaArchive` 字段 → 经 `Memory by decorated` 转发，与 `decorated.mediaArchive` 同一实例

### `JsonlBackedMemoryTest`（`agent/session` 模块）补充

- 验证 `mediaArchive` 字段返回构造时注入的 archive 实例(其他 `add()` / `history()` / `rebuild()` 行为已有测试覆盖,不变)

### `SessionRepositoryTest`（`agent/session` 模块）补充

- `hydrateSession()` 后 `session.memory` 真实类型是 `ArchivingMemory`，`mediaArchive` 能 resolve 落盘的 Local
- `deleteSession()` 后 `sessions/{accountId}/{sessionId}/` 整个目录不存在（`memory.jsonl` + `conversations/` + `media/` 三块都清理），但 `sessions.jsonl` 索引条目同步移除
- `getSessionDir()` 返回的目录包含三个 sibling 子项（`memory.jsonl` / `conversations/` / `media/`）

### Provider fail-fast 测试

- `OpenAiMappingTest` / `AnthropicMappingTest` 加 Local → `UnsupportedContent` 用例

---

## 13. 风险与权衡

| 风险 | 缓解 |
|---|---|
| `Memory` 接口加 `mediaArchive` 字段破坏现有实现 | 必填字段;archive 实体只注入最下层（`InMemoryMemory` / `JsonlBackedMemory`）;其余装饰器通过 `Memory by` delegate 透明转发;SDK 源码内 `agent/` 下所有 Memory 实现已枚举覆盖（§4）|
| `FilesystemMediaArchive` 路径失效（agent 销毁/重启后 path 失效）| 由 `agent/session` 模块自管：实现内 `mkdirs` 兜底；caller 文档说明路径迁移 / 跨进程语义 |
| `archive.resolve()` 找不到 fileId | 抛 `IllegalStateException`（入参错误, 不包装成 `AgentException`, 让 caller catch 决策）|
| Local ID 是 UUID，序列化进 ChatMessage 后跨设备失效 | 文档说明 Local 默认单进程内引用,跨进程用 `FileId` |
| `ArchivingMemory` 硬编码 1KB 阈值是 magic number | 阈值 = `JsonlConversation.pageSizeThreshold / 10`, 避免单图占满整 page;下沉到 `ARCHIVE_THRESHOLD` 私有 `companion object const`(不再 inline 1024 字面量);如未来真出现反馈需要调整，再考虑提升为构造参数 |
| `FilesystemMediaArchive` 并发 IO（多个 `add()` 同时 `store` 同 fileId / 写一半被覆盖）| `store` / `resolve` 均通过 `kotlinx.coroutines.sync.Mutex.withLock` 序列化(与 `InMemoryMemory` 的线程安全契约一致);类 KDoc 已声明"线程安全:多个并发 add() 调用通过 Mutex 序列化"。`MediaArchive` 接口方法声明为 `suspend` 是为了让实现能用 `withLock`(见 §14 DEV-2)。|

---

## 14. 与初版 spec 的偏离(实施回顾)

实施过程中 3 项偏离,均经用户确认接受:

### DEV-1: `MediaArchive` / `ModalityAdapter` 用 plain `interface` 而非 `fun interface`

**原 spec**:§3.2 写 `fun interface MediaArchive`(2 抽象方法)、§5 写 `fun interface ModalityAdapter`(1 抽象方法)。`fun interface` 仅允许 1 个抽象方法,所以 `MediaArchive` 用 `fun interface` 会编译失败。`ModalityAdapter` 当时 1 个抽象方法本可 `fun interface`,但为 future extensibility(预留 `resolveLocal` / `describePlaceholders` 等)统一改 plain `interface`。

**影响**:caller 不能用 SAM lambda 直接构造 `ModalityAdapter`(如 `modalityAdapter = { msgs, arc -> ... }`),需显式 `class MyAdapter : ModalityAdapter`。本项目无此类用法,影响为零。

**修正**:§3.2 / §5 / §11 已改为 plain `interface` 描述。

### DEV-2: `MediaArchive.store` / `resolve` / `ModalityAdapter.adapt` 改为 `suspend`

**原 spec**:所有方法声明为 `fun`(非 `suspend`)。

**触发**:final review (commit `649cc02`) 给 `FilesystemMediaArchive` 加 `Mutex.withLock` 保证线程安全时,`Mutex.withLock` 必须在 coroutine 上下文内,需要接口方法 `suspend`。cascade 路径:`MediaArchive.store/resolve` → `InMemoryMediaArchive.store/resolve` → `ModalityAdapter.adapt/resolveLocal` → `ArchivingMemory.archiveLargeMedia/archiveIfLarge` + 4 个 test fixture 全部改为 `suspend`。`ReActAgent.buildRequest` 自身已是 suspend,无 caller 受影响。

**影响**:`MediaArchive` 自定义实现(若 caller 已基于 v2 spec 实现)需加 `suspend` 修饰符。`InMemoryMediaArchive` + `FilesystemMediaArchive` + 4 个测试 spy 同步更新。

**修正**:§3.2 / §5 / §11 已加 `suspend` 说明;§13 风险表新增 FilesystemMediaArchive 线程安全行,引用此偏离。

### DEV-3: T1 commit body claim 误述(Local 分支同 commit 已加,非下游后续任务)

**原 commit `e123829`**:commit body 写 "下游消费者在后续任务添加 when 分支" —— 但 Kotlin sealed interface 编译时强制 exhaustiveness,`MediaSource.Local` 加进 sealed 后 `AgentExtensions.describeMediaSource` + `OpenAiMapping` / `AnthropicMapping` 的 `when (source)` 必须同 commit 补全 Local 分支(否则 `agent/core` + `agent/providers/*` 编译失败)。所以 T1 commit 实际上**已**在 `AgentExtensions.kt` + `OpenAiMapping.kt` + `AnthropicMapping.kt` 3 处加 `is MediaSource.Local -> ...` 分支。

**影响**:commit body 描述与实际不符,reviewer/reader 可能误以为 Local 分支是后续 T10 才加的,导致重复审查。T10 实际只补了 4 条测试,生产代码未动。

**修正**:ledger (`.superpowers/sdd/modality-media-archive-ledger.md`) 已记录此事实;T4 / T10 实施 brief 已明确"Local 分支已存在,不要重添",避免重复工作。