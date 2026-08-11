# Subagent 模块设计

> 日期：2026-06-24 · 状态：**Implemented**（与代码同步）
> 模块：`subagent`
> 范围：在 `capability` 模块的 Capability / Adapter / Registry 抽象之上，实现任务委托机制；保留 Delegate / OneToOne 两种暴露模式；通过 `memory: Memory?` 空值表达内存策略。

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 提案代号 | subagent |
| 关联模块 | 新增 `subagent` 模块 |
| 关联前置 | `Capability` 抽象 + `AgentContext` / `ToolContext.agentContext` 扩展 |
| 破坏性变更 | 否（新增模块） |

---

## 1. 动机

任务委托是 agent 系统的常见需求：

> "我有一个代码审查 agent,让 main agent 看到它并能在需要时调用,把任务交给它处理。"

每次让 main agent 直接实现代码审查逻辑既冗余又难以维护——典型做法是把一类任务打包成一个**子代理（subagent）**：name + description + 完整独立的 LLM 循环。main agent 通过 LLM tool 调用把任务路由给对应的 subagent,subagent 在自己的 LLM 循环里跑完返回结果。

subagent 与 skill 的关键区别：
- **skill** = 文档片段（load 后注入上下文，让 LLM 拥有知识）
- **subagent** = 独立 LLM 循环（自己跑 ReAct，可以调自己的工具链）

---

## 2. 设计原则

- **Capability 抽象复用**：Subagent 实现 `Capability<SubagentTask, SubagentContext>`，复用 `CapabilityRegistry` 的路由语义和 Adapter 的暴露模式。
- **默认实现在接口里**：`Subagent` 接口自带 `activate` 默认实现，调用方只需提供 `maxIterations` / `memory` / `tools` / `load(context)` 四个成员，不再有 `ReActSubagent` 这种 framework 默认类。
- **两层抽象**：`Subagent` 接口提供完整能力（带 context 的 `load`），`SimpleSubagent` 抽象类提供简化版本（无参 `load`），满足不同复杂度需求。
- **复用 Adapter 而非自己实现 Tool**：暴露模式直接使用 `CapabilityAdapter.of(...)` 工厂生成 `DelegationAdapter` / `OneToOneAdapter`。
- **内存策略由空值表达**：`memory: Memory?` 为 null 时每次调用独立 memory（默认；隔离上下文），非 null 时跨调用共享 memory。
- **错误透传**：subagent 内部错误（网络、解析）抛异常，由 delegate tool 透传成 `ToolExecutionResult(isError=true)`。

---

## 3. 核心类型

### 3.1 包结构

新模块 `subagent/`，包 `io.github.yeyi.agent.subagent`，共 6 个文件：

```
subagent/src/main/kotlin/io/github/yeyi/agent/subagent/
├── Subagent.kt                       # Subagent 接口（含默认 activate）
├── SubagentArguments.kt              # SubagentTask + CapabilityArguments<SubagentTask>（internal）
├── SubagentContext.kt                # SubagentContext + SubagentContextFactory（internal）
├── SubagentRegistry.kt               # CapabilityRegistry 接口委托实现
├── SubagentExtensions.kt             # DSL: AgentBuilder.subagents()
└── SimpleSubagent.kt                 # 便捷抽象类，提供无参 load()
```

### 3.2 `Subagent`

```kotlin
package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.awaitResult
import io.github.yeyi.agent.capability.Capability
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool

/**
 * Subagent = 独立 LLM 循环 + 任务委托能力。
 *
 * 实现 [Capability] 接口，泛型 [SubagentTask] 是 arguments 的强类型表达，
 * 由 [SubagentArguments] 提供 schema 和 serializer 给 Adapter。
 *
 * 提供默认 [activate] 实现：调用方只需实现 [maxIterations] / [memory] / [tools] / [load] 即可。
 */
public interface Subagent : Capability<SubagentTask, SubagentContext> {
    public val maxIterations: Int?
    public val memory: Memory?
    public val tools: List<Tool>?

    public fun load(context: SubagentContext): String

    override suspend fun activate(arguments: SubagentTask?, context: SubagentContext): String {
        val task = arguments?.task
            ?: throw IllegalArgumentException("Missing 'task' argument")

        val memory = memory ?: InMemoryMemory()
        val resolvedTools = tools
            ?: context.agentContext.tools.filter { !it.name.contains(CAPABILITY_TYPE) }
        val instructions = load(context)

        val sub = agent {
            persona(Persona(instructions))
            llmProvider(context.agentContext.llmProvider)
            memory(memory, context.agentContext.maxRounds)
            maxIterations(maxIterations ?: context.agentContext.maxIterations)
            tools(resolvedTools)
        }

        return sub.run(task).awaitResult().message.content
            ?: throw IllegalStateException("Subagent '${name}' returned empty content")
    }

    public companion object {
        public const val CAPABILITY_TYPE: String = "subagent"
    }
}
```

要点：
- `Subagent` 泛型 `Capability<SubagentTask, SubagentContext>`，自带默认 `activate`。
- 实现方需提供 `maxIterations`（迭代预算，null 则继承 main agent 的值）、`memory`（null = 隔离；非 null = 跨调用共享）、`tools`（null = 继承 main agent 的工具并过滤掉 subagent 相关工具；非 null = 显式指定）、`load(context)`（persona 文本懒加载函数，可访问上下文）。
- `activate` 内部启动一个新的 `ReActAgent` 实例跑 sub-loop，使用 main agent 的 `llmProvider`。

### 3.2.1 `SimpleSubagent`

对于不需要访问 `SubagentContext` 的简单场景，提供了 `SimpleSubagent` 便捷抽象类：

```kotlin
public abstract class SimpleSubagent(
    override val name: String,
    override val description: String,
    override val maxIterations: Int? = null,
    override val memory: Memory? = null,
    override val tools: List<Tool>? = null
) : Subagent {
    public abstract fun load(): String

    override fun load(context: SubagentContext): String = load()
}
```

要点：
- 继承 `Subagent` 接口，提供无参抽象 `load()` 方法。
- 实现 `load(context)` 委托给无参 `load()`，忽略上下文。
- 适合大多数不需要访问 `agentContext` 的 subagent 场景。

### 3.3 `SubagentTask` & `SubagentArguments`

```kotlin
@Serializable
public data class SubagentTask(
    val task: String,
    val context: String? = null,
)

internal class SubagentArguments : CapabilityArguments<SubagentTask> {
    override val schema: String = """
        {
            "type": "object",
            "properties": {
                "task": {
                    "type": "string",
                    "description": "Task description to delegate"
                },
                "context": {
                    "type": "string",
                    "description": "Optional background information for this invocation"
                }
            },
            "required": ["task"]
        }
    """.trimIndent()
    override val serializer: KSerializer<SubagentTask> = SubagentTask.serializer()
}
```

要点：
- `SubagentArguments` 是 `internal`（仅供 `CapabilityAdapter` 与 `SubagentExtensions` 内部拼装使用，调用方无需感知）。
- Schema 固定为单字段 `{ task: string }`。

### 3.4 `SubagentRegistry`

```kotlin
public class SubagentRegistry :
    CapabilityRegistry<Subagent, SubagentTask, SubagentContext> by DefaultCapabilityRegistry(
        capabilityName = "subagent"
    )
```

要点：
- `DefaultCapabilityRegistry` 是 final class，无法继承，用接口委托模式（`by`）。
- `register` / `all()` / `find(...)` 等方法直接委托给内部 `DefaultCapabilityRegistry` 实例。
- `capabilityName = "subagent"`，被 `CapabilityAdapter` 用于生成 Tool 名称（`load_subagent` / `subagent_<name>`）。

### 3.5 `SubagentContextFactory`

```kotlin
internal class SubagentContextFactory : CapabilityContextFactory<SubagentContext> {
    override fun create(context: ToolContext): SubagentContext =
        SubagentContext(agentContext = context.agentContext)
}
```

要点：
- `internal`：仅供 Adapter 通过工厂调用。
- 无构造参数，纯粹透传 `ToolContext.agentContext`；迭代预算由 Subagent 实现自身拥有（`Subagent.maxIterations`），不经过 Context 传递。

---

## 4. 暴露模式

subagent 模块**不自行实现 Tool**。暴露模式直接复用 `CapabilityAdapter.of(...)` 工厂：

```kotlin
val registry = SubagentRegistry().apply { register(subagents) }
CapabilityAdapter.of(
    registry = registry,
    capabilityContextFactory = SubagentContextFactory(),
    arguments = SubagentArguments(),
    enableDelegateAdaptMode = true,   // 或 false
).installOn(agentBuilder)
```

**Adapter 已有的基础设施**：
- `enableDelegateAdaptMode = true` → 生成 `LoadCapabilityTool`，schema = `{subagent_name, arguments}`
- `enableDelegateAdaptMode = false` → 为每个 capability 生成 `CapabilityTool`，schema = `SubagentArguments.schema`

`enableDelegateAdaptMode` 是 `CapabilityAdapter.of(...)` 工厂的 `Boolean` 参数（默认 `true`），通过该参数屏蔽 `DelegationAdapter` / `OneToOneAdapter` 具体类的可见性。

---

## 5. DSL

```kotlin
/**
 * 把已有 registry 挂到 AgentBuilder。
 * 暴露模式通过 [enableDelegateAdaptMode] 指定（true 委托 / false 一对一）。
 */
public fun AgentBuilder.subagents(
    registry: SubagentRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    CapabilityAdapter.of(
        registry,
        SubagentContextFactory(),
        SubagentArguments(),
        enableDelegateAdaptMode
    ).installOn(this)
}
```

**没有 `subagent()` 工厂函数**：原 Draft 中的 `subagent(name, description, instructions, maxIterations, sharedMemory)` 已被取消。由于 `Subagent` 是带默认 `activate` 的接口，调用方直接 `object` / `class` 实现即可，无需 framework 提供默认构造器（也避免实现方在 Subagent 上再覆盖 persona/tools/memory 等字段）。

理由：
- **OneToOne**：每个 subagent 一个独立 Tool，LLM 在 system prompt 里能看到完整描述。适合 subagent 数量少（< 5）、每个用途差异大的场景。
- **Delegate**：所有 subagent 通过单个 Tool 暴露，Tool 描述动态列出全部 subagent。适合 subagent 多、需要动态添加的场景（默认）。

---

## 6. Tool 形态（由 Adapter 生成）

### 6.1 Delegate Tool（`LoadCapabilityTool`）

由 `DelegationAdapter` 自动生成的 Tool，schema：

```json
{
    "type": "object",
    "properties": {
        "subagent_name": { "type": "string" },
        "arguments": {
            "type": "object",
            "properties": {
                "task": { "type": "string", "description": "Task description to delegate" }
            },
            "required": ["task"]
        }
    },
    "required": ["subagent_name", "arguments"]
}
```

### 6.2 OneToOne Tool（`CapabilityTool`）

由 `OneToOneAdapter` 为每个 subagent 生成，Tool name = `subagent_<name>`，schema：

```json
{
    "type": "object",
    "properties": {
        "task": { "type": "string", "description": "Task description to delegate" }
    },
    "required": ["task"]
}
```

---

## 7. 错误处理与边界

### 7.1 路由失败（找不到 subagent）

`SubagentRegistry` 的 `register` 在同名重复时抛 `IllegalArgumentException`（由 `DefaultCapabilityRegistry` 实现）。`LoadCapabilityTool.execute` 找不到 subagent 时返回 `ToolExecutionResult(isError=true, content = "subagent_name not found: X")`。

### 7.2 subagent 内部失败

`Subagent.activate` 默认实现里用 `sub.run(task)` 跑 ReAct 循环。`AgentEvent.Failed` 触发抛 `event.cause`（已是 `AgentException`），由 Tool 执行层统一捕获并转 `ToolExecutionResult(isError=true)`。

LLM 能自我纠正的错误（subagent 内部工具失败）由 subagent 自己的 LLM 循环处理，不应穿透到 main agent——这正是委托模式的核心理由（子问题隔离）。

### 7.3 `CancellationException`

统一由 Tool 执行层处理，不专门设计。

### 7.4 不增加防御性校验

按 "good enough" 原则：
- 调用方实现的 `Subagent` 不校验 `name` / `description` 非空。
- `load()` 返回值非空也不校验。
- `maxIterations` 由调用方显式给出；不强制上限。

---

## 8. 文件改动清单

### 8.1 新增文件（subagent 模块）

| 文件 | 说明 |
|---|---|
| `subagent/build.gradle.kts` | Gradle 子项目配置，依赖 `:agent` + `:capability` |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/Subagent.kt` | `Subagent` 接口（含默认 `activate`） |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentArguments.kt` | `SubagentTask` + `CapabilityArguments<SubagentTask>`（internal） |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentContext.kt` | `SubagentContext` + `SubagentContextFactory`（internal） |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentRegistry.kt` | `CapabilityRegistry` 接口委托实现 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentExtensions.kt` | DSL: `AgentBuilder.subagents(...)` |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SimpleSubagent.kt` | 便捷抽象类，提供无参 `load()` |
| `settings.gradle.kts` | 增加 `include(":subagent")` |

### 8.2 不动文件

- `capability/` 模块的所有现有文件（subagent 是 Capability 的具体应用，不修改抽象）
- `agent/`、`skill/`、`mcp/`、`hook/` 模块的所有现有文件（仅依赖 CapabilityAdapter，不修改实现）

---

## 9. 实现示例

### 9.1 Isolated（默认）— `memory` 为 null

```kotlin
val reviewer = object : Subagent {
    override val name = "code-reviewer"
    override val description = "Reviews code for style and correctness"
    override val maxIterations = 5
    override val memory: Memory? = null   // 隔离：每次调用独立 memory
    override fun load() =
        "You are a code reviewer. Given a diff, list issues concisely."
}
```

### 9.2 Shared — `memory` 预创建并跨调用复用

```kotlin
val interviewerMemory = InMemoryMemory()
val interviewer = object : Subagent {
    override val name = "interviewer"
    override val description = "Conducts a multi-turn interview"
    override val maxIterations = 5
    override val memory: Memory = interviewerMemory  // 共享：跨调用累积 memory
    override fun load() =
        "You are an interviewer. Ask one question at a time."
}
```

### 9.3 挂到 AgentBuilder

```kotlin
val registry = SubagentRegistry().apply {
    register(reviewer)
    register(interviewer)
}

agentBuilder.subagents(registry, enableDelegateAdaptMode = true)
```

---

## 10. 文档同步

- `README.md`：增加 "Subagent 委托" 章节，给出一对一/委托两种模式示例。
- `app/src/main/.../demo/DemoAgentFactory.kt`：可选用 subagent 演示（v1.1 起）。

---

## 11. 兼容性

- 全新增模块 + 全新增 API，无破坏性变更。
- `Skill` / `McpServer` / `Hook` 不变。
- `agent` 模块公开 API 因 Capability 抽象已扩展（`AgentContext` / `ToolContext.agentContext`），见 `capability` 模块源码。

---

## 12. 不在本设计范围

- **`build_tools()` Subagent 特有方法**：按用户最新反馈「暂时不考虑」。
- **Subagent 之间的递归委托**：subagent 调用另一个 subagent 暂不限制（按 Capability 抽象天然允许），但不在本次 spec 写测试。
- **Subagent 取消/暂停**：靠 coroutine cancellation，不专门设计。
- **Subagent 远程加载**：v1 不做。
- **Subagent 元数据（version / tags）**：v1 不做。
- **Subagent 嵌套深度限制**：v1 不做。

---

## 附录 A · 从 Draft 到实现的差异记录

| 项 | Draft 描述 | 实际实现 | 原因 |
|---|---|---|---|
| `ReActSubagent` 类 | 单独类，提供默认 `activate` | 删除；`activate` 默认实现下沉到 `Subagent` 接口 | 避免"接口 + 默认类"双层抽象；让接口自带行为 |
| `MemoryStrategy` 枚举 | `Isolated` / `Shared` 显式枚举 | 删除；改为 `memory: Memory?` 空值语义 | 单一字段已能表达两种策略，枚举冗余 |
| `subagent()` DSL 工厂 | 提供 `subagent(name, description, instructions, ...)` 快速构造 | 删除；调用方直接实现 `Subagent` 接口 | 接口已自带默认 `activate`，工厂只能再覆盖字段，价值低 |
| `CapabilityAdaptMode` | 顶层枚举 | 改为 `CapabilityAdapter.Mode` 嵌套枚举 | 收拢 Adapter 相关类型到同一文件 |
| `CapabilityAdapter.Mode` | 嵌套枚举 | 删除；改为 `enableDelegateAdaptMode: Boolean` 参数 | 简化类型层级；两种模式 Boolean 已足够表达 |
| Adapter 构造 | 显式 `DelegationAdapter(...)` / `OneToOneAdapter(...)` | 改为 `CapabilityAdapter.of(...)` 工厂方法 | 屏蔽具体子类可见性，统一创建路径 |
| `SubagentArguments` / `SubagentContextFactory` 可见性 | `public` | `internal` | 仅供 Adapter 内部拼装，无需公开 |
| `SubagentContext` 是否 data class | 未明确 | 普通 class | 仅作为标记接口 `CapabilityContext` 的载体，无需 data class 等价性 |
| `SubagentContext` / `SubagentContextFactory` 的 `maxIterations` | Context 持有预算，工厂可注入上限 | 全部删除；仅保留 `agentContext` 透传字段 | 预算归 Subagent 实现自身拥有（`Subagent.maxIterations`），Context 不必复制 |

---

**文档结束 · 实现为准**