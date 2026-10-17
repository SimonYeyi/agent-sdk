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

## 依赖扩展

### agent/capability/.../CapabilityRegistry.kt

新增 `getOrNull` 方法：

```kotlin
public interface CapabilityRegistry<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext> {
    // ... 现有方法 ...

    /** 按名称查找，找不到返回 null。 */
    public fun getOrNull(name: String): C?
}

public class DefaultCapabilityRegistry<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    override val capabilityType: String
) : CapabilityRegistry<C, T, Ctx> {
    // ... 现有实现 ...

    override fun getOrNull(name: String): C? = capabilities[name]
}
```

### agent/config/.../LoggingExtensions.kt

新建文件：

```kotlin
package io.github.yeyi.agent.config

import io.github.yeyi.agent.log.LoggingTagged

internal val log = LoggingTagged("config")
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

config 模块的日志扩展：

```kotlin
// io.github.yeyi.agent.config.LoggingExtensions.kt
package io.github.yeyi.agent.config

import io.github.yeyi.agent.log.LoggingTagged

internal val log = LoggingTagged("config")
```

```kotlin
public data class AgentDefinition(
    val name: String,
    val version: Int = 0,
    val persona: String,
    val provider: Provider,
    val maxIterations: Int = 20,
    val maxMemoryRounds: Int = 20,
    val tools: List<String> = emptyList(),
    val toolsets: List<String> = emptyList(),
    val mcps: List<Mcp> = emptyList(),
    val skills: List<String> = emptyList(),
    val subagents: List<String> = emptyList(),
) {
    public data class Provider(
        val name: String,        // "anthropic" | "openai"
        val baseUrl: String,
        val model: String,
        val apiKey: String
    )

    public data class Mcp(
        val name: String,
        val description: String,
        val transport: Transport
    ) {
        public sealed interface Transport {
            public data class Stdio(
                val command: String,
                val args: List<String> = emptyList(),
                val env: Map<String, String> = emptyMap()
            ) : Transport

            public data class Http(
                val url: String,
                val headers: Map<String, String> = emptyMap()
            ) : Transport
        }
    }
}

public fun AgentDefinition.spawn(
    memory: Memory,
    toolRegistry: ToolRegistry? = null,
    lazyToolRegistry: LazyToolRegistry? = null,
    toolsetRegistry: ToolsetRegistry? = null,
    skillRegistry: SkillRegistry? = null,
    subagentRegistry: SubagentRegistry? = null
): Agent {
    // 1. 从各 registry 中过滤出声明的项
    // tools 列表优先从 lazyToolRegistry 查找，未找到则从 toolRegistry 查找
    val filteredToolRegistry = ToolRegistry()
    val filteredLazyToolRegistry = LazyToolRegistry()
    tools.forEach { name ->
        lazyToolRegistry?.getOrNull(name)?.let {
            filteredLazyToolRegistry.register(it)
        } ?: toolRegistry?.getOrNull(name)?.let {
            filteredToolRegistry.register(it)
        } ?: log.warn("Runtime Tool not found: $name")
    }

    val filteredToolsetRegistry = ToolsetRegistry().apply {
        toolsets.forEach { name ->
            toolsetRegistry?.getOrNull(name)?.let { register(it) }
                ?: log.warn("Runtime Toolset not found: $name")
        }
    }

    val filteredSkillRegistry = SkillRegistry().apply {
        skills.forEach { name ->
            skillRegistry?.getOrNull(name)?.let { register(it) }
                ?: log.warn("Runtime Skill not found: $name")
        }
    }

    val filteredSubagentRegistry = SubagentRegistry().apply {
        subagents.forEach { name ->
            subagentRegistry?.getOrNull(name)?.let { register(it) }
                ?: log.warn("Runtime Subagent not found: $name")
        }
    }

    // 2. 创建 MCP Registry 并注册声明的 MCP
    val mcpRegistry = McpRegistry(
        toolsetRegistry = filteredToolsetRegistry,
        clientInfo = ClientInfo("agent", version.toString())
    )
    mcps.forEach { mcpConfig ->
        val mcp = createMcp(mcpConfig)
        mcpRegistry.register(mcp)
    }

    // 3. 构建 LLM Provider
    val llmProvider = createLlmProvider(provider)

    // 4. 构建 Agent
    return agent {
        persona(Persona(persona))
        llmProvider(llmProvider)
        memory(memory, maxMemoryRounds)
        maxIterations(maxIterations)
        tools(filteredToolRegistry)
        lazyTools(filteredLazyToolRegistry)
        toolsets(filteredToolsetRegistry)
        skills(filteredSkillRegistry)
        subagents(filteredSubagentRegistry)
    }
}

private fun createLlmProvider(provider: Provider): LlmProvider {
    return when (provider.name) {
        "anthropic" -> AnthropicProvider(
            baseUrl = provider.baseUrl,
            model = provider.model,
            apiKey = provider.apiKey
        )
        "openai" -> OpenAiProvider(
            baseUrl = provider.baseUrl,
            model = provider.model,
            apiKey = provider.apiKey
        )
        else -> error("Unknown provider: ${provider.name}")
    }
}

private fun createMcp(config: AgentDefinition.Mcp): Mcp {
    return object : Mcp() {
        override val name: String = config.name
        override val description: String = config.description
        override val client: McpClient = McpClient(
            when (val transport = config.transport) {
                is AgentDefinition.Mcp.Transport.Stdio -> StdioTransport(
                    command = listOf(transport.command) + transport.args,
                    workingDirectory = null
                )
                is AgentDefinition.Mcp.Transport.Http -> SseTransport(
                    endpoint = transport.url,
                    httpHeaders = transport.headers
                )
            }
        )
    }
}

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
# MCP 模板定义（顶层）
# description 必填，会被模型识别用于选择合适的 MCP 工具
[mcp.filesystem]
description = "提供文件系统操作能力，包括读取、写入、列出目录等"
transport = "stdio"
command = "npx"
args = ["-y", "@modelcontextprotocol/server-filesystem", "/tmp"]

[mcp.httpMcp]
description = "远程 HTTP MCP 服务"
transport = "http"
url = "https://mcp.example.com"
headers = { Authorization = "Bearer xxx" }

# Agent 定义
[agent.smartHome]
persona = "你是智能家居助手..."
maxIterations = 20
maxMemoryRounds = 20

[agent.smartHome.provider]
name = "anthropic"
baseUrl = "https://api.anthropic.com"
model = "claude-3-5-sonnet"
apiKey = "sk-xxx"

tools = ["getWeather", "controlTV", "controlAC"]
toolsets = ["iotToolset"]
skills = ["weatherSkill", "homeSkill"]
subagents = ["helpSubagent"]
mcps = ["filesystem", "httpMcp"]
```

### 配置解析流程

```
1. 解析 [mcp.xxx] 顶层定义 → Map<String, Mcp>
   - transport = "stdio" → Mcp.Transport.Stdio(command, args, env)
   - transport = "http"  → Mcp.Transport.Http(url, headers)
2. 解析 [agent.xxx] 配置块
3. agent.mcps = ["filesystem", "httpMcp"] → 引用顶层 MCP 模板
4. 解析时将引用替换为完整 Mcp 对象
5. 最终得到完整 AgentDefinition
```

**伪代码示例：**

```kotlin
fun parseAgentDefinition(toml: Toml): AgentDefinition {
    val mcpTemplates = toml.getTables("mcp").associate { table ->
        table.name!! to parseMcp(table)
    }

    val agentToml = toml.getTable("agent.smartHome")
    val mcpRefs = agentToml.getList<String>("mcps") ?: emptyList()
    val mcps = mcpRefs.map { ref ->
        mcpTemplates[ref] ?: error("MCP template not found: $ref")
    }

    return AgentDefinition(
        ...
        mcps = mcps
    )
}
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
3. **扁平化配置**：tools/skills/subagents/toolsets/mcps 各管各的，无 capabilities 命名空间
4. **工具推断**：tools 列表中的 name 优先从 lazyToolRegistry 查找（自动识别为 lazy tool），未找到则从 toolRegistry 查找
5. **版本预留**：AgentReference.version 和 AgentDefinition.version 占位，默认 0，暂不涉及版本概念
6. **Registry 外置**：Registry 是运行时 pool，spawn 时注入，AgentDefinition 只存 name 列表
7. **MCP 内置**：MCP 通过 toolset 机制处理，配置声明在 mcps 字段
8. **配置自包含**：AgentDefinition 包含构建 Agent 所需的完整配置（含 apiKey），由配置文件管理安全性

## 待清理

- [ ] `SessionExtensions.kt` 中的 `AgentBuilder.memory(session: Session)` extension 已多余，应删除。现有流程通过 `AgentDefinition.spawn(session.memory, registries)` 构建 Agent，不再需要此 extension。
