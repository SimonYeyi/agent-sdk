# RoundsBoundedMemory 设计文档

## 目标

提供多轮会话 Memory 管理能力，支持动态压缩，解决长对话导致的 token 膨胀和上下文窗口耗尽问题。

## 核心概念

**轮次（Round）：** 一轮 = User 消息 + Assistant 消息 + 后续 ToolResults

**摘要（Summary）：** 被压缩轮次的 LLM 生成摘要，作为 User 消息插入 memory 顶部

## 内存结构

```
memory: [
    System(persona),                        // Builder 注入，不存 memory
    User([SUMMARY]...[/SUMMARY]),          // 压缩后的摘要列表
    round(K-30%*K+1),                       // 最早保留的轮次
    ...
    round(K)                                // 最近一轮
]
```

## 关键设计

### 1. Memory 接口扩展

```kotlin
public interface Memory {
    public suspend fun add(message: ChatMessage)
    public suspend fun history(): List<ChatMessage>
    public suspend fun rebuild(messages: List<ChatMessage>)  // 新增：用新列表重建存储
}
```

### 2. RoundsBoundedMemory 装饰器

**位置：** `agent/src/main/kotlin/io/github/yeyi/agent/memory/RoundsBoundedMemory.kt`

**职责：** 包装底层 Memory（InMemory / JsonlBacked），内部管理压缩逻辑

**构造参数：**

| 参数 | 来源 | 说明 |
|------|------|------|
| `underlying` | memory() 传入 | 底层 Memory 实现 |
| `llmProvider` | AgentBuilder 注入 | 用于生成摘要 |
| `maxRounds` | memory() 配置 | 最大保留轮数 |

### 3. 内部固定参数

| 参数 | 值 | 说明 |
|------|------|------|
| `retainRatio` | 0.3 | 压缩后保留 30% 轮次 |
| `maxSummaries` | 10 | Summary 上限，超出触发二次压缩 |

### 4. 约束条件

- `underlying` 不可以是 `ReadOnlyMemory`，因为压缩时需要调用 `rebuild()` 重建存储

### 5. Summary 数据结构

```kotlin
@Serializable
private data class Summary(
    val content: String,      // 摘要内容
    val timestamp: String      // ISO 时间戳
)

@Serializable
private data class SummaryContainer(
    val summaries: List<Summary>
)
```

### 6. 摘要存储格式

```kotlin
// 写入 memory
User("[SUMMARY]<json>[/SUMMARY]")

// json 结构
{"summaries":[{"content":"...","timestamp":"..."}]}
```

## 压缩流程

### Step 1 — 触发条件

添加新消息后，统计当前轮次数。

```kotlin
// 轮次计算（不包含 Summary）
fun countRounds(messages: List<ChatMessage>): Int =
    messages.count { it is ChatMessage.User && !it.isSummary() }
```

### Step 2 — 轮次压缩

```
触发条件: currentRounds > maxRounds

计算:
- 保留窗口 = maxRounds * retainRatio = 20 * 0.3 = 6 轮（最近）
- 参与压缩 = currentRounds - 保留窗口（最早的部分）

操作:
1. 从后往前遍历确定保留窗口索引（最近 maxRounds * retainRatio 轮）
2. 压缩窗口 = 全部消息 - 保留窗口 - Summary 消息
3. 调用 LLM 生成摘要（temperature=0.3）
4. 调用 underlying.rebuild() 重建存储（Summary + 保留的轮次）
```

### Step 3 — Summary 二次压缩

```
触发条件: summaries.size >= maxSummaries (10条)

操作:
1. 保留最近 30% = 3条
2. 其余 7条 → LLM 再次压缩成 1条新 Summary
3. 最终: 1条新 Summary + 3条保留 = 4条
```

### Step 4 — 最终 memory 结构

```
[System(persona), User([SUMMARY]), round...]
```

## 持久化恢复

### 首次加载

```kotlin
class RoundsBoundedMemory(...) {
    private var summaries: MutableList<Summary>? = null

    override suspend fun history(): List<ChatMessage> {
        summaries ??= restoreSummaries(underlying.history())
        // ...
    }

    private fun restoreSummaries(history: List<ChatMessage>): MutableList<Summary> {
        val summaryMsg = history.firstOrNull()
            ?.takeIf { it.isSummaryMessage() }
            ?.let { it as ChatMessage.User }
            ?: return mutableListOf()

        val json = summaryMsg.content
            .removePrefix("[SUMMARY]")
            .removeSuffix("[/SUMMARY]")

        return Json.decodeFromString<SummaryContainer>(json).summaries.toMutableList()
    }

    private fun ChatMessage.isSummaryMessage(): Boolean =
        this is ChatMessage.User && content.startsWith("[SUMMARY]")
}
```

### 判断逻辑

- `summaries == null` → 首次加载，从 history 恢复
- `summaries != null` → 正常走内存操作

## 文件结构

```
agent/src/main/kotlin/io/github/yeyi/agent/
  memory/
    Memory.kt                      # 接口（含 rebuild 方法）
    InMemoryMemory.kt              # 现有实现（需实现 rebuild）
    RoundsBoundedMemory.kt          # 新增：轮次有界 Memory（内含 private Summary 数据类）
```

## 实施步骤

1. 扩展 `Memory` 接口 — 增加 `rebuild()` 方法
2. 实现 `rebuild()` — 在 InMemoryMemory、JsonlBackedMemory 等底层实现中
3. 新增 `RoundsBoundedMemory.kt` — 装饰器实现（内含 private Summary 数据类）
4. 修改 `AgentBuilder.memory()` — 支持 maxRounds 参数，注入 llmProvider
5. 新增单元测试 — 压缩逻辑、恢复逻辑、边界条件

## 使用方式

```kotlin
val agent = agent {
    memory(session.memory, maxRounds = 20)  // maxRounds 跟随 memory 配置
    llmProvider(provider)                    // 传入 LLM 用于压缩
}

// 外部无感知，内部自动管理压缩
val result = agent.run("你好")
```

**API 设计：**
- 默认 maxRounds = 20，超出则触发压缩

## 优点

- **有界存储：** memory 始终有硬上界，不会无限膨胀
- **透明压缩：** 外部无需感知压缩逻辑
- **分层摘要：** 支持多级压缩，平衡精度和 token 消耗
- **持久化兼容：** 从磁盘恢复时正确还原摘要状态
