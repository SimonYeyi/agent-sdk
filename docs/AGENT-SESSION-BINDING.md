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
    val tools: List<Tool> = emptyList(),
    val toolsets: List<String> = emptyList(),
    val skills: List<String> = emptyList(),
    val subagents: List<String> = emptyList(),
) {
    public data class Provider(
        val name: String,        // "anthropic" | "openai"
        val baseUrl: String,
        val model: String,
        val apiKey: String
    )
    
    public data class Tool(
        val name: String,
        val lazy: Boolean = false,
        val lazyLevel: LazyLevel? = null
    ) {
        public enum class LazyLevel {
            SCHEMA, TOOL
        }
    }
}

public fun AgentDefinition.spawn(
    memory: Memory,
    toolRegistry: ToolRegistry,
    lazyToolRegistry: LazyToolRegistry,
    toolsetRegistry: ToolsetRegistry,
    skillRegistry: SkillRegistry,
    subagentRegistry: SubagentRegistry
): Agent
```

Agent 的完整声明。`spawn()` 根据声明的 tools/skills/subagents/toolsets 从传入的 registry 中过滤同名项，构建 Agent。

### AgentRegistry（config 模块）

```kotlin
public class AgentRegistry {
    public fun register(definition: AgentDefinition)
    public fun find(name: String): AgentDefinition?
}
```

管理 AgentDefinition 的注册与查找。`find` 返回可空，查不到时返回 null。

## TOML 配置示例

```toml
[agent.smartHome]
persona = "你是智能家居助手..."
maxIterations = 20
maxMemoryRounds = 20

[agent.smartHome.provider]
type = "anthropic"
baseUrl = "https://api.anthropic.com"
model = "claude-3-5-sonnet"
apiKey = "sk-xxx"

tools = [
    "getWeather",
    { name = "controlTV", lazy = true, lazyLevel = "tool" },
    { name = "controlAC", lazy = true, lazyLevel = "schema" }
]
toolsets = ["iotToolset"]
skills = ["weatherSkill", "homeSkill"]
subagents = ["helpSubagent"]
```

## 创建会话流程

```
1. 应用层：确定要创建的 Agent 类型（如 "smartHome"）
2. AgentRegistry.find("smartHome") → AgentDefinition
3. 创建 Session，传入 AgentReference(name = "smartHome")
4. 调用 definition.spawn(memory, registries) 构建 Agent
```

```kotlin
// 示例
val definition = agentRegistry.find("smartHome") ?: error("Agent not found")
val session = sessionManager.create(
    accountId = "user1",
    sessionName = "我的智能家居",
    agent = AgentReference(name = "smartHome")
)
val agent = definition.spawn(
    session.memory,
    toolRegistry,
    lazyToolRegistry,
    toolsetRegistry,
    skillRegistry,
    subagentRegistry
)
```

## 恢复会话流程

```
1. SessionManager.get(accountId, sessionId) → Session
2. Session.agent.name → "smartHome"
3. AgentRegistry.find("smartHome") → AgentDefinition
4. 调用 definition.spawn(session.memory, registries) 构建 Agent
```

```kotlin
// 示例
val session = sessionManager.get("user1", "session-id")
val definition = agentRegistry.find(session.agent.name) ?: error("Agent not found")
val agent = definition.spawn(
    session.memory,
    toolRegistry,
    lazyToolRegistry,
    toolsetRegistry,
    skillRegistry,
    subagentRegistry
)
```

## 设计原则

1. **模块解耦**：Session 模块与 config 模块独立，不相互依赖
2. **投影引用**：Session 只持有 AgentReference，不持有完整声明
3. **扁平化配置**：tools/skills/subagents/toolsets 各管各的，无 capabilities 命名空间
4. **统一 tool 声明**：lazy/lazyLevel 是 Tool 的属性，配置中统一用 tools 字段
5. **版本预留**：AgentReference.version 和 AgentDefinition.version 占位，默认 0，暂不涉及版本概念
6. **Registry 外置**：Registry 是运行时 pool，spawn 时注入，AgentDefinition 只存 name 列表
