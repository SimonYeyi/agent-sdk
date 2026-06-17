# Session 模块实施文档

## 设计目标

提供多会话管理能力，支持消息持久化到 jsonl 文件，实现内存+磁盘二级缓存。

## 文件结构

```
{sessionParent}/
  sessions/
    {accountId}/
      sessions.jsonl           # Session 元数据列表
      memories/
        {sessionId}.jsonl     # ChatMessage 列表
```

## 数据模型

```kotlin
@Serializable
public data class Session(
    val id: String,
    val accountId: String,
    val name: String,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    @Transient private val _memory: Memory? = null
) {
    val memory: Memory get() = _memory!!
}
```

## 核心组件

### 1. JsonlBackedMemory

- 内存缓存 `List<ChatMessage>`
- 初始化时创建父目录
- `add()`: 写内存 + 异步追加到 jsonl（FileOutputStream 自动创建目录和文件）
- `history()`: 内存为空从 jsonl 加载，否则返回内存
- `clear()`: 清内存 + 清 jsonl 文件

### 2. SessionRepository

```kotlin
public class SessionRepository(sessionParent: File) {
    public fun createSession(accountId: String, sessionName: String): Session
    public fun findSessions(accountId: String): List<Session>
    public fun findSession(accountId: String, sessionId: String): Session?
    public fun saveSession(session: Session)
    public fun deleteSession(accountId: String, sessionId: String)
}
```

### 3. SessionManager

```kotlin
public class SessionManager(sessionParent: File) {
    public suspend fun create(accountId: String, sessionName: String): Session
    public suspend fun get(accountId: String, sessionId: String): Session
    public suspend fun delete(accountId: String, sessionId: String)
    public suspend fun list(accountId: String): List<Session>
}
```

## 使用方式

```kotlin
val sessionManager = SessionManager(context.dataDir)
val session = sessionManager.create("user1", "我的助手")

val agent = agent {
    memory(session.memory)
    llmProvider(provider)
}

val result = agent.run("你好")
```

## 实施步骤

1. 创建 `session/build.gradle.kts`，依赖 agent 模块
2. 新增 `Session.kt` - 数据类定义
3. 新增 `JsonlBackedMemory.kt` - 二级缓存 Memory 实现
4. 新增 `SessionRepository.kt` - 文件 I/O
5. 新增 `SessionManager.kt` - 业务逻辑
6. 新增单元测试

## 修改说明

- `ChatMessage.kt` - 子类添加 `@Serializable` 注解支持序列化
- `JsonlBackedMemory.kt` - 异步追加写入
- `libs.versions.toml` - 添加 kotlinx-datetime 依赖