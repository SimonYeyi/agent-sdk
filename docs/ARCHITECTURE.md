# Agent SDK 架构设计文档

## 1. 项目概述

Agent SDK 是一个 Kotlin 多平台（Kotlin Multiplatform）Agent 开发框架，核心使用 ReAct（Reasoning + Acting）模式实现大语言模型驱动的智能体。SDK 设计强调**可插拔架构**，各模块职责清晰，通过依赖注入和 DSL 模式构建 Agent。

---

## 2. 模块架构

```
┌─────────────────────────────────────────────────────────────────┐
│                          app (Android Demo)                      │
│    DemoAgentFactory │ ChatScreen │ ChatViewModel │ Demo Tools    │
├─────────────────────────────────────────────────────────────────┤
│                           agent (Core)                           │
│  ┌──────────┐ ┌────────────┐ ┌─────────┐ ┌────────┐ ┌─────────┐ │
│  │  Agent  │ │AgentBuilder│ │Persona  │ │  Tool  │ │ Memory  │ │
│  │ ReActAgent│ │    DSL    │ │ System  │ │Registry│ │InMemory │ │
│  └──────────┘ └────────────┘ └─────────┘ └────────┘ └─────────┘ │
│  ┌──────────────────┐ ┌─────────────────┐ ┌───────────────┐  │
│  │     LLM 模块      │ │  AgentEvent/Hook │ │AgentException  │  │
│  │ChatMessage/Request│ │   /Context/Result│ │               │  │
│  │  StreamEvent      │ │                  │ │               │  │
│  └──────────────────┘ └─────────────────┘ └───────────────┘  │
├─────────────────────────────────────────────────────────────────┤
│                         hook (生命周期钩子)                       │
│                    Hook │ CompositeHook                           │
├─────────────────────────────────────────────────────────────────┤
│                        session (会话管理)                         │
│    Session │ SessionManager │ SessionRepository │ JsonlBackedMemory│
├─────────────────────────────────────────────────────────────────┤
│                         skill (技能管理)                          │
│      Skill │ SkillRegistry │ SkillTool │ LoadSkillTool │ SkillContext │
├─────────────────────────────────────────────────────────────────┤
│                           mcp (MCP 客户端)                         │
│   McpServer │ GenericMcpServer │ StdioTransport │ SseTransport   │
│   McpServerRegistry │ LoadMcpTool │ CallMcpTool                   │
├─────────────────────────────────────────────────────────────────┤
│                    providers (LLM Provider 实现)                  │
│              OpenAiProvider │ AnthropicProvider                  │
└─────────────────────────────────────────────────────────────────┘
```

### 2.1 各模块职责

| 模块 | 职责 |
|------|------|
| **agent** | 核心：Agent 接口、ReAct 实现、Tool/Memory/Persona 管理、LLM 抽象 |
| **hook** | 生命周期钩子系统，支持 Agent 和 Session 双重回调的组合扩展 |
| **session** | 会话持久化管理，支持 JSONL 格式存储历史 |
| **skill** | 动态技能加载，将 Skill 适配为 Tool 供 LLM 调用 |
| **mcp** | Model Context Protocol 客户端，把外部 MCP Server 包装为 LLM 可调用的 Tool |
| **providers** | LLM Provider 实现（OpenAI/Anthropic），处理协议转换 |
| **app** | Android 演示应用 |

---

## 3. 核心组件设计

### 3.1 Agent 接口与实现

```kotlin
public interface Agent {
    // 批式调用
    fun run(input: String): Flow<AgentEvent>
    // 流式调用
    fun runStream(input: String): Flow<AgentEvent>
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
                              │   │   ├─ ToolRegistry.execute()
                              │   │   ├─ hook.afterToolCall()   // 可改写
                              │   │   └─ Memory.add(ToolResult)
                              │   │
                              │   └─ 继续下一轮循环
                              │
                              └─ 达到 maxIterations
                                      │
                                      ▼
                               throw MaxIterations
```

### 3.2 LLM 抽象层

**LlmProvider** 接口解耦上层与具体 LLM 实现：

```kotlin
public interface LlmProvider {
    val name: String
    suspend fun chat(request: ChatRequest): ChatResponse
    fun chatStream(request: ChatRequest): Flow<StreamEvent>
}
```

**StreamEvent** 统一流式事件模型：
- `ContentDelta` - 文本增量
- `ToolCallStart/ToolCallDelta` - 工具调用增量
- `Done` - 正常完成
- `Error` - 错误

### 3.3 Tool 系统

```kotlin
public interface Tool {
    val name: String
    val description: String
    val parametersSchema: ToolParameters
    suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult
}
```

**ToolRegistry** 是工具的单一数据源：
- 注册时检查重复名称
- 执行时异常转换为 `ToolExecutionResult(isError=true)`
- 投影为 `ToolDefinition` 发送给 LLM

### 3.4 Memory 系统

```kotlin
public interface Memory {
    suspend fun add(message: ChatMessage)
    suspend fun history(): List<ChatMessage>
}
```

两种实现：
- **InMemoryMemory** - 内存存储，测试用
- **JsonlBackedMemory** - 持久化存储，会话场景用

### 3.5 Hook 系统

```kotlin
public interface Hook : AgentHook, SessionHook

public interface AgentHook {
    suspend fun beforeLlmCall(context: AgentContext) {}
    suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) {}
    suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult?
    suspend fun afterToolCall(context: AgentContext, call: ToolCall, result: ToolExecutionResult, durationMs: Long): ToolExecutionResult
    suspend fun onError(context: AgentContext, cause: AgentException) {}
    suspend fun onRunFinished(context: AgentContext, result: AgentResult) {}
}
```

**Hook 组合语义**：
- `beforeToolCall` 返回非 null → 短路真实执行
- `afterToolCall` 返回值 → 链式改写结果
- 异常隔离：单个 hook 抛异常不影响主流程

### 3.6 Skill 系统

Skill 是一种按需加载的文档单元：

```kotlin
public interface Skill {
    val name: String
    val description: String
    fun load(context: SkillContext): String
}
```

**SkillTool** 适配器将 Skill 转为 Tool：
- Tool 名称 = `skill_<skill.name>`
- LLM 调用时触发 `load()` 获取完整指令

**LoadSkillTool** 是内置工具，让 LLM 主动加载技能：

```
LLM 判断需要技能 → 调用 load_skill{skill_name="xxx"}
                         │
                         ▼
               SkillRegistry.load(name)
                         │
                         ▼
               Skill.load(context) 返回详细指令
```

**LoadSkillTool** 在构造期把 `SkillRegistry` 的技能索引拼到自己的 `description` 里,作为 LLM 工具元数据暴露(随 tool 定义一起发给 LLM);**SkillRegistry** 不再触碰 `Persona`,`AgentBuilder.skills(registry)` 也不要求 `persona { }` 必须在它之前调用。

### 3.7 MCP 系统

`mcp` 模块把外部 [Model Context Protocol](https://modelcontextprotocol.io) Server 包装成 LLM 可调用的 Tool,模块内自洽实现协议(2025-06-18)的所有细节。

**核心组件**:

- **McpTransport**(sealed) — 通信传输抽象
  - `StdioTransport`:子进程,stdin/stdout 行分隔
  - `SseTransport`:Streamable HTTP,POST + Accept 头 + Content-Type 分流
- **McpServer**(interface) — 远程 MCP Server 的本地代理
- **GenericMcpServer** — `McpServer` 通用实现,封装 `initialize` 握手、工具列表缓存与失效、`ping` 等
- **McpServerRegistry** — 多 server 管理
- **LoadMcpTool**(`load_mcp_tools`) — LLM 用此发现某个 server 的工具列表
- **CallMcpTool**(`call_mcp_tool`) — LLM 用此调用已发现的 MCP 工具

**协议契约**:
- 只读 MCP 规范规定的状态字段(`isError` / `nextCursor` 等)映射到 SDK 字段
- 整体透传 JSON-RPC 的 `result` 给 LLM,不深度解析 `content` 内部结构
- 协议层错误(`response.error`)→ 抛异常 → ToolRegistry 转 `isError=true`
- 应用层错误(`result.isError=true`)→ 字段映射 → `ToolExecutionResult.isError=true`

详细设计、协议合规性审计、修复计划见 [docs/mcp-design.md](mcp-design.md)。

---

## 4. 核心使用流程

### 4.1 Agent 创建与执行

```kotlin
val agent = agent {
    persona(Persona(role = "你是一个有用的助手"))
    llmProvider(OpenAiProvider.official(apiKey))
    tool(WeatherTool())
    tool(CalculatorTool())
    skills(SkillRegistry().register(listOf(WeatherSkill(), CalculatorSkill())))
}

val result = agent.run("北京天气如何？").awaitResult()
agent.runStream("北京天气如何？").collect { event -> ... }
```

### 4.2 代码调用链

#### 场景：批式调用 run()（用户说"北京天气如何？"，Agent 使用 `get_weather` 工具）

```
┌─────────────────────────────────────────────────────────────────────────┐
│                           Agent 执行调用链（批式）                        │
└─────────────────────────────────────────────────────────────────────────┘

1. 用户调用
   agent.run("北京天气如何？")
         │
         ▼
2. ReActAgent.run()
         │
         ▼
3. loop() 开始
         │
         ├─► memory.add(ChatMessage.User("北京天气如何？"))
         │
         ▼
4. buildRequest()
   ChatRequest(
       messages = [System(persona), User("北京天气如何？")],
       tools = [ToolDefinition("get_weather", ...), ...]
   )
         │
         ▼
5. llmProvider.chat(request) ──────────────────────────────────────────┐
         │                                                          │
         ▼                                                          ▼
6. OpenAiProvider.chat()                                     LLM 云端处理
   POST /v1/chat/completions                                  (推理 + 工具调用决策)
         │                                                          │
         │◄──────────────────── Response ──────────────────────────┘
         │
         ▼
7. mapFromOpenAi() → ChatResponse(
       message = Assistant(
           content = "我来帮你查一下...",
           toolCalls = [ToolCall(id="c1", name="get_weather", arguments={...})]
       ),
       finishReason = ToolCalls
   )
         │
         ▼
8. memory.add(response.message) // Assistant + toolCalls
         │
         ▼
9. for (call in toolCalls)
         │
         ├─► emit(AgentEvent.ToolCallExplanation("我来帮你查一下..."))
         │
         ▼
10. toolRegistry.execute(call, context)
         │
         ▼
11. ToolRegistry.execute()
    byName["get_weather"]?.execute(args, context)
         │
         ▼
12. WeatherTool.execute(arguments, context)
    调用天气 API，返回结果
         │
         ▼
13. memory.add(ToolResult(toolCallId="c1", content="晴，25°C"))
         │
         ▼
14. emit(AgentEvent.ToolCallEnd(callId="c1", result=...))
         │
         ▼
15. 继续循环 → buildRequest()（含完整历史）
         │
         ▼
16. llmProvider.chat(request) ───────────────────────────────────────►┐
         │                                                             │
         ▼                                                             ▼
17. LLM 整合工具结果，生成最终回复                              LLM 云端处理
         │                                                             │
         │◄──────────────────── Response ──────────────────────────────┘
         │
         ▼
18. memory.add(finalAssistantMessage)
         │
         ▼
19. emit(AgentEvent.Final(AgentResult(message=..., iterations=2, toolCalls=[...])))
         │
         ▼
20. return
```

#### 场景：流式调用 runStream()

流式调用的 ReAct 循环（步骤 3、4、8-20）与批式完全一致，唯一差异在步骤 5-7 的 LLM 调用环节。

**差异部分（替换批式步骤 5-7）：**

```
5. llmProvider.chatStream(request) ──────────────────────────────────────┐
         │                                                              │
         ▼                                                              ▼
6. OpenAiProvider.chatStream()                               LLM 云端 SSE 流
   POST /v1/chat/completions (stream=true)                      (增量推理)
         │                                                              │
         │◄──────────────────── SSE Stream ────────────────────────────┘
         │
         ▼
7. decodeOpenAiSseLines() 逐行消费 SSE
         │
         ├─► StreamEvent.ContentDelta("我") ──► emit(AgentEvent.TextDelta("我"))
         ├─► StreamEvent.ContentDelta("来") ──► emit(AgentEvent.TextDelta("来"))
         ├─► StreamEvent.ToolCallStart("c1", "get_weather")
         ├─► StreamEvent.ToolCallDelta("c1", null, "{\"city\":") ──► 累积参数
         ├─► StreamEvent.ToolCallDelta("c1", null, "\"北京\"}") ──► 累积参数
         └─► StreamEvent.Done(usage, FinishReason.ToolCalls)
         │
         ▼
8. 累积完整 ToolCall，转为 ChatResponse
   ChatResponse(
       message = Assistant(
           content = "我来帮你查一下...",
           toolCalls = [ToolCall(id="c1", name="get_weather", arguments={...})]
       ),
       finishReason = ToolCalls
   )
         │
         ▼
9. （后续步骤与批式完全一致：memory.add → toolRegistry.execute → ... → Final）
```

**流式 vs 批式关键差异总结**：
- LLM 调用方法不同：`chat()` vs `chatStream()`
- 流式产生 `TextDelta` 事件实时推送增量文本
- 流式需要累积 `ToolCallStart/ToolCallDelta` 才能构建完整 `ToolCall`
- ReAct 循环逻辑、工具执行、memory 写入、Final 事件等**完全相同**

---

## 5. Session 与持久化

```kotlin
// Session 创建
val sessionManager = SessionManager(sessionParentDir)
val session = sessionManager.create(userId, "我的助手")

// Session 关联 Memory
val agent = agent {
    memory(session.memory)  // JsonlBackedMemory
    llmProvider(...)
    tool(...)
}
```

Session 持久化结构：
- 每个 Session 有独立目录
- `messages.jsonl` 存储历史消息
- 支持多会话并发管理（Mutex 保护）

---

## 6. Hook 扩展点

```kotlin
// 组合多个 Hook
val composite = CompositeHook(
    hooks = listOf(metricsHook, authHook)
)

// 工具拦截示例
val toolInterceptionHook = object : AgentHook {
    override suspend fun beforeToolCall(
        context: AgentContext,
        call: ToolCall
    ): ToolExecutionResult? {
        return if (call.name == "forbidden_tool") {
            ToolExecutionResult(content = "Access denied", isError = true)
        } else null  // 继续正常执行
    }
}
```

---

## 7. Provider 实现要点

### 7.1 OpenAI Provider

- HTTP POST `/v1/chat/completions`
- SSE 流式响应解码
- 请求映射：`mapToOpenAi()` 将 `ChatRequest` 转为 OpenAI DTO
- 响应映射：`mapFromOpenAi()` 将 OpenAI DTO 转为 `ChatResponse`

### 7.2 Anthropic Provider

- HTTP POST `/v1/messages`
- Anthropic 版本头 `anthropic-version: 2023-06-01`
- SSE 流式响应解码
- 差异处理：Anthropic 不支持 `stopSequences`，使用 `messages` 而非 `tools`

---

## 8. 异常处理模型

```kotlin
public sealed class AgentException(message: String, cause: Throwable? = null) : Exception(message, cause) {
    class MaxIterations(val max: Int) : AgentException("Max iterations $max reached")
    class LlmError(cause: Throwable) : AgentException("LLM error: ${cause.message}", cause)
    class InvalidResponse(message: String) : AgentException("Invalid response: $message")
    class ToolExecution(message: String, cause: Throwable? = null) : AgentException(message, cause)
}

internal fun Throwable.toAgentException(): AgentException = ...
```

**异常转换点**：
- LLM 调用失败 → `LlmError`
- 响应解析失败 → `InvalidResponse`
- 工具执行异常（非 CancellationException）→ `ToolExecution`
- 达到最大迭代 → `MaxIterations`

---

## 9. 配置校验

**AgentBuilder** 构建时校验：
- `llmProvider` **必须**设置
- 工具名称不允许重复（ToolRegistry 注册时校验）
- `maxIterations` 必须 > 0

---

## 10. 依赖关系

### 10.1 模块依赖拓扑

```
app (Android Demo)
 │
 ├── agent             ← 核心模块
 ├── hook              ← 生命周期钩子
 ├── session           ← 会话管理
 ├── skill             ← 技能管理
 ├── providers/openai  ← OpenAI Provider
 └── providers/anthropic ← Anthropic Provider

agent
 │
 └── (无内部模块依赖，仅依赖外部库 kotlinx.coroutines/kotlinx.serialization)

hook
 │
 ├── agent             ← 依赖 agent 的 AgentHook 接口
 └── session           ← 依赖 session 的 SessionHook 接口

session
 └── agent             ← 依赖 agent 的 Memory 接口

skill
 └── agent             ← 依赖 agent 的 Tool/Memory 接口

mcp
 └── agent             ← 依赖 agent 的 Tool 接口,封装 MCP 2025-06-18 协议

providers/openai
 └── agent             ← 依赖 agent 的 LlmProvider 接口

providers/anthropic
 └── agent             ← 依赖 agent 的 LlmProvider 接口
```
```

**说明**：
- `app` 作为顶层应用，扁平依赖所有模块（agent、hook、session、skill、providers），通过 `agent {}` DSL 组合各模块的实现
- `agent` 是核心模块，定义 `Agent`、`Tool`、`Memory`、`LlmProvider`、`AgentHook` 等核心抽象，本身不依赖任何内部模块，仅依赖外部库
- `hook` 依赖 `agent`（`AgentHook`）和 `session`（`SessionHook`），因为 `Hook` 接口同时继承两者，实现一个 hook 可同时参与 Agent 和 Session 生命周期
- `session` 依赖 `agent` 的 `Memory` 接口，实现 `JsonlBackedMemory`
- `skill` 依赖 `agent` 的 `Tool` 接口和 `ToolRegistry`，实现技能到工具的适配
- `providers` 依赖 `agent` 的 `LlmProvider` 接口，实现具体 LLM 后端协议转换

### 10.2 运行时组合示例

```
app 构建 Agent 时的依赖注入：

DemoAgentFactory.create()
  │
  ├─► agent {} DSL
  │     │
  │     ├─ llmProvider(OpenAiProvider(...))    ← providers 模块
  │     ├─ tool(GetWeatherTool())             ← app 自定义 Tool
  │     ├─ memory(JsonlBackedMemory(...))      ← session 模块
  │     ├─ hook(CompositeHook(...))           ← hook 模块
  │     └─ skills(SkillRegistry(...))          ← skill 模块
  │
  ▼
ReActAgent 实例
  (依赖注入：llmProvider, toolRegistry, memory, hook)
```

**注**：`skills(SkillRegistry(...))` 内部走 `tool(LoadSkillTool(registry))`，不再向 `Persona` 注入索引，因而与 `persona { }` 的调用顺序无依赖。

### 10.3 编译时依赖（build.gradle.kts）

| 模块 | 直接依赖 |
|------|---------|
| `app` | `agent`, `providers/openai`, `providers/anthropic`, `hook`, `session`, `skill`, `mcp` |
| `agent` | 无内部模块依赖（仅依赖 kotlinx.coroutines、kotlinx.serialization） |
| `hook` | `agent`, `session` |
| `session` | `agent` |
| `skill` | `agent` |
| `providers/openai` | `agent` |
| `providers/anthropic` | `agent` |
