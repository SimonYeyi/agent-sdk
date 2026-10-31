# Session 与 Agent 绑定设计

## 背景

最初发现 Session 恢复时只能恢复 Memory，但 Agent 相关的配置（provider、capabilities 等）没有被保留。

需要让 Session 绑定 Agent，使得会话恢复时能自动重建 Agent 实例。

## 模块划分

```
Session 模块（agent/session）
├── AgentReference     # Session 绑定的 Agent 投影（仅 name + version 占位）
└── Session            # 会话，包含 AgentReference

config 模块（agent/config）
├── AgentDefinition              # Agent 的完整声明（可序列化）
├── AgentScope                   # 运行时上下文：memory + 各注册表
├── AgentResolver                # 从 AgentScope 按定义投影出 ResolveInfo
├── AgentDefinitionExtensions    # spawn(scope) 组装入口
├── AgentRegistry                # 管理 AgentDefinition 的注册与查找
└── LoggingExtensions            # 日志 tag 封装
```

Session 模块不依赖 config 模块（session 只依赖 core/hook）；恢复闭环由应用层胶水代码完成：`session.agent.name → AgentRegistry.getOrNull → AgentScope(session.memory, registries) → definition.spawn(scope)`。

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

配套新增 `isEmpty()`，供 `AgentResolver` 判断投影结果是否为空。

### agent/config/.../LoggingExtensions.kt

```kotlin
package io.github.yeyi.agent.config

import io.github.yeyi.agent.log.LoggingTagged

internal val log = LoggingTagged("config")
```

## 核心类型

### AgentReference（Session 模块）

```kotlin
@Serializable
public data class AgentReference(
    val name: String,
    val version: Int = 0  // 占位，默认 0
)
```

Session 绑定的 Agent 投影，仅包含引用信息。恢复时按 `name` 从 `AgentRegistry` 查找定义；`version` 暂不参与校验。

### Session（Session 模块）

```kotlin
@Serializable
public data class Session(
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
@Serializable
public data class AgentDefinition(
    val name: String,
    val version: Int = 0,
    val persona: String,
    val provider: Provider,
    val maxIterations: Int = 20,
    val maxMemoryRounds: Int = 20,
    val tools: List<String>? = null,
    val toolsets: List<String>? = null,
    val skills: List<String>? = null,
    val subagents: List<String>? = null,
    val mcps: List<Mcp>? = null,
) {
    @OptIn(ExperimentalSerializationApi::class)
    @Serializable
    @JsonClassDiscriminator("name")
    public sealed interface Provider {
        public val baseUrl: String
        public val model: String
        public val apiKey: String

        @Serializable
        @SerialName("openai")
        public data class OpenAi(...) : Provider

        @Serializable
        @SerialName("anthropic")
        public data class Anthropic(...) : Provider
    }

    @Serializable
    public data class Mcp(
        val name: String,
        val description: String,
        val transport: Transport
    ) {
        @Serializable
        public sealed interface Transport {
            @Serializable
            @SerialName("stdio")
            public data class Stdio(
                val command: String,
                val args: List<String> = emptyList(),
                val workingDirectory: String? = null,
                val env: Map<String, String> = emptyMap()
            ) : Transport

            @Serializable
            @SerialName("http")
            public data class Http(
                val url: String,
                val headers: Map<String, String> = emptyMap()
            ) : Transport
        }
    }
}
```

Provider 采用 sealed interface + `@JsonClassDiscriminator("name")`，`when` 分支编译期穷尽，无需 `else -> error`。

### AgentScope / AgentResolver（config 模块）

```kotlin
public class AgentScope(
    public val memory: Memory,
    public val toolRegistry: ToolRegistry? = null,
    public val lazyToolRegistry: LazyToolRegistry? = null,
    public val toolsetRegistry: ToolsetRegistry? = null,
    public val skillRegistry: SkillRegistry? = null,
    public val subagentRegistry: SubagentRegistry? = null,
    public val hook: AgentHook? = null,                    // null → 不挂 hook（默认空操作）
    public val modalityAdapter: ModalityAdapter? = null    // null → 默认可见性策略
) {
    public fun resolver(): AgentResolver = AgentResolver(this)
}
```

`hook` 与 `modalityAdapter` 是纯运行时注入项，随 `AgentScope` 传入、由 `spawn` 接线；**不进入 AgentDefinition 配置契约**（两者都是代码态对象，无法序列化声明）。

```kotlin
public class AgentResolver internal constructor(private val scope: AgentScope) {
    public fun resolve(definition: AgentDefinition): ResolveInfo
    public class ResolveInfo(
        public val toolRegistry: ToolRegistry?,
        public val lazyToolRegistry: LazyToolRegistry?,
        public val toolsetRegistry: ToolsetRegistry?,
        public val skillRegistry: SkillRegistry?,
        public val subagentRegistry: SubagentRegistry?,
        public val llmProvider: LlmProvider,
        public val mcpRegistry: McpRegistry?
    )
}
```

`resolve` 一次性按序投影：
- `tools == null` → 全部注入；`[]` → 空结果（`ToolRegistry` 仍返回非 null，与 null 等效，spawn 侧 `tools()` 为拷贝式成员函数，空注入无害）；`["a"]` → 只注入 a
- `toolsets/skills/subagents` 同理，但空结果返回 null（spawn 侧跳过插件安装）
- 能力缺失只 `log.warn` 降级，不中断构建
- tools 按名查找时优先 `lazyToolRegistry`（自动识别 lazy tool），未命中再查 `toolRegistry`

### spawn（AgentDefinitionExtensions.kt）

```kotlin
public fun AgentDefinition.spawn(scope: AgentScope): Agent {
    val info = scope.resolver().resolve(this@spawn)
    return agent {
        persona(AgentPersona(persona))
        llmProvider(info.llmProvider)
        memory(scope.memory, maxMemoryRounds)
        maxIterations(maxIterations)
        info.toolRegistry?.let { tools(it) }
        info.lazyToolRegistry?.let { lazyTools(it) }
        info.toolsetRegistry?.let { toolsets(it) }
        info.skillRegistry?.let { skills(it) }
        info.subagentRegistry?.let { subagents(it) }
        info.mcpRegistry?.let { mcps(it) }
        scope.hook?.let { hook(it) }
        scope.modalityAdapter?.let { modalityAdapter(it) }
    }
}
```

注意 `tools()` 是 `AgentBuilder` 的成员函数（拷贝式注册），空 registry 注入与不调用等效；`toolsets()`/`mcps()` 是 toolset 框架插件，只能安装一次。

`scope.hook` / `scope.modalityAdapter` 在此接线到 AgentBuilder；二者为 null 时不调用对应 DSL，走 AgentBuilder 默认行为（默认 hook 空操作、默认可见性策略）。

### AgentRegistry（config 模块）

```kotlin
public class AgentRegistry {
    public fun register(definition: AgentDefinition)
    public fun getOrNull(name: String): AgentDefinition?
}
```

`register` 同名重复注册抛异常；`getOrNull` 查不到返回 null。**纯内存存储，不落盘**——这是 apiKey 不泄露的关键前提之一。

## 配置语义

- 字段不写 → `null` → 使用 registry 中全部项
- 字段写 `= []` → 不使用任何项（tools 投影结果为空 registry，与 null 等效）
- 字段写 `= ["xxx"]` → 只使用指定的项

例如：
```toml
tools = []          # 不使用任何 tool
tools = ["getWeather"]  # 只使用 getWeather
# 不写 tools 字段 → 使用 registry 中全部 tool
```

**恢复一致性提示**：`null` 语义（跟随运行时全部）意味着恢复时能力集合 = 恢复时刻注册表内容。若创建与恢复之间注册表有增删，恢复出的 agent 会漂移。要严格保证恢复一致，定义中应显式枚举（非 null）能力列表。

## 已知限制

- **toolsets 与 mcps 自动合并**：二者不再互斥。`mcps()` 退化为 `toolsets(registry.toolsetRegistry)` 薄包装；`toolsets()` 优先 findPlugin 已装共享 toolset 插件，有则把新 registry 并入，无则安装新插件。二者可同时声明并合并到同一份共享插件。
- **TOML 配置解析器尚未实现**：下方示例仅作格式规划，当前配置通过构造 `AgentDefinition` 对象注入，未提供 TOML/JSON 文件解析入口。
- **apiKey 随配置序列化**：见下方安全边界。

## TOML 配置示例（规划，解析器待实现）

```toml
# MCP 模板定义（顶层）
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

## 创建会话流程

```kotlin
val definition = agentRegistry.getOrNull("smartHome") ?: error("Agent not found")
val session = sessionManager.create(
    accountId = "user1",
    sessionName = "我的智能家居",
    agent = AgentReference(name = "smartHome")
)
val scope = AgentScope(
    memory = session.memory,
    toolRegistry = toolRegistry,
    lazyToolRegistry = lazyToolRegistry,
    toolsetRegistry = toolsetRegistry,
    skillRegistry = skillRegistry,
    subagentRegistry = subagentRegistry
)
val agent = definition.spawn(scope)
```

## 恢复会话流程

```kotlin
val session = sessionManager.get("user1", "session-id")
val definition = agentRegistry.getOrNull(session.agent.name) ?: error("Agent not found")
val scope = AgentScope(
    memory = session.memory,
    toolRegistry = toolRegistry,
    // ... 同上
)
val agent = definition.spawn(scope)
```

## 安全边界（apiKey）

- **现状**：`Provider.apiKey` 必填且随 `AgentDefinition` 序列化。`AgentRegistry` 纯内存、session 只存 `AgentReference`，因此默认路径下 apiKey 不会落盘。
- **责任约定**：`AgentDefinition` 属于敏感配置。任何持久化 AgentDefinition 的介质（配置文件、注册表落盘、日志）必须视为凭证存储，由持有方负责保护；不应序列化到非受控介质。
- **可选演进**：将 `Provider.apiKey` 改为可空，`AgentScope` 增加 `apiKeyResolver` 兜底，null 时由运行时注入，均缺失则抛明确异常。兼顾"配置自包含"与"凭证不落盘"两种模式。

## 设计原则

1. **模块解耦**：Session 模块与 config 模块独立，恢复闭环由应用层胶水组装
2. **投影引用**：Session 只持有 AgentReference，不持有完整声明
3. **扁平化配置**：tools/skills/subagents/toolsets/mcps 各管各的，无 capabilities 命名空间
4. **工具推断**：tools 列表中的 name 优先从 lazyToolRegistry 查找，未找到则从 toolRegistry 查找
5. **版本预留**：AgentReference.version 和 AgentDefinition.version 占位，默认 0，暂不参与恢复校验
6. **Registry 外置**：Registry 是运行时 pool，spawn 时注入，AgentDefinition 只存 name 列表
7. **MCP 内置**：MCP 通过 toolset 机制处理，配置声明在 mcps 字段（含完整 transport 配置，可复用 toolset 槽位混入普通 Toolset）
8. **配置自包含**：AgentDefinition 包含构建 Agent 所需的完整配置（含 apiKey），安全性由配置持有方负责（见安全边界）
9. **缺失降级**：按名投影找不到的能力只 warn 不中断，保证降级可用

## 待清理

- [x] `SessionExtensions.kt` 中的 `AgentBuilder.memory(session: Session)` extension 已删除，现有流程通过 `AgentDefinition.spawn(scope)` 构建 Agent
- [x] toolsets + mcps 同时声明已支持自动合并（`mcps()` 退化为 `toolsets(registry.toolsetRegistry)` 薄包装，二者并入同一共享 toolset 插件）
- [ ] TOML/JSON 配置解析器（当前仅支持对象构造）
- [ ] demo（DemoAgentFactory）仍使用旧 `agent {}` DSL，未迁移到 config 路径
