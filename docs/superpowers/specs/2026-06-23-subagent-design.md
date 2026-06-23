# Subagent 模块设计

> 日期：2026-06-23 · 状态：Draft（待用户审阅；用户明确要求"先放着"再驱动实施能力模块）
> 模块：`subagent`（新增 Gradle 子项目）
> 范围：在 Capability 抽象（见 `2026-06-23-capability-module-design.md`）之上实现任务委托机制；保留一对一/委托两种暴露模式；保留内存策略（shared/isolated）。

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 提案代号 | subagent |
| 关联模块 | 新增 `subagent` 模块 |
| 关联前置 | `Capability` 抽象（已 spec 完成，待实施）+ `AgentContext` / `ToolContext.agentContext` 扩展 |
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
- **极简接口**：`Subagent` 只暴露 `name` / `description` / `activate`，没有 persona / tools / memory 字段——这些都由 framework 通过 `SubagentContext` 注入。
- **复用 Adapter 而非自己实现 Tool**：暴露模式直接使用 `DelegationAdapter`（委托模式，默认）和 `OneToOneAdapter`（一对一模式），不自行实现 Tool。
- **内存策略可选**：默认每次调用独立 memory（避免上下文污染）；可选 shared 让 subagent 跨调用保持记忆。
- **错误透传**：subagent 内部错误（网络、解析）抛异常，由 delegate tool 透传成 `ToolExecutionResult(isError=true)`。

---

## 3. 核心类型

### 3.1 包结构

新模块 `subagent/`，包 `io.github.yeyi.agent.subagent`：

```
subagent/src/main/kotlin/io/github/yeyi/agent/subagent/
├── Subagent.kt                       # Subagent + SubagentTask + SubagentContext
├── ReActSubagent.kt               # framework 默认实现
├── SubagentRegistry.kt             # CapabilityRegistry 接口委托实现
├── SubagentContextFactory.kt        # CapabilityContextFactory<SubagentContext>
├── MemoryStrategy.kt               # Isolated / Shared 枚举（ReActSubagent 已不使用，由 sharedMemory null 控制）
├── SubagentArguments.kt            # CapabilityArguments<SubagentTask>
└── SubagentExtensions.kt           # DSL: subagent() + AgentBuilder.subagents()
```

### 3.2 `Subagent`

```kotlin
package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.capability.Capability
import io.github.yeyi.agent.capability.CapabilityContext
import kotlinx.serialization.Serializable

/**
 * Task 输入类型，由 [SubagentArguments] 提供 schema 和 serializer。
 */
@Serializable
public data class SubagentTask(val task: String)

/**
 * Subagent 的 CapabilityContext。
 *
 * @param agentContext 透传 main agent 上下文(LlmProvider/Hook/Memory)
 * @param maxIterations subagent 独立预算,默认 5
 */
public class SubagentContext(
    public val agentContext: AgentContext,
    public val maxIterations: Int,
) : CapabilityContext

/**
 * Subagent = 独立 LLM 循环 + 任务委托能力。
 *
 * 实现 [Capability] 接口，泛型 [SubagentTask] 是 arguments 的强类型表达，
 * 由 [SubagentArguments] 提供 schema 和 serializer 给 Adapter。
 */
public interface Subagent : Capability<SubagentTask, SubagentContext>
```

要点：
- `Subagent` 泛型 `Capability<SubagentTask, SubagentContext>`。
- `SubagentContext` 是 data class，实现 `CapabilityContext` 标记接口。
- 不暴露 persona / tools / memory 字段——这些由 `ReActSubagent` 构造时接收，由 framework 拼装。

### 3.3 `SubagentTask` & `SubagentArguments`

```kotlin
package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.capability.CapabilityArguments
import kotlinx.serialization.KSerializer

@Serializable
public data class SubagentTask(val task: String)

/**
 * Subagent 的 arguments 定义。
 * Schema 固定为 `{ "task": { "type": "string", "description": "..." } }`。
 */
public class SubagentArguments : CapabilityArguments<SubagentTask> {
    override val schema: String = """
        {
            "type": "object",
            "properties": {
                "task": {
                    "type": "string",
                    "description": "Task description to delegate"
                }
            },
            "required": ["task"]
        }
    """.trimIndent()
    override val serializer: KSerializer<SubagentTask> = SubagentTask.serializer()
}
```

### 3.4 `ReActSubagent`

```kotlin
public class ReActSubagent(
    override val name: String,
    override val description: String,
    private val instructions: String,
    private val maxIterations: Int,
    private val sharedMemory: Memory?,
) : Subagent {

    override suspend fun activate(
        arguments: SubagentTask?,
        context: SubagentContext
    ): String {
        val task = arguments?.task
            ?: throw IllegalArgumentException("Missing 'task' argument")

        val memory = sharedMemory ?: InMemoryMemory()

        val sub = agent {
            persona(Persona(instructions))
            llmProvider(context.agentContext.llmProvider)
            memory(memory)
            maxIterations(maxIterations)
        }

        return sub.run(task).awaitResult().message.content
            ?: throw IllegalStateException("Subagent '\${name}' returned empty content")
    }
}
```

要点：
- `activate` 签名匹配 `Capability<SubagentTask, SubagentContext>` 接口。
- `instructions` 是 subagent 的 persona 文本（系统提示词）。
- `sharedMemory` 为 null 时每次创建独立 memory；不为 null 时跨调用复用——由调用方控制内存策略。
- `ReActSubagent` 内部启动一个新的 `ReActAgent` 实例跑 sub-loop，使用 main agent 的 `llmProvider`（hook 暂不支持）。

### 3.5 `SubagentRegistry`

```kotlin
public class SubagentRegistry :
    CapabilityRegistry<SubagentContext, Subagent, SubagentTask> by DefaultCapabilityRegistry(
        capabilityName = "subagent"
    )
```

要点：
- `DefaultCapabilityRegistry` 是 final class，无法继承，用接口委托模式。
- 通过 `by DefaultCapabilityRegistry(...)` 直接委托。
- `register` / `all()` 等方法直接委托给内部实例。
- 构造器 internal，外部通过 `AgentBuilder.subagents(registry)` DSL 接入。

### 3.6 `SubagentContextFactory`

```kotlin
public class SubagentContextFactory(
    private val maxIterations: Int = 5,
) : CapabilityContextFactory<SubagentContext> {
    override fun create(context: ToolContext): SubagentContext {
        return SubagentContext(
            agentContext = context.agentContext,
            maxIterations = maxIterations,
        )
    }
}
```

---

## 4. 暴露模式

subagent 模块**不自行实现 Tool**。暴露模式直接复用 `CapabilityAdapter`：

```kotlin
val registry = SubagentRegistry().apply { register(subagents) }
DelegationAdapter(
    registry,
    SubagentContextFactory(),
    SubagentArguments()
).installOn(agentBuilder)
```

**Adapter 已有的基础设施**：
- `DelegationAdapter` → 生成 `LoadCapabilityTool`，schema = `{subagent_name, arguments}`
- `OneToOneAdapter` → 为每个 capability 生成 `CapabilityTool`，schema = `SubagentArguments.schema`

---

## 5. DSL

```kotlin
/**
 * 创建一个最简的 ReActSubagent。
 *
 * @param name subagent 路由名
 * @param description 给 LLM 看的简介
 * @param instructions subagent 的 persona 文本(系统提示词)
 * @param sharedMemory 不为 null 时跨调用复用，null 时每次独立 memory
 */
public fun subagent(
    name: String,
    description: String,
    instructions: String,
    maxIterations: Int = 5,
    sharedMemory: Memory? = null,
): Subagent = ReActSubagent(
    name = name,
    description = description,
    instructions = instructions,
    maxIterations = maxIterations,
    sharedMemory = sharedMemory,
)

/**
 * 把已有 registry 挂到 AgentBuilder。
 * 暴露模式通过 [CapabilityAdaptMode] 指定（Delegate / OneToOne），定义在 capability 模块。
 */
public fun AgentBuilder.subagents(
    registry: SubagentRegistry,
    mode: CapabilityAdaptMode = CapabilityAdaptMode.Delegate,
) {
    val adapter = when (mode) {
        CapabilityAdaptMode.Delegate -> DelegationAdapter(
            registry,
            SubagentContextFactory(),
            SubagentArguments()
        )
        CapabilityAdaptMode.OneToOne -> OneToOneAdapter(
            registry,
            SubagentContextFactory(),
            SubagentArguments()
        )
    }
    adapter.installOn(this)
}
```

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

`SubagentRegistry` 的 `register` 在同名重复时抛 `IllegalArgumentException`（由 `DefaultCapabilityRegistry` 实现）。`LoadCapabilityTool.execute` 捕获并转 `ToolExecutionResult(isError=true, content = "Subagent not found: X")`。

### 7.2 subagent 内部失败

`DefaultSubagent.activate` 内部用 `sub.run(task)` 跑 ReAct 循环。`AgentEvent.Failed` 触发抛 `event.cause`（已是 `AgentException`），由 Tool 执行层统一捕获并转 `ToolExecutionResult(isError=true)`。

LLM 能自我纠正的错误（subagent 内部工具失败）由 subagent 自己的 LLM 循环处理，不应穿透到 main agent——这正是委托模式的核心理由（子问题隔离）。

### 7.3 `CancellationException`

统一由 Tool 执行层处理，不专门设计。

### 7.4 不增加防御性校验

按 "good enough" 原则：
- `subagent()` DSL 不校验 name 非空、description 非空。
- `ReActSubagent` 不校验 instructions 非空。
- `maxIterations` 默认 5，由调用方显式覆盖。

---

## 8. 文件改动清单

### 8.1 新增文件（subagent 模块）

| 文件 | 说明 |
|---|---|
| `subagent/build.gradle.kts` | Gradle 子项目配置，依赖 `capability` 模块 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/Subagent.kt` | `Subagent` + `SubagentTask` + `SubagentContext` |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/ReActSubagent.kt` | framework 默认实现 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentRegistry.kt` | `CapabilityRegistry` 接口委托实现 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentContextFactory.kt` | `CapabilityContextFactory` 实现 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentArguments.kt` | `CapabilityArguments<SubagentTask>` |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/MemoryStrategy.kt` | 内存策略枚举（ReActSubagent 已不使用） |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentExtensions.kt` | DSL |
| `settings.gradle.kts` | 增加 `include(":subagent")` |

### 8.2 不动文件

- `capability/` 模块的所有现有文件（subagent 是 Capability 的具体应用，不修改抽象）
- `agent/`、`skill/`、`mcp/`、`hook/` 模块的所有现有文件（仅依赖 CapabilityAdapter，不修改实现）

---

## 9. 内存策略示例

### 9.1 Isolated（默认）

```kotlin
val reviewer = subagent(
    name = "code-reviewer",
    description = "Reviews code for style and correctness",
    instructions = "You are a code reviewer. Given a diff, list issues concisely.",
)
// 每次 reviewer.activate(...) 都是独立 memory,跨调用无状态
```

### 9.2 Shared

```kotlin
val interviewerMemory = InMemoryMemory()
val interviewer = subagent(
    name = "interviewer",
    description = "Conducts a multi-turn interview",
    instructions = "You are an interviewer. Ask one question at a time.",
    sharedMemory = interviewerMemory,  // 跨调用复用
)
// 多次 interviewer.activate(...) 会累积 memory,实现多轮访谈
```

---

## 10. 文档同步

- `README.md`：增加 "Subagent 委托" 章节，给出一对一/委托两种模式示例。
- `docs/superpowers/specs/2026-06-23-capability-module-design.md`：第 4.3 节增加一行「Subagent 模块 spec 位于 `2026-06-23-subagent-design.md`」。
- `app/src/main/.../demo/DemoAgentFactory.kt`：可选用 subagent 演示（v1.1 起）。

---

## 11. 兼容性

- 全新增模块 + 全新增 API，无破坏性变更。
- `Skill` / `McpServer` / `Hook` 不变。
- `agent` 模块公开 API 因 Capability 抽象已扩展（AgentContext / ToolContext.agentContext），见 `2026-06-23-capability-module-design.md`。

---

## 12. 不在本设计范围

- **`build_tools()` Subagent 特有方法**：按用户最新反馈「暂时不考虑」。
- **Subagent 之间的递归委托**：subagent 调用另一个 subagent 暂不限制（按 Capability 抽象天然允许），但不在本次 spec 写测试。
- **Subagent 取消/暂停**：靠 coroutine cancellation，不专门设计。
- **Subagent 远程加载**：v1 不做。
- **Subagent 元数据（version / tags）**：v1 不做。
- **Subagent 嵌套深度限制**：v1 不做。

---

**文档结束 · 用户明确"先放着"暂不审阅，等待能力模块实施完成后再回头审视**
