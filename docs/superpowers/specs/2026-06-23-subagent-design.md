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

- **极简接口**：`Subagent` 只暴露 `name` / `description` / `execute`，没有 persona / tools / memory 字段——这些都由 framework 通过 `SubagentCapabilityContext` 注入。
- **Capability 抽象复用**：Subagent 实现 `Capability<SubagentCapabilityContext>`，复用 `CapabilityRegistry` 的路由语义。
- **Delegate Tool per-type**：subagent 模块自己实现 `DelegateSubagentTool`，不靠通用 adapter——委托 Tool 的 schema（参数、描述）随能力类型差异过大。
- **暴露模式可选**：main agent 注册时可选「一对一」（每个 subagent 一个 Tool，类似 SkillTool）或「委托」（所有 subagent 通过一个 Delegate Tool 路由，默认）。
- **内存策略可选**：默认每次调用独立 memory（避免上下文污染）；可选 shared 让 subagent 跨调用保持记忆。
- **错误透传**：subagent 内部错误（网络、解析）抛 `AgentException`，由 delegate tool 透传成 `ToolExecutionResult(isError=true)`。

---

## 3. 核心类型

### 3.1 包结构

新模块 `subagent/`，包 `io.github.yeyi.agent.subagent`：

```
subagent/src/main/kotlin/io/github/yeyi/agent/subagent/
├── Subagent.kt                       # Capability<SubagentCapabilityContext> 实现接口
├── SubagentCapabilityContext.kt      # CapabilityContext 子类
├── DefaultSubagent.kt                # framework 默认实现
├── SubagentRegistry.kt               # CapabilityRegistry<SubagentCapabilityContext>
├── SubagentTool.kt                   # 一对一模式：单个 subagent → 单个 Tool
├── DelegateSubagentTool.kt           # 委托模式：所有 subagent → 单个 Tool
├── SubagentMode.kt                   # OneToOne / Delegate 枚举
├── MemoryStrategy.kt                 # Isolated / Shared 枚举
└── SubagentExtensions.kt              # DSL: subagent() + AgentBuilder.subagents()
```

### 3.2 `Subagent`

```kotlin
package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.capability.Capability
import io.github.yeyi.agent.capability.CapabilityContext
import kotlinx.serialization.json.JsonElement

public interface SubagentCapabilityContext : CapabilityContext {
    public val agentContext: AgentContext      // 透传 main agent 上下文(LlmProvider/Hook/Memory)
    public val maxIterations: Int              // subagent 独立预算,默认 5
}

public interface Subagent : Capability<SubagentCapabilityContext> {
    /**
     * 框架调用入口:把任务字符串交给 subagent,返回结果字符串。
     *
     * - arguments 由 delegate tool 解析后传入(委托模式下为 {"subagent_name", "task"})
     * - context 由 framework 组装,subagent 不必自己构造
     */
    override suspend fun execute(arguments: JsonElement, context: SubagentCapabilityContext): String
}
```

要点：
- `SubagentCapabilityContext.agentContext` 是透传给 subagent 的 AgentContext（含 LlmProvider/Hook），subagent 不需要从外部重新注入。
- `maxIterations` 独立预算，不消耗 main agent 的迭代次数。
- 不暴露 persona / tools / memory 字段——这些由 `DefaultSubagent` 构造时接收，由 framework 拼装。

### 3.3 `MemoryStrategy`

```kotlin
public enum class MemoryStrategy {
    /** 每次调用独立 memory（默认；隔离上下文） */
    Isolated,
    /** 同一 subagent 实例跨调用共享 memory（保留上下文供后续轮次） */
    Shared,
}
```

理由：
- 默认 `Isolated`：subagent 单次任务不应污染自身历史，避免 main agent 反复调用时上下文错乱。
- `Shared`：少数场景下 subagent 需要保留对话上下文（如多轮访谈式 subagent），由调用方显式选择。

### 3.4 `DefaultSubagent`

```kotlin
public class DefaultSubagent(
    override val name: String,
    override val description: String,
    private val instructions: String,                  // = persona 文本
    private val memoryStrategy: MemoryStrategy = MemoryStrategy.Isolated,
    private val maxIterations: Int = 5,                // subagent 独立预算默认值
    private val sharedMemory: Memory? = null,         // Shared 模式下注入预创建 memory
) : Subagent {

    override suspend fun execute(arguments: JsonElement, context: SubagentCapabilityContext): String {
        val task = (arguments as? JsonObject)?.get("task")?.jsonPrimitive?.content
            ?: throw IllegalArgumentException("Missing 'task' argument")

        val memory = when (memoryStrategy) {
            MemoryStrategy.Isolated -> InMemoryMemory()
            MemoryStrategy.Shared   -> sharedMemory ?: InMemoryMemory()
        }

        val sub = agent {
            persona(Persona(instructions))
            llmProvider(context.agentContext.llmProvider)
            hook(context.agentContext.hook)
            memory(memory)
            maxIterations(this@DefaultSubagent.maxIterations)
        }

        var result = ""
        sub.run(task).collect { event ->
            when (event) {
                is AgentEvent.Final  -> result = event.result.message.content ?: ""
                is AgentEvent.Failed -> throw event.cause
                else -> {}
            }
        }
        return result
    }
}
```

要点：
- `instructions` 是 subagent 的 persona 文本（系统提示词）。
- `Shared` 模式下 `sharedMemory` 由调用方持有引用，跨 `execute` 调用复用同一内存——调用方负责其生命周期。
- `DefaultSubagent` 内部启动一个新的 `ReActAgent` 实例跑 sub-loop，使用 main agent 的 `llmProvider` + `hook`（透传）。
- 内存策略对调用方透明：`Isolated` 在 `execute` 内每次新建；`Shared` 复用构造期持有的引用。

### 3.5 `SubagentRegistry`

复用 `CapabilityRegistry` 的实现细节；公开别名以保留业务命名：

```kotlin
public class SubagentRegistry : CapabilityRegistry<SubagentCapabilityContext>() {

    public companion object {
        public fun of(subagents: Iterable<Subagent>): SubagentRegistry =
            SubagentRegistry().apply { register(subagents) }
    }

    /**
     * Internal:供 DSL 在 OneToOne 模式下枚举注册项以生成单 Tool。
     * 不作为公开 API（不暴露 get/all）；仅 subagent 模块内部可见。
     */
    internal fun snapshot(): List<Subagent> = snapshotCapabilities()
}
```

注：`snapshotCapabilities()` 是 `CapabilityRegistry` 提供的 `internal` 钩子（不在公开 API 暴露），仅供 framework DSL 使用。

### 3.6 暴露模式枚举

```kotlin
public enum class SubagentMode {
    /** 每个 subagent 暴露为一个 Tool（name = "subagent_<name>"），类似 SkillTool */
    OneToOne,
    /** 所有 subagent 通过单个 Delegate Tool 暴露，LLM 用 subagent_name 参数路由（默认） */
    Delegate,
}
```

理由：
- **OneToOne**：每个 subagent 一个独立 Tool，LLM 在 system prompt 里能看到完整描述。适合 subagent 数量少（< 5）、每个用途差异大的场景。
- **Delegate**：所有 subagent 通过单个 Tool 暴露，Tool 描述动态列出全部 subagent。适合 subagent 多、需要动态添加的场景（默认）。

---

## 4. Delegate Tool 形态

### 4.1 `DelegateSubagentTool`（委托模式，默认）

```kotlin
internal class DelegateSubagentTool(
    private val registry: SubagentRegistry,
    private val contextFactory: CapabilityContextFactory<SubagentCapabilityContext>,
) : Tool {
    override val name: String = "delegate_to_subagent"

    override val description: String by lazy {
        buildString {
            append("Delegate a task to one of the registered subagents.\n")
            append("Available subagents:\n")
            append(registry.buildDescription())
        }
    }

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema("""
        {
            "type": "object",
            "properties": {
                "subagent_name": { "type": "string", "description": "Target subagent name from the registry" },
                "task": { "type": "string", "description": "Task description to delegate" }
            },
            "required": ["subagent_name", "task"]
        }
    """.trimIndent())

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        val args = arguments.jsonObject
        val subagentName = args["subagent_name"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult(content = "Missing 'subagent_name'", isError = true)
        val task = args["task"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult(content = "Missing 'task'", isError = true)

        val subagentContext = contextFactory.create(context)
        return try {
            val result = registry.execute(subagentName, JsonObject(mapOf("task" to JsonPrimitive(task))), subagentContext)
            ToolExecutionResult(content = result, isError = false)
        } catch (e: IllegalArgumentException) {
            ToolExecutionResult(content = "Subagent not found: $subagentName. ${e.message}", isError = true)
        }
        // 其他异常透传给 ToolRegistry 处理
    }
}
```

要点：
- Tool 名称固定 `delegate_to_subagent`（所有 subagent 共享一个 Tool）。
- 描述懒构建：首次访问时拼接 `buildDescription()`（含所有 subagent 的 name + description）。
- 参数固定 `{subagent_name, task}`。
- `IllegalArgumentException`（找不到 subagent）捕获并转 `isError=true`；其他异常透传给 `ToolRegistry` 的统一 try/catch。

### 4.2 `SubagentTool`（一对一模式）

```kotlin
internal class SubagentTool(
    private val subagent: Subagent,
    private val contextFactory: CapabilityContextFactory<SubagentCapabilityContext>,
) : Tool {
    override val name: String = "subagent_${subagent.name}"
    override val description: String = subagent.description

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema("""
        {
            "type": "object",
            "properties": {
                "task": { "type": "string", "description": "Task description to delegate" }
            },
            "required": ["task"]
        }
    """.trimIndent())

    override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
        return try {
            val subagentContext = contextFactory.create(context)
            val result = subagent.execute(arguments, subagentContext)
            ToolExecutionResult(content = result, isError = false)
        } catch (e: Throwable) {
            if (e is CancellationException) throw e
            ToolExecutionResult(content = "Subagent execution failed: ${e.message}", isError = true)
        }
    }
}
```

要点：
- Tool 名称 = `subagent_<name>`（与 SkillTool 命名规则一致）。
- 参数只有 `task`（subagent_name 已通过 Tool 名称隐含）。
- 异常捕获并转 `isError=true`（与 `ToolRegistry` 错误处理范式一致，但放在 tool 内部因为没有 registry 中转）。

---

## 5. DSL

```kotlin
/**
 * 创建一个最简的 DefaultSubagent。
 *
 * @param name subagent 路由名
 * @param description 给 LLM 看的简介
 * @param instructions subagent 的 persona 文本(系统提示词)
 * @param memoryStrategy 内存策略，默认 Isolated
 * @param sharedMemory Shared 模式下预创建的 memory（可空，默认新建一个 InMemoryMemory）
 */
public fun subagent(
    name: String,
    description: String,
    instructions: String,
    memoryStrategy: MemoryStrategy = MemoryStrategy.Isolated,
    sharedMemory: Memory? = null,
    maxIterations: Int = 5,
): Subagent = DefaultSubagent(
    name = name,
    description = description,
    instructions = instructions,
    memoryStrategy = memoryStrategy,
    sharedMemory = sharedMemory,
    maxIterations = maxIterations,
)

/**
 * 把 subagent 列表 / registry 挂到 AgentBuilder。
 *
 * @param mode 暴露模式，默认 Delegate
 */
public fun AgentBuilder.subagents(
    subagents: Iterable<Subagent>,
    mode: SubagentMode = SubagentMode.Delegate,
) {
    val registry = SubagentRegistry().apply { register(subagents) }
    subagents(registry, mode)
}

public fun AgentBuilder.subagents(
    registry: SubagentRegistry,
    mode: SubagentMode = SubagentMode.Delegate,
) {
    when (mode) {
        SubagentMode.OneToOne -> registry.snapshot().forEach { tool(SubagentTool(it, ::createSubagentContext)) }
        SubagentMode.Delegate -> tool(DelegateSubagentTool(registry, ::createSubagentContext))
    }
}

private fun createSubagentContext(toolContext: ToolContext): SubagentCapabilityContext =
    DefaultSubagentContext(agentContext = toolContext.agentContext)
```

注：`createSubagentContext` 是 subagent 模块的私有 factory，使用 `toolContext.agentContext`（非空，前置条件由 Capability 文档保证）。

---

## 6. 错误处理与边界

### 6.1 路由失败（找不到 subagent）

`SubagentRegistry.execute` 抛 `IllegalArgumentException`：
- 委托模式下 `DelegateSubagentTool.execute` 捕获并转 `ToolExecutionResult(isError=true, content = "Subagent not found: X")`。
- 一对一模式下 Tool 名固定，不存在路由失败（同名 subagent 在 register 时已被去重）。

### 6.2 subagent 内部失败

`DefaultSubagent.execute` 内部用 `sub.run(task)` 跑 ReAct 循环。`AgentEvent.Failed` 触发抛 `event.cause`（已是 `AgentException`），由 delegate tool 透传给 `ToolRegistry`，最终 `ToolRegistry` 转 `ToolExecutionResult(isError=true)`。

LLM 能自我纠正的错误（subagent 内部工具失败）由 subagent 自己的 LLM 循环处理，不应穿透到 main agent——这正是委托模式的核心理由（子问题隔离）。

### 6.3 `CancellationException`

delegate tool 与 subagent 内部均不应吞 `CancellationException`，统一由 `ToolRegistry` 的现有契约处理。

### 6.4 不增加防御性校验

按 "good enough" 原则：
- `subagent()` DSL 不校验 name 非空、description 非空。
- `DefaultSubagent` 不校验 instructions 非空。
- `maxIterations` 默认 5，由调用方显式覆盖。

---

## 7. 文件改动清单

### 7.1 新增文件（subagent 模块）

| 文件 | 说明 |
|---|---|
| `subagent/build.gradle.kts` | Gradle 子项目配置 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/Subagent.kt` | `Subagent` + `SubagentCapabilityContext` |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/DefaultSubagent.kt` | framework 默认实现 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentRegistry.kt` | `CapabilityRegistry` 子类 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/DefaultSubagentContext.kt` | `SubagentCapabilityContext` 默认实现 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentTool.kt` | 一对一 Tool |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/DelegateSubagentTool.kt` | 委托 Tool |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentMode.kt` | OneToOne / Delegate 枚举 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/MemoryStrategy.kt` | Isolated / Shared 枚举 |
| `subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentExtensions.kt` | DSL |
| `settings.gradle.kts` | 增加 `include(":subagent")` |

### 7.2 不动文件

- `agent/`、`skill/`、`mcp/`、`hook/` 模块的所有现有文件（仅依赖新 API，不修改实现）
- 已有 `Capability` 模块的接口（subagent 是 Capability 的具体应用，不修改抽象）

---

## 8. 内存策略示例

### 8.1 Isolated（默认）

```kotlin
val reviewer = subagent(
    name = "code-reviewer",
    description = "Reviews code for style and correctness",
    instructions = "You are a code reviewer. Given a diff, list issues concisely.",
)
// 每次 reviewer.execute(...) 都是独立 memory,跨调用无状态
```

### 8.2 Shared

```kotlin
val interviewerMemory = InMemoryMemory()
val interviewer = subagent(
    name = "interviewer",
    description = "Conducts a multi-turn interview",
    instructions = "You are an interviewer. Ask one question at a time.",
    memoryStrategy = MemoryStrategy.Shared,
    sharedMemory = interviewerMemory,  // 跨调用复用
)
// 多次 interviewer.execute(...) 会累积 memory,实现多轮访谈
```

---

## 9. 文档同步

- `README.md`：增加 "Subagent 委托" 章节，给出一对一/委托两种模式示例。
- `docs/superpowers/specs/2026-06-23-capability-module-design.md`：第 4.3 节增加一行「Subagent 模块 spec 位于 `2026-06-23-subagent-design.md`」。
- `app/src/main/.../demo/DemoAgentFactory.kt`：可选用 subagent 演示（v1.1 起）。

---

## 10. 兼容性

- 全新增模块 + 全新增 API，无破坏性变更。
- `Skill` / `McpServer` / `Hook` 不变。
- `agent` 模块公开 API 因 Capability 抽象已扩展（AgentContext / ToolContext.agentContext），见 `2026-06-23-capability-module-design.md`。

---

## 11. 不在本设计范围

- **`build_tools()` Subagent 特有方法**：按用户最新反馈「暂时不考虑」。
- **Subagent 之间的递归委托**：subagent 调用另一个 subagent 暂不限制（按 Capability 抽象天然允许），但不在本次 spec 写测试。
- **Subagent 取消/暂停**：靠 coroutine cancellation，不专门设计。
- **Subagent 远程加载**：v1 不做。
- **Subagent 元数据（version / tags）**：v1 不做。
- **Subagent 嵌套深度限制**：v1 不做。

---

**文档结束 · 用户明确"先放着"暂不审阅，等待能力模块实施完成后再回头审视**
