# Conversation 完整聊天记录方案

## 背景

当前 Memory 会进行压缩，压缩后原始对话丢失。用户期望在界面上看到完整的对话历史。

## 设计思路

- Memory：负责给 Agent 提供上下文，会压缩
- Conversation：负责存储完整对话历史，不压缩
- UI 从 Conversation 获取完整历史，Agent 从 Memory 获取压缩后上下文

## 实现方案

### 1. Conversation 接口

```kotlin
public interface Conversation {
    fun messages(): List<ChatMessage>
}
```

### 2. JsonlConversation 实现类

装饰 `innerMemory`，实现完整历史存储：

```kotlin
class JsonlConversation(
    private val conversationFile: File,
    private val innerMemory: Memory
) : Conversation, Memory by innerMemory {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            polymorphic(ChatMessage::class) {
                subclass(ChatMessage.System::class)
                subclass(ChatMessage.User::class)
                subclass(ChatMessage.Assistant::class)
                subclass(ChatMessage.ToolResult::class)
            }
        }
    }

    override suspend fun add(message: ChatMessage) {
        // 1. 写完整历史到 conversation 文件
        conversationFile.appendText(json.encodeToString(message) + "\n")
        // 2. 让被装饰的 Memory 处理（压缩等）
        innerMemory.add(message)
    }

    override fun messages(): List<ChatMessage> {
        return conversationFile.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<ChatMessage>(it) }
    }
}
```

### 3. Session 修改

新增 `conversation` 属性，私有化处理（与 `_memory` 一致）：

```kotlin
@Serializable
public data class Session(
    val id: String,
    val accountId: String,
    val name: String,
    val createdAt: kotlinx.datetime.Instant,
    val lastActiveAt: kotlinx.datetime.Instant,
    @Transient private val _conversation: Conversation? = null,  // 新增，私有化
    @Transient private val _memory: Memory? = null
) {
    public val memory: Memory get() = _memory!!
    public val conversation: Conversation get() = _conversation!!
}
```

### 4. SessionRepository 修改

**文件结构**：
```
sessions/
  <accountId>/
    sessions.jsonl
    memories/
      <sessionId>.jsonl        # Memory 持久化（会被压缩）
    conversations/
      <sessionId>.jsonl        # 完整对话历史
```

**新增方法**：
```kotlin
private fun getConversationFile(accountId: String, sessionId: String): File {
    return File(File(getUserDir(accountId), "conversations"), "$sessionId.jsonl").also {
        it.parentFile.mkdirs()
    }
}
```

**createSession**：
```kotlin
val rawMemory = JsonlBackedMemory(getMemoryFile(accountId, id))
val conversation = JsonlConversation(getConversationFile(accountId, id), rawMemory)
Session(
    id = id,
    accountId = accountId,
    name = sessionName,
    createdAt = now,
    lastActiveAt = now,
    conversation = conversation,
    _memory = conversation
)
```

**findSessions**：
```kotlin
val rawMemory = JsonlBackedMemory(getMemoryFile(accountId, session.id))
val conversation = JsonlConversation(getConversationFile(accountId, session.id), rawMemory)
session.copy(
    conversation = conversation,
    _memory = conversation
)
```

**deleteSession**：新增删除 conversation 文件

## 数据流

```
Agent.add(message)
    ↓
Conversation.add(message)
    ↓
1. conversationFile.appendText(message)  # 写完整历史
2. innerMemory.add(message)               # 被压缩记忆处理
    ↓
UI.history() → Memory.history() → 压缩后历史（Agent 用）
UI.messages() → Conversation.messages() → 完整历史（UI 用）
```

## 文件清单

- 新建：`session/src/main/kotlin/io/github/yeyi/agent/session/Conversation.kt`
- 新建：`session/src/main/kotlin/io/github/yeyi/agent/session/JsonlConversation.kt`
- 修改：`session/src/main/kotlin/io/github/yeyi/agent/session/Session.kt`
- 修改：`session/src/main/kotlin/io/github/yeyi/agent/session/SessionRepository.kt`
