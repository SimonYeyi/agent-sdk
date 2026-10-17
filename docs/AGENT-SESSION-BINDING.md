# Session 与 Agent 绑定设计

## 背景

最初发现 Session 恢复时只能恢复 Memory，但 Agent 相关的配置（provider、capabilities 等）没有被保留。

需要让 Session 绑定 Agent，使得会话恢复时能自动重建 Agent 实例。

## 模块划分

```
Session 模块（agent/session）
├── AgentReference     # Session 绑定的 Agent 投影
└── Session            # 会话，包含 AgentReference

config 模块（agent/config）
├── AgentDefinition    # Agent 的完整声明
└── AgentRegistry      # 管理 AgentDefinition 的注册与查找
```

## 核心类型

### AgentReference（Session 模块）

```kotlin
@Serializable
data class AgentReference(
    val name: String,
    val version: Int = 0  // 占位，默认 0
)
```

Session 绑定的 Agent 投影，仅包含引用信息。

### Session（Session 模块）

```kotlin
@Serializable
data class Session(
    val id: String,
    val accountId: String,
    val name: String,
    val agent: AgentReference,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    @Transient private val _memory: Memory? = null,
    @Transient private val _conversation: Conversation? = null
) {
    val memory: Memory get() = _memory ?: throw IllegalStateException("Memory not found")
    val conversation: Conversation get() = _conversation ?: throw IllegalStateException("Conversation not found")
}
```

### AgentDefinition（config 模块）

```kotlin
public data class AgentDefinition(
    val name: String,
    val version: Int = 0,
    val persona: String,
    val provider: Provider,
    val maxIterations: Int = 20,
    val maxMemoryRounds: Int = 20,
    val capabilities: List<Capability>,
) {
    public data class Provider(
        val name: String,        // "anthropic" | "openai"
        val baseUrl: String,
        val model: String,
    )

    public data class Capability(
        val type: Type,
        val names: List<String>,
    ) {
        public enum class Type {
            TOOL, LAZY_TOOL, TOOLSET, SKILL, SUBAGENT,
        }
    }
}

public fun AgentDefinition.spawn(
    memory: Memory,
    providerApiKey: String,
    toolRegistry: ToolRegistry? = null,
    lazyToolRegistry: LazyToolRegistry? = null,
    toolsetRegistry: ToolsetRegistry? = null,
    skillRegistry: SkillRegistry? = null,
    subagentRegistry: SubagentRegistry? = null,
): Agent
```

**设计说明：**
- `provider.name` 用 String（provider 类型有限，暂不枚举）
- `capability.type` 用 `Capability.Type` 枚举，类型安全

Agent 的完整声明，包含构建 Agent 所需的所有配置信息（不含敏感信息如 apiKey）。

### AgentRegistry（config 模块）

```kotlin
public class AgentRegistry {
    public fun register(definition: AgentDefinition)
    public fun find(name: String): AgentDefinition?
}
```

管理 AgentDefinition 的注册与查找。`find` 返回可空，查不到时返回 null。

## 创建会话流程

```
1. 应用层：确定要创建的 Agent 类型（如 "smartHome"）
2. AgentRegistry.find("smartHome") → AgentDefinition
3. 创建 Session，传入 AgentReference(name = "smartHome")
4. 调用 definition.spawn(memory, providerApiKey) 构建 Agent
```

```kotlin
// 示例
val definition = agentRegistry.find("smartHome") ?: error("Agent not found")
val session = sessionManager.create(
    accountId = "user1",
    sessionName = "我的智能家居",
    agent = AgentReference(name = "smartHome")
)
val agent = definition.spawn(session.memory, providerApiKey = "sk-xxx")
```

## 恢复会话流程

```
1. SessionManager.get(accountId, sessionId) → Session
2. Session.agent.name → "smartHome"
3. AgentRegistry.find("smartHome") → AgentDefinition
4. 调用 definition.spawn(session.memory, apiKey) 构建 Agent
```

```kotlin
// 示例
val session = sessionManager.get("user1", "session-id")
val definition = agentRegistry.find(session.agent.name) ?: error("Agent not found")
val agent = definition.spawn(session.memory, providerApiKey = "sk-xxx")
```

## 设计原则

1. **模块解耦**：Session 模块与 config 模块独立，不相互依赖
2. **投影引用**：Session 只持有 AgentReference，不持有完整声明
3. **敏感信息外置**：apiKey 等敏感信息由调用方在构建时注入，不存储
4. **版本预留**：AgentReference.version 和 AgentDefinition.version 占位，默认 0，暂不涉及版本概念
