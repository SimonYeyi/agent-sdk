# Agent 核心架构

## 1. 概述

`agent/` 模块提供了 Agent 的核心能力：**ReAct 推理循环**、**Tool 系统**、**Memory 管理**、**Hook 生命周期**、**Skill / Subagent / Toolset / MCP 扩展点**、**LLM Provider 抽象层**。所有模块通过 `AgentBuilder` DSL 装配。

## 2. 模块层次

```
agent:core         ← 地基：Agent 接口、ReAct 循环、Tool、Memory、LlmProvider、Persona、AgentHook、AgentEvent
agent:capability   ← 能力框架：Capability/Registry/Adapter（委托模式 + 一一映射模式）
agent:hook         ← Hook 流水线：Hook 接口、HookPipeline、AgentHookEvent
agent:session      ← 会话管理：SessionManager、JSONL 持久化、分页对话记录
agent:skill        ← 技能管理：Skill 接口、SkillRegistry、延迟工具加载
agent:subagent     ← 子 Agent 委派：Subagent 接口、静态/动态 Subagent、并发派发
agent:toolset      ← 工具集：Toolset 容器、ToolsetRegistry、SubToolDelegate
agent:mcp          ← MCP 协议：Mcp 抽象类、McpClient、三种传输层（Stdio/Sse/Local）
agent:providers    ← LLM Provider：OpenAiProvider、AnthropicProvider
```

## 3. Agent 接口与实现

```kotlin
public interface Agent {
    public fun run(input: String): Flow<AgentEvent>
    public fun runStream(input: String): Flow<AgentEvent>
}
```

**ReActAgent** 是唯一实现，通过 `loop()` 方法执行 ReAct 循环：

```
用户输入 → Memory.add(User) → 循环直到完成(maxIterations)
                              │
                              ├─ buildRequest() ──► LLM.chat/chatStream
                              │                      │
                              │                      ▼
                              │               响应无 ToolCalls
                              │                      │
                              │                      ▼
                              │               emit(Final) ──► return
                              │
                              ├─ 响应有 ToolCalls
                              │   │
                              │   ├─ ToolCallExplanation (可选解释)
                              │   │
                              │   ├─ for each ToolCall
                              │   │   ├─ hook.beforeToolCall()  // 可短路
                              │   │   ├─ ToolRegistry.dispatch()
                              │   │   ├─ hook.afterToolCall()   // 可改写
                              │   │   └─ Memory.add(ToolResult)
                              │   │
                              │   └─ 继续下一轮循环
                              │
                              └─ 达到 maxIterations → throw MaxIterations
```

**关键实现细节**：
- `run()` 和 `runStream()` 共享同一个 `loop()` 方法，差异仅在 LLM 调用方式（`chat` vs `chatStream`）
- 流式模式通过 `StreamEvent` 累积 `ToolCall` 参数（`ContentDelta` / `ToolCallStart` / `ToolCallDelta` / `Done`）
- Memory 自动轮次压缩：`RoundsBoundedMemory` 装饰，超限时触发摘要压缩
- 上下文溢出处理：捕获 `context_length_exceeded` 错误，触发 memory 压缩后重试

## 4. LLM 抽象层

```kotlin
public interface LlmProvider {
    val name: String
    suspend fun chat(request: ChatRequest): ChatResponse
    fun chatStream(request: ChatRequest): Flow<StreamEvent>
}
```

**StreamEvent** 统一流式事件模型：
- `ContentDelta` — 文本增量
- `ToolCallStart` / `ToolCallDelta` — 工具调用增量
- `Done` — 正常完成
- `Error` — 错误

## 5. Tool 系统

```kotlin
public interface Tool {
    val name: String
    val description: String
    val parametersSchema: ToolParameters
    suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult
}
```

**ToolParameters** sealed type：
- `Empty` — 无参数
- `JsonSchema` — 完整 JSON Schema

**ToolRegistry** 是工具的单一数据源：
- 注册时检查重复名称（`LinkedHashMap` 保留注册顺序）
- `dispatch()` 将工具调用分发到具体 Tool
- 异常统一转换为 `ToolExecutionResult(isError=true)`
- 投影为 `ToolDefinition` 发送给 LLM

## 6. Memory 系统

```kotlin
public interface Memory {
    suspend fun add(message: ChatMessage)
    suspend fun history(): List<ChatMessage>
    suspend fun rebuild(messages: List<ChatMessage>)
}
```

**实现层次**：
- `InMemoryMemory` — 内存存储
- `RoundsBoundedMemory` — 装饰器，超过保留轮次时触发摘要压缩
- `JsonlBackedMemory` — JSONL 文件持久化（session 模块提供）

## 7. Persona 系统

```kotlin
public class Persona(public val role: String) {
    fun personality(text: String): Persona
    fun domain(text: String): Persona
    fun constraints(items: List<String>): Persona
    fun extra(text: String, label: String? = null): Persona
}
```

`toString()` 将各字段按固定顺序拼接为 system prompt。

## 8. Agent 事件模型

```
AgentEvent (sealed interface)
├── Initial(userInput)              — 用户输入事件
├── TextDelta(text)                 — 流式文本增量
├── ToolCallExplanation(text, names) — 工具调用解释
├── ToolCallStart(callId, toolName) — 工具调用开始
├── ToolCallEnd(callId, result)     — 工具调用结束
├── Final(result)                   — 最终结果
├── Failed(cause)                   — 执行失败
├── MemoryCompressing(summaries)    — 内存压缩开始
└── MemoryCompressed(summaries)     — 内存压缩完成
```

## 9. AgentBuilder DSL

```kotlin
val agent = agent {
    persona(Persona("你是一个 helpful 助手"))
    llmProvider(OpenAiProvider(...))
    memory(session.memory, maxRounds = 30)
    tool(GetWeatherTool())
    skills(skillRegistry)
    toolsets(toolsetRegistry)
    subagents(subagentRegistry)
    hook(HookPipeline(logging = true))
    maxIterations(20)
}
```

**构建时校验**：`llmProvider` 必须设置；工具名称不允许重复；`maxIterations` 必须 > 0。

## 10. Hook 系统 (agent:hook)

### 10.1 架构分层

Hook 系统分为两层：

**底层：AgentHook 接口 (agent:core)**
- 定义 8 个生命周期回调方法
- `ReActAgent` 构造器只接受单个 `AgentHook` 实例

**上层：Hook 框架 (agent:hook)**
- `Hook` — 轻量事件处理器接口，按 `HookEvent` 类型订阅
- `HookPipeline` — 流水线，同时实现 `AgentHook`，内部按 priority 排序调度 sub-hooks
- `HookEvent` / `AgentHookEvent` — 事件类型，对应 AgentHook 的 8 个回调

### 10.2 Hook 接口

```kotlin
public interface Hook {
    val name: String
    val events: Set<KClass<out HookEvent>>?  // null = 接收所有事件
    val priority: Int                         // 数值越大越先执行
    suspend fun execute(event: HookEvent, context: HookContext): HookResult
}
```

### 10.3 Hook 结果语义

```kotlin
public sealed class HookResult {
    object Continue : HookResult()                              // 继续
    data class Refuse(val reason: String) : HookResult()        // 投票拒绝
    data class Modify(val newResult: Any) : HookResult()        // 链式改写
}
```

**调度语义**：
- `Continue`：继续下一个 Hook
- `Refuse`：不中断 chain，末尾聚合多个 Refuse
- `Modify`：通过 `HookEvent.copyWith()` 链式改写事件
- 异常隔离：单个 hook 抛异常不影响主流程

### 10.4 事件类型

```kotlin
AgentHookEvent (sealed interface)
├── BeforeMemoryCompress(summaries)
├── AfterMemoryCompress(summaries)
├── BeforeLlmCall(request)
├── AfterLlmResponse(response)
├── BeforeToolCall(toolCall)          // 返回非 null → 短路执行
├── AfterToolCall(toolCall, result, synthetic, durationMs)
├── RunCompleted(result)
└── RunFailed(error)
```

### 10.5 组合模式

```kotlin
val pipeline = HookPipeline(
    initialHooks = listOf(metricsHook, authHook, loggingHook),
    logging = true
)

val agent = agent {
    llmProvider(...)
    hook(pipeline)
}
```

`HookPipeline` 实现了 `AgentHook`，所以对 `ReActAgent` 来说它只是一个普通的 hook 实例。

## 11. 能力框架 (agent:capability)

### 11.1 设计意图

能力框架解决"如何把一组相关的可调用单元（Skill / Subagent / Toolset）注册到 Agent，让 LLM 能够发现并调用它们"的问题。

### 11.2 核心接口

```kotlin
public interface Capability<T : Any, Ctx : CapabilityContext> {
    val name: String
    val description: String
    suspend fun activate(arguments: T?, context: Ctx): String
}

public interface CapabilityRegistry<Ctx, C, T> {
    val capabilityType: String
    fun register(capability: C)
    fun get(name: String): C
    fun all(): List<C>
}
```

### 11.3 两种适配模式

**委托模式 (Delegate Mode)** — 默认：
- 生成一个 `load_<type>` 工具（如 `load_skill` / `load_subagent` / `load_toolset`）
- 工具描述中动态包含所有已注册 capability 的 name/description
- LLM 通过 `{name: "...", arguments: {...}}` 选择具体 capability
- 优点：减少 LLM 可见工具数，避免工具爆炸

**一一映射模式 (OneToOne Mode)**：
- 每个 capability 生成一个独立工具 `{type}_{name}`（如 `skill_weather` / `subagent_security`）
- 每个工具有自己的 schema 和 description
- 优点：LLM 可见更多语义信息

### 11.4 Adapter 内部实现

```
CapabilityAdapter.of(registry, contextFactory, arguments, enableDelegate)
  ├── DelegationAdapter → CapabilityLoadTool (单一入口)
  └── OneToOneAdapter   → CapabilityAdaptTool × N (每个 capability 一个)
```

## 12. Skill 系统 (agent:skill)

### 12.1 设计意图

Skill 是按需加载的指令包，指导 LLM 如何在某个场景下表现。Skill 本身不含逻辑，只含文本指令。

### 12.2 Skill 接口

```kotlin
public interface Skill : Capability<Unit, SkillContext> {
    val standalone: Boolean
    suspend fun load(): String
}
```

### 12.3 Skill 加载流程

```
LLM 判断需要技能 → 调用 load_skill {name: "weather"}
                         │
                         ▼
               CapabilityLoadTool.execute()
                         │
                         ▼
               SkillRegistry.get("weather")
                         │
                         ▼
               Skill.load() 返回指令文本
```

### 12.4 Skill 工具代理

`skill_tool_loader` / `skill_tool_caller` 两个工具用于延迟加载 Skill 内的子工具。

## 13. Subagent 系统 (agent:subagent)

### 13.1 设计意图

Subagent 是独立 LLM 循环 + 任务委派能力。每个 Subagent 是一个独立的 Agent 实例，拥有自己的 Persona / Memory / Tool 集。

### 13.2 Subagent 接口

```kotlin
public interface Subagent : Capability<SubagentTask, SubagentContext> {
    val maxIterations: Int?
    val memory: Memory?
    val tools: List<Tool>?
    suspend fun load(): String
    suspend fun run(subagentTask: SubagentTask, context: SubagentContext): String
}
```

### 13.3 Subagent 执行流程

```
Subagent.run(task, context)
  │
  ├─ 构造子 Agent (agent { ... })
  │   ├─ persona = Subagent.load()
  │   ├─ llmProvider = 父 Agent 的 provider
  │   ├─ memory = Subagent.memory ?? InMemoryMemory()
  │   ├─ tools = Subagent.tools ?? 父 Agent 的 tools (过滤 MCP 工具)
  │   └─ maxIterations = Subagent.maxIterations ?? 父 Agent 的配置
  │
  ├─ 合并 context 到 task
  ├─ sub.run(userMessage).awaitResult()
  └─ return 子 Agent 的最终回复
```

### 13.4 动态 Subagent

`DynamicSubagentTool` 允许 LLM 在运行时通过 role/context/task/tools 四元组并发派生临时子 Agent：

```kotlin
// LLM 调用 dynamic_subagent
{
  "subagents": [
    {"role": "代码审查员", "task": "审查以下代码...", "tools": ["code_review"]},
    {"role": "安全分析师", "task": "分析安全漏洞...", "tools": ["security_scan"]}
  ]
}
```

- 并发执行：通过 `supervisorScope` + `async` 并发派发所有子 Agent
- 互不影响：每个子 Agent 独立构造，使用独立的 InMemoryMemory
- 结果聚合：按顺序返回各子 Agent 的最终回复

## 14. Toolset 系统 (agent:toolset)

### 14.1 设计意图

Toolset 把一组相关 Tool 作为一个"集合"管理，支持按需启用/关闭。核心解决"LLM 可见工具数过多"的问题。

### 14.2 Toolset 接口

```kotlin
public interface Toolset : Capability<Unit, ToolsetContext>, ToolDispatcher {
    fun add(tool: Tool)
    fun all(): List<Tool>
    suspend fun activate(arguments: Unit?, context: ToolsetContext): String
    suspend fun dispatch(name: String, arguments: JsonElement, context: ToolContext): ToolExecutionResult
}
```

### 14.3 注册与调用

```kotlin
val registry = ToolsetRegistry().apply {
    register(Toolset("weather", "天气相关工具集").apply {
        add(GetWeatherTool()); add(GetForecastTool())
    })
}
val agent = agent { llmProvider(...); toolsets(registry) }
```

### 14.4 子工具代理调用

```
LLM 调用 sub_tool_delegate {toolset_name: "weather", sub_tool_name: "get_weather", ...}
                         │
                         ▼
               SubToolDelegate.execute()
                         ├─ registry.get("weather") → Toolset
                         ├─ toolset.dispatch("get_weather", args, context)
                         └─ Toolset.subTools["get_weather"].execute(args)
```

多个 Toolset 共享同一个 `sub_tool_delegate` 工具，避免工具爆炸。

## 15. MCP 系统 (agent:mcp)

### 15.1 设计意图

MCP 模块把外部 [Model Context Protocol](https://modelcontextprotocol.io) Server 包装成 LLM 可调用的工具。模块内自洽实现协议（2025-06-18）的所有细节。

### 15.2 架构

```
Mcp (抽象类，继承 Toolset)
  ├─ McpClient (JSON-RPC 通信，懒初始化，分页遍历工具)
  ├─ McpRegistry (注册表，复用 Toolset 框架)
  └─ McpTool (ToolDef → Tool 代理调用)
        │
        ▼
McpTransport (三种传输层)
  ├─ StdioTransport — 子进程 stdin/stdout，行分隔 JSON，三级关闭流程
  ├─ SseTransport — Streamable HTTP (POST + SSE GET)，会话亲和性
  └─ LocalTransport — 进程内调用，无网络开销，适合测试
```

### 15.3 传输层

**StdioTransport**：启动子进程，stdin/stdout 通信，三级关闭流程（close stdin → wait 5s → SIGTERM → wait 2s → SIGKILL）。

**SseTransport**：持久 GET 连接接收 SSE 通知，短生命周期 POST 请求发送 JSON-RPC，`Mcp-Session-Id` 头维护会话亲和性，SSE 自动重连。

**LocalTransport**：进程内直接将 JSON-RPC 请求委派给本地 `McpServer` 实现，无网络或子进程开销。

### 15.4 注册与使用

```kotlin
val mcpRegistry = McpRegistry(ToolsetRegistry(), ClientInfo("my-agent", "1.0")).apply {
    register(LiveScoreMcp(httpClient))
    register(CalculatorMcp())
}
val agent = agent { llmProvider(...); mcps(mcpRegistry) }
```

`McpRegistry` 复用 Toolset 框架，`mcps()` DSL 与 `toolsets()` DSL 互斥（共享 `load_toolset` / `sub_tool_delegate` 槽位）。

## 16. Session 与持久化 (agent:session)

### 16.1 SessionManager

```kotlin
val sessionManager = SessionManager(baseDir, hookPipeline)
val session = sessionManager.create(accountId, "我的助手")
val agent = agent { memory(session.memory); llmProvider(...) }
```

**Session 生命周期**：`create()` → `SessionHookEvent.Created`；`start()` → `SessionHookEvent.Start`；`stop()` → `SessionHookEvent.Stop`；`delete()` → `SessionHookEvent.Deleted`。

### 16.2 持久化结构

```
baseDir/agent/sessions/{accountId}/
  sessions.jsonl              ← Session 元数据
  memories/{sessionId}.jsonl  ← 扁平消息历史
  conversations/{sessionId}/
    page1.jsonl               ← 分页对话记录 (20KB/页)
    page2.jsonl
```

### 16.3 分页存储

`JsonlConversation` 实现分页存储，每页达到阈值（默认 20KB）后创建新页。锚点机制：首次调用 `messages(1)` 记录当前最大页码，后续翻页基于锚点计算，防止新增消息导致页码错位。

## 17. Provider 实现要点

**OpenAI Provider**：HTTP POST `/v1/chat/completions`，SSE 流式解码，`tool_choice = "auto"`。

**Anthropic Provider**：HTTP POST `/v1/messages`，版本头 `anthropic-version: 2023-06-01`，`tool_use` content block。

## 18. 异常处理模型

```kotlin
public sealed class AgentException : Exception {
    class MaxIterations(max: Int)            // 达到最大迭代
    class LlmError(cause: Throwable)         // LLM 调用失败
    class InvalidResponse(message: String)   // 响应解析失败
    class ToolExecution(message: String)     // 工具执行异常
    class ToolNotFound(name: String, ...)    // 工具未找到
}
```

Context Overflow 自动重试（memory 压缩后）；`onRunFailed` 接收原始 `Throwable`。

## 19. 配置校验

- `AgentBuilder`：`llmProvider` 必须设置；工具名称不允许重复；`maxIterations` 必须 > 0
- `ToolsetsInstallException`：`load_toolset` / `sub_tool_delegate` 只能由一个 DSL 安装