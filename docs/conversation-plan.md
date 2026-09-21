# Conversation 完整聊天记录方案

## 背景

当前 Memory 会进行压缩，压缩后原始对话丢失。用户期望在界面上看到完整的对话历史。

## 设计思路

- Memory：负责给 Agent 提供上下文，会压缩
- Conversation：负责存储完整对话历史，不压缩
- UI 从 Conversation 获取完整历史，Agent 从 Memory 获取压缩后上下文

## 文件结构

```
conversations/
  <sessionId>/
    page1.jsonl   # 最旧
    page2.jsonl
    page3.jsonl   # 最新（page 号越大越新）
```

## Conversation 接口

```kotlin
public interface Conversation {
    fun history(page: Int): List<MemoryEntry>
    // page = PAGE_ALL(0)：返回所有消息
    // page = 1：最新一页，page = 2：更旧一页...
}
```

## 翻页算法

### 核心问题

新消息创建新 page 后，用户正在浏览的历史页会不会错位？

### 解决方案：startPage 锚定

用户首次加载 page 1（最新）时，记录 `startPage = maxPage`。

之后的翻页都基于 `startPage` 计算，不再受新 page 影响。

用户翻回 page 1 时，重置 `startPage = maxPage`。

### 公式

```
文件页号 = startPage - (用户页号 - 1)
```

### 场景演练

**初始状态**：
- 文件：page1, page2, page3, page4（page4 最新）
- maxPage = 4, startPage = 4

| 用户操作 | 计算 | 实际文件 |
|---------|------|---------|
| 加载 page 1 | 4 - (1-1) = 4 | page4 ✓ |
| 翻到 page 2 | 4 - (2-1) = 3 | page3 ✓ |
| 翻到 page 3 | 4 - (3-1) = 2 | page2 ✓ |
| 翻到 page 4 | 4 - (4-1) = 1 | page1 ✓ |

**新消息来了，创建 page 5**：
- maxPage = 5
- startPage 仍是 4（不变）

| 用户操作 | 计算 | 实际文件 |
|---------|------|---------|
| 翻到 page 4 | 4 - (4-1) = 1 | page1 ✓ |
| 翻到 page 5 | 4 - (5-1) = 0 | 无效 ✓ |

**用户回到 page 1（最新）**：
- 触发重置：startPage = maxPage = 5
- 后续翻页基于新的 startPage

| 用户操作 | 计算 | 实际文件 |
|---------|------|---------|
| page 1 | 5 - (1-1) = 5 | page5 ✓ |
| page 2 | 5 - (2-1) = 4 | page4 ✓ |

## JsonlConversation 实现

### 成员变量
```kotlin
private val json = Json { ... }
private var maxPage: Int = 0          // 当前最大文件页号
private var startPage: Int = 0        // 用户首次加载时的锚定页号
```

### 初始化
- 扫描目录找出当前最大 page 号
- 如果目录为空，创建 page1.jsonl

### JsonlMemory.add(entry) 逻辑
1. 落盘追加到 memory.jsonl（Memory 契约持久化）
2. 同步调用 conversation.append(entry) 作为分页副作用
3. append 检查 maxPage 对应文件是否达到大小阈值
4. 未达阈值 → 追加到 page{maxPage}.jsonl
5. 已达阈值 → maxPage++，创建新 page{maxPage}.jsonl，追加到新文件

### history(page) 逻辑
```kotlin
override suspend fun history(page: Int): List<MemoryEntry> {
    // PAGE_ALL = 0:按页码数字排序遍历,返回全部
    // page <= 0:返回空
    // page == 1:重置锚点 startPage = maxPage
    // 实际页 = startPage - (page - 1),<= 0 返回空
}
```

> 注意:PAGE_ALL 全量读取按**页码数字**排序(`pageNumberOf(it) ?: 0`),
> 而非文件名字典序——否则 page10.jsonl 会排在 page2.jsonl 前面。

## Session 修改

```kotlin
@Serializable
public data class Session(
    val id: String,
    val accountId: String,
    val name: String,
    val createdAt: kotlinx.datetime.Instant,
    val lastActiveAt: kotlinx.datetime.Instant,
    @Transient private val _memory: Memory? = null,
    @Transient private val _conversation: Conversation? = null
) {
    val memory: Memory get() = _memory!!
    val conversation: Conversation get() = _conversation!!
}
```

## SessionRepository 修改

### 文件结构
```
sessions/
  <accountId>/
    sessions.jsonl
    memories/
      <sessionId>.jsonl        # Memory 持久化
    conversations/
      <sessionId>/
        page1.jsonl            # 分页对话历史
        page2.jsonl
```

### getConversationDir 方法
```kotlin
private fun getConversationDir(accountId: String, sessionId: String): File {
    return File(File(getUserDir(accountId), "conversations"), sessionId).also {
        it.mkdirs()
    }
}
```

### createSession
```kotlin
val archive = FilesystemMediaArchive(getMediaDir(accountId, id))
val conversation = JsonlConversation(getConversationDir(accountId, id))
val memory = JsonlMemory(getMemoryFile(accountId, id), archive, conversation)
Session(
    ...
    _memory = memory,
    _conversation = conversation
)
```

### findSessions
```kotlin
val archive = FilesystemMediaArchive(getMediaDir(accountId, session.id))
val conversation = JsonlConversation(getConversationDir(accountId, session.id))
val memory = JsonlMemory(getMemoryFile(accountId, session.id), archive, conversation)
session.copy(
    _memory = memory,
    _conversation = conversation
)
```

### deleteSession
- 删除 memory 文件
- 删除 conversation 目录（递归删除所有 page 文件）

## 数据流

```
Agent 写入 → memory.add(entry)
    ↓
JsonlMemory.add(entry)
    ↓
1. 落盘 memory.jsonl（Memory 契约持久化）
2. conversation.append(entry)   # 分页副作用
    ↓
JsonlConversation.append(entry)
    ↓
3. 追加到当前 page 文件（超阈值创建新 page）
    ↓
UI.conversation.history(PAGE_ALL) → 所有历史（按页码数字排序）
UI.conversation.history(1) → 最新一页
UI.conversation.history(2) → 更旧一页
```

## 变更文件

- `session/src/main/kotlin/io/github/yeyi/agent/session/Conversation.kt` - 接口为 `history(page: Int)`，常量 `PAGE_ALL = 0`
- `session/src/main/kotlin/io/github/yeyi/agent/session/JsonlConversation.kt` - 支持分页存储
- `session/src/main/kotlin/io/github/yeyi/agent/session/Session.kt` - 已有
- `session/src/main/kotlin/io/github/yeyi/agent/session/SessionRepository.kt` - 适配目录结构
