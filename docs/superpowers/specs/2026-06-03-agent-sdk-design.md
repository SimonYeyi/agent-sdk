# Agent SDK 设计文档

> **v1 设计 · Android 平台优先 · Kotlin 实现**
> 日期：2026-06-03 · 状态：Draft（待用户审阅）

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 项目代号 | agent-sdk |
| v1 目标语言 | Kotlin / JVM（Android 7.0+） |
| v1 目标平台 | Android（次要：JVM 桌面/服务端） |
| v1 包名（顶层） | `io.github.yeyi.agent` |
| 设计阶段 | v1 核心 + v2+ 路线图 |
| Python 移植 | v2 独立实现（不共享代码，共享协议规范） |

---

## 1. 概述与目标

### 1.1 一句话定义
**agent-sdk** 是一个可作为 Android 依赖引入的 Kotlin SDK，让开发者能在自己的 App 中快速构建**能调用工具的 LLM Agent**。

### 1.2 核心目标
- **降低 Android 端 LLM Agent 开发门槛**：从"自己写 HTTP、调 LLM API、解析 tool calling" → 变成"写业务 Tool + 用 DSL 拼装 Agent"
- **核心抽象稳定**：4 个核心接口（LlmClient / Tool / Memory / Agent）发布后 1 年内不破坏
- **可扩展**：业务 Tool、LLM Provider、Memory 后端、Agent 算法都可插拔
- **Python 移植友好**：v2 用 Python 重写时不需要重新设计协议

### 1.3 非目标（v1 明确不做）
- ❌ 持久化 Memory（Room/Disk）
- ❌ Token 截断 / 历史压缩
- ❌ Tool 自动发现（反射/ServiceLoader）
- ❌ MCP / Skills / 任何上层工具协议
- ❌ 多 Agent / Plan-and-Execute
- ❌ 结构化输出（JSON schema 强制）
- ❌ 会话 ID / 会话路由（业务层职责）
- ❌ 可观测性 / 缓存 / 重试 / 限流
- ❌ 跨平台（KMP / iOS / Web）
- ❌ Skill 按需激活 / 文件发现 / Slash 命令（v2.1+）
- ❌ Skill 自学习 / 自进化（v3+）

---

## 2. 关键概念区分

| 概念 | 含义 | SDK 是否实现 | 备注 |
|---|---|---|---|
| **Provider** | `LlmClient` 接口的具体实现（OpenAiClient 等） | ✅ **SDK 实现** | 协议转换是 SDK 职责 |
| **Tool** | 业务工具（订单查询、天气、时间等） | ❌ **SDK 不实现** | 业务 Tool 是用户的事 |
| **Memory** | 消息存储抽象 | ✅ 接口 + `InMemoryMemory` | v1 唯一实现 |
| **Agent** | 编排算法（ReAct 等） | ✅ 接口 + `ReActAgent` | 唯一算法实现 |
| **Skill** | `Tool` + 系统提示片段的可复用打包单位 | ✅ 数据类 + DSL 工厂 | v1 静态注册，v2.1+ 加动态加载 |
| **Session** | 一次对话的业务概念 | ❌ SDK 不做 | 调用方持有 `Memory` 实例即可 |

---

## 3. 模块结构

### 3.1 顶层结构（1+1+n）

```
agent-sdk/                                       ← 根项目
├── core/                                        ← 模块 1：核心抽象 + ReAct
├── providers/                                   ← 目录（仅组织意义，不参与 Gradle composite）
│   ├── openai/                                  ← 模块 2：OpenAI 兼容实现
│   └── anthropic/                               ← 模块 3：Anthropic 原生实现
├── app/                                         ← 模块 4：Sample Android App
├── docs/
│   └── superpowers/
│       └── specs/
│           └── 2026-06-03-agent-sdk-design.md   ← 本文件
├── settings.gradle.kts
├── build.gradle.kts
├── gradle/
│   └── libs.versions.toml
└── README.md
```

### 3.2 依赖关系

| 模块 | 依赖 | 不依赖 |
|---|---|---|
| `core` | kotlinx-coroutines、kotlinx-serialization | 任何 HTTP 客户端、任何 Provider |
| `providers/openai` | `core`、Ktor Client、kotlinx-serialization-json | 其他 provider、Sample App |
| `providers/anthropic` | `core`、Ktor Client、kotlinx-serialization-json | 其他 provider、Sample App |
| `app` | `core`、`providers/openai`（或 anthropic） | 任何其他实现 |

**关键不变量**：`core` 模块**绝不**引用任何 provider、任何 HTTP 客户端、任何 Android SDK 类。这是 v1 → v2 抽象稳定的物理保证。

### 3.3 包名映射

| 模块 | Gradle `namespace` | Kotlin 包 |
|---|---|---|
| `core` | `io.github.yeyi.agent.core` | `io.github.yeyi.agent.core.*` |
| `providers/openai` | `io.github.yeyi.agent.providers.openai` | `io.github.yeyi.agent.providers.openai` |
| `providers/anthropic` | `io.github.yeyi.agent.providers.anthropic` | `io.github.yeyi.agent.providers.anthropic` |
| `app` | `io.github.yeyi.agent.app` | `io.github.yeyi.agent.app.*` |

**内部子包约定**（以 `core` 为例）：
```
io.github.yeyi.agent.core
├── agent/        # Agent 接口、ReActAgent、AgentBuilder
├── llm/          # LlmClient、ChatRequest/Response、StreamEvent
├── tool/         # Tool、ToolParameters、ToolContext、ToolExecutionResult
├── memory/       # Memory、InMemoryMemory
├── error/        # AgentException 体系
├── internal/     # 内部工具，禁止 SDK 用户引用
└── providers/    # Provider 共用层（如 ProviderSupport）
```

---

## 4. 核心抽象

所有类型在 `core` 模块，**不依赖任何具体 LLM 协议**。

### 4.1 消息与角色

```kotlin
// 消息 - sealed interface 表达 4 种角色
sealed interface ChatMessage {
    val role: Role

    data class System(val content: String) : ChatMessage {
        override val role = Role.System
    }
    data class User(val content: String) : ChatMessage {
        override val role = Role.User
    }
    data class Assistant(
        val content: String? = null,                  // 可空：可能只有 toolCalls
        val toolCalls: List<ToolCall> = emptyList()
    ) : ChatMessage {
        override val role = Role.Assistant
    }
    data class ToolResult(
        val toolCallId: String,                        // 关联 Assistant.toolCalls[i].id
        val toolName: String,
        val content: String,                            // 序列化为字符串给 LLM
        val isError: Boolean = false
    ) : ChatMessage {
        override val role = Role.Tool
    }
}

enum class Role { System, User, Assistant, Tool }

data class ToolCall(
    val id: String,
    val name: String,
    val arguments: JsonElement                         // kotlinx.serialization JsonElement
)
```

**为什么用 `JsonElement` 而非强类型**：Python 端 `dict` 是天然动态类型；`JsonElement` 跨语言时心智成本最低，v2 移植时直接对应 Python 的 `dict`/`Any`。

**`toolCalls` 的语义**：LLM 在 assistant message 中**请求调用**的工具（意图层），不是执行结果。执行结果在后续 `ToolResult` 消息里。

### 4.2 LLM 客户端接口

```kotlin
interface LlmClient {
    val providerName: String                            // "openai" | "anthropic" | ...

    suspend fun chat(request: ChatRequest): ChatResponse
    fun chatStream(request: ChatRequest): Flow<StreamEvent>
}

data class ChatRequest(
    val messages: List<ChatMessage>,
    val tools: List<ToolDefinition> = emptyList(),
    val temperature: Double? = null,
    val maxTokens: Int? = null,
    val stopSequences: List<String> = emptyList()
)

data class ToolDefinition(
    val name: String,
    val description: String,
    val parametersSchema: ToolParameters                // 见 4.3
)

data class ChatResponse(
    val message: ChatMessage.Assistant,                 // 直接是 Assistant，不包 ChatMessage
    val usage: Usage? = null,
    val finishReason: FinishReason
)

data class Usage(
    val promptTokens: Int,
    val completionTokens: Int,
    val totalTokens: Int
)

enum class FinishReason { Stop, ToolCalls, Length, Error }

sealed interface StreamEvent {
    data class ContentDelta(val text: String) : StreamEvent
    data class ToolCallDelta(
        val id: String?,
        val name: String?,
        val argumentsDelta: String                       // 增量片段，consumer 自行拼装
    ) : StreamEvent
    data class Done(val usage: Usage?) : StreamEvent
    data class Error(val cause: Throwable) : StreamEvent
}
```

### 4.3 Tool 接口

```kotlin
interface Tool {
    val name: String
    val description: String
    val parametersSchema: ToolParameters
    suspend fun execute(args: JsonElement, ctx: ToolContext): ToolExecutionResult
}

sealed interface ToolParameters {
    object Empty : ToolParameters
    data class JsonSchema(val schema: String) : ToolParameters   // JSON Schema 字符串
}

data class ToolExecutionResult(
    val content: String,                                 // 给 LLM 看的字符串
    val isError: Boolean = false
)

data class ToolContext(
    val invocationId: String = UUID.randomUUID().toString(),
    val metadata: Map<String, String> = emptyMap()
)
```

**v1 不做 TypedTool（`Tool<I, O>`）**：YAGNI。`JsonElement` + `Json.decodeFromJsonElement<T>(args)` 在调用方做类型转换就够清晰。v2 Python 端用 Pydantic 也能做这件事。

**v1 不做自动 JSON Schema 生成**：Tool 自己写 schema 字符串（手写或用 KSP）。v2 可考虑 KSP 注解处理器。

### 4.4 Memory

```kotlin
interface Memory {
    suspend fun add(message: ChatMessage)
    suspend fun history(): List<ChatMessage>
    suspend fun clear()
}

class InMemoryMemory : Memory {                          // v1 唯一实现
    private val messages: MutableList<ChatMessage> = mutableListOf()
    private val mutex: Mutex = Mutex()

    override suspend fun add(message: ChatMessage): Unit = mutex.withLock {
        messages += message
    }
    override suspend fun history(): List<ChatMessage> = mutex.withLock {
        messages.toList()
    }
    override suspend fun clear(): Unit = mutex.withLock {
        messages.clear()
    }
}
```

**v1 立场**：SDK 不做持久化、不做 token 截断、不做历史压缩。调用方持有 `Memory` 实例即可。详情见第 11 章。

### 4.5 Agent 主体

```kotlin
interface Agent {
    val config: AgentConfig

    /** 单轮：不维护历史（每次 run 新建 InMemoryMemory） */
    suspend fun run(input: String): AgentResult

    /** 多轮：传入 memory 保留历史 */
    suspend fun run(input: String, memory: Memory): AgentResult

    /** 流式：边生成边 yield 事件 */
    fun runStream(input: String, memory: Memory): Flow<AgentEvent>
}

data class AgentConfig(
    val systemPrompt: String,
    val llmClient: LlmClient,
    val tools: List<Tool>,
    val memoryFactory: () -> Memory,
    val maxIterations: Int,
    val hooks: List<AgentHook> = emptyList()                // v1 新增：生命周期回调
)

data class AgentResult(
    val finalMessage: ChatMessage.Assistant,
    val memory: Memory,                                    // 更新后的 memory（caller 可继续持有）
    val iterations: Int,
    val toolCalls: List<ToolCallRecord>                    // 本次 run 触发的所有工具调用
)

data class ToolCallRecord(
    val callId: String,
    val toolName: String,
    val arguments: JsonElement,
    val result: ToolExecutionResult,
    val timestamp: java.time.Instant
)

sealed interface AgentEvent {
    data class TextDelta(val text: String) : AgentEvent
    data class ToolCallStarted(val callId: String, val toolName: String) : AgentEvent
    data class ToolCallFinished(val callId: String, val result: ToolExecutionResult) : AgentEvent
    data class Final(val message: ChatMessage.Assistant) : AgentEvent
    data class Failed(val cause: Throwable) : AgentEvent
}

/**
 * Agent 生命周期回调。所有方法都有默认 no-op 实现，consumer 按需 override。
 *
 * **关键契约**：
 * 1. Hook 抛出的异常**不影响主流程**——会被吞掉并通过 `onError` 之外的辅助通道记日志
 * 2. Hook 不应阻塞或 sleep——可能影响 agent 整体延迟
 * 3. Hook 不能修改 `AgentConfig`/`Memory`——只读视图
 * 4. Hook 调用顺序：BeforeLlmCall → LlmCall → AfterLlmResponse → (BeforeToolCall → ToolCall → AfterToolCall)* → AfterRun
 */
interface AgentHook {
    suspend fun beforeLlmCall(iteration: Int, messages: List<ChatMessage>) {}
    suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {}
    suspend fun beforeToolCall(call: ToolCall) {}
    suspend fun afterToolCall(call: ToolCall, result: ToolExecutionResult, durationMs: Long) {}
    suspend fun onError(iteration: Int, cause: Throwable) {}
    suspend fun onRunFinished(result: AgentResult) {}
}

/** v1 默认提供的 NoOp hook */
object NoOpAgentHook : AgentHook
```

### 4.6 配置 DSL

```kotlin
class AgentBuilder {
    var systemPrompt: String = ""
    var llmClient: LlmClient? = null
    var maxIterations: Int = 10
    private val tools: MutableList<Tool> = mutableListOf()
    private val skills: MutableList<Skill> = mutableListOf()
    private var memoryFactory: () -> Memory = { InMemoryMemory() }
    private val hooks: MutableList<AgentHook> = mutableListOf()

    fun tool(t: Tool) { tools += t }
    fun tools(ts: Iterable<Tool>) { tools += ts }
    fun skill(s: Skill) { skills += s }
    fun skills(ss: Iterable<Skill>) { skills += ss }
    fun memory(f: () -> Memory) { memoryFactory = f }
    fun hook(h: AgentHook) { hooks += h }

    fun build(): Agent {
        val client = requireNotNull(llmClient) { "llmClient must be set" }
        if (systemPrompt.isBlank() && tools.isEmpty() && skills.isEmpty()) {
            warn("Agent has no system prompt, no tools, and no skills; useful only for pure chat.")
        }

        // Skill 在 build() 时被展开为 systemPrompt + tools
        val combinedPrompt = buildString {
            append(systemPrompt)
            for (s in skills) {
                if (s.systemPromptFragment.isNotBlank()) {
                    if (isNotEmpty()) append("\n\n")
                    append(s.systemPromptFragment)
                }
            }
        }
        val allTools = tools + skills.flatMap { it.tools }
        val allToolsByName = allTools.associateBy { it.name }
        require(allTools.size == allToolsByName.size) {
            "Duplicate tool names after flattening: ${allTools.groupBy { it.name }.filter { it.value.size > 1 }.keys}"
        }

        val config = AgentConfig(
            systemPrompt = combinedPrompt,
            llmClient = client,
            tools = allTools,
            memoryFactory = memoryFactory,
            maxIterations = maxIterations,
            hooks = hooks.toList()
        )
        return ReActAgent(config)
    }
}

fun agent(block: AgentBuilder.() -> Unit): Agent = AgentBuilder().apply(block).build()
```

**典型用法**：
```kotlin
val myAgent = agent {
    systemPrompt = "You are a helpful Android assistant."
    llmClient = OpenAiClient(
        apiKey = BuildConfig.OPENAI_API_KEY,
        baseUrl = "https://api.openai.com/v1",
        model = "gpt-4o-mini"
    )
    maxIterations = 8

    // 方式 1：直接注册 Tool
    tool(MyOrderTool(orderApi))

    // 方式 2：注册 Skill（自动展开为 systemPrompt + tools）
    skill(MyWeatherSkill())     // = 一组 Tool + "你是天气助手"提示

    // 方式 3：可选 hook
    hook(LoggingHook())
}

val memory = InMemoryMemory()
val result = myAgent.run("现在几点？帮我算 3*7+2", memory)
println(result.finalMessage.content)
```

### 4.7 Skill（v1 提权）

**Skill 是"一组 Tool + 一段系统提示片段"的可复用打包单位**——把一类 agent 用例的所有"配料"封装成一个对象。

```kotlin
// Skill 数据类（v1：纯静态描述）
data class Skill(
    val name: String,
    val description: String,                                  // 给 LLM 看的简介
    val systemPromptFragment: String = "",                    // 拼接到 agent.systemPrompt
    val tools: List<Tool> = emptyList()                       // 附加到 agent.tools
)

// 工厂 DSL（更友好的写法）
class SkillBuilder {
    var description: String = ""
    var systemPromptFragment: String = ""
    private val tools: MutableList<Tool> = mutableListOf()
    fun tool(t: Tool) { tools += t }
    fun build(name: String): Skill = Skill(name, description, systemPromptFragment, tools.toList())
}

fun skill(name: String, block: SkillBuilder.() -> Unit): Skill =
    SkillBuilder().apply(block).build(name)
```

**典型 Skill 例子**：
```kotlin
// 方式 1：纯静态描述
val codeReviewer = Skill(
    name = "code_reviewer",
    description = "Reviews code diffs for style and correctness issues.",
    systemPromptFragment = """
        You are a code reviewer. When given a diff:
        1. Identify style issues (naming, structure)
        2. Identify potential bugs (nulls, race conditions)
        3. Suggest concrete improvements
        Cite line numbers. Be terse.
    """.trimIndent(),
    tools = listOf(ReadFileTool(), GitDiffTool(), ShellTool(allowed = listOf("git", "grep")))
)

// 方式 2：工厂 DSL
val weatherAssistant = skill("weather_assistant") {
    description = "Answers weather-related questions."
    systemPromptFragment = "You are a weather assistant. Always check current data before answering."
    tool(GetCurrentWeatherTool())
    tool(GetForecastTool())
}

// 拼装 Agent
val myAgent = agent {
    systemPrompt = "You are a multi-purpose assistant."   // 基础 prompt
    llmClient = llm
    skill(codeReviewer)                                  // 追加提示 + 工具
    skill(weatherAssistant)
}
// 等价于：
//   systemPrompt = "You are a multi-purpose assistant." + "\n\n" + "You are a code reviewer..." + "\n\n" + "You are a weather assistant..."
//   tools = [ReadFileTool, GitDiffTool, ShellTool, GetCurrentWeatherTool, GetForecastTool]
```

**v1 vs v2+ Skill 能力对比**：

| 能力 | v1 | v2.1+ |
|---|---|---|
| 静态数据类（name + prompt + tools） | ✅ | ✅ |
| DSL 工厂（`skill(name) { ... }`） | ✅ | ✅ |
| 注册到 Agent（`agent { skill(...) }`） | ✅ | ✅ |
| Skill 名称冲突检测 | ✅（build 时） | ✅ |
| `SkillRegistry`（从文件/网络加载） | ❌ | ✅ |
| 按需激活（运行时由 LLM 选择加载哪些） | ❌ | ✅ |
| Slash 命令（`/skill foo`） | ❌ | ✅ |
| Skill 元数据（version、author、tags） | ❌ | ✅ |
| Skill 自学习/自进化 | ❌ | ❌（v3+） |

**Skill 与 Tool 的边界**：
- **Tool**：原子能力（"读文件"、"查天气"），可被任意 agent 复用
- **Skill**：场景化打包（"代码审查员" = 3 个 Tool + 1 段提示），是**面向 agent 用例**的复用单位
- 同一个 Tool 可被多个 Skill 包含
- Skill 可以只含提示不含 Tool（纯知识型 Skill）

---

## 5. ReAct 主循环

### 5.1 算法（非流式）

```kotlin
internal class ReActAgent(private val config: AgentConfig) : Agent {

    override suspend fun run(input: String, memory: Memory): AgentResult {
        memory.add(ChatMessage.User(input))
        val toolCallRecords = mutableListOf<ToolCallRecord>()
        var iterations = 0

        while (iterations < config.maxIterations) {
            iterations++
            ensureActive()                                              // 响应 coroutine 取消

            val request = buildRequest(memory)
            val response = config.llmClient.chat(request)
            memory.add(response.message)

            if (response.message.toolCalls.isEmpty()) {
                return AgentResult(
                    finalMessage = response.message,
                    memory = memory,
                    iterations = iterations,
                    toolCalls = toolCallRecords.toList()
                )
            }

            for (call in response.message.toolCalls) {
                val result = invokeTool(call)
                toolCallRecords += ToolCallRecord(
                    callId = call.id,
                    toolName = call.name,
                    arguments = call.arguments,
                    result = result,
                    timestamp = java.time.Instant.now()
                )
                memory.add(ChatMessage.ToolResult(
                    toolCallId = call.id,
                    toolName = call.name,
                    content = result.content,
                    isError = result.isError
                ))
            }
        }

        throw AgentMaxIterationsException(
            "Reached max iterations (${config.maxIterations}) without final answer"
        )
    }

    private fun buildRequest(memory: Memory): ChatRequest = ChatRequest(
        messages = buildList {
            if (config.systemPrompt.isNotBlank()) add(ChatMessage.System(config.systemPrompt))
            addAll(memory.history())
        },
        tools = config.tools.map { ToolDefinition(it.name, it.description, it.parametersSchema) }
    )

    private suspend fun invokeTool(call: ToolCall): ToolExecutionResult {
        val tool = config.tools.find { it.name == call.name }
            ?: return ToolExecutionResult(
                content = "Tool '${call.name}' not found. Available: ${config.tools.joinToString { it.name }}",
                isError = true
            )
        return try {
            tool.execute(call.arguments, ToolContext())
        } catch (t: Throwable) {
            if (t is CancellationException) throw t                      // 取消必须传播
            ToolExecutionResult(content = "Tool error: ${t.message}", isError = true)
        }
    }
}
```

### 5.2 算法（流式）

```kotlin
override fun runStream(input: String, memory: Memory): Flow<AgentEvent> = flow {
    memory.add(ChatMessage.User(input))
    var iterations = 0

    while (iterations < config.maxIterations) {
        iterations++
        ensureActive()

        val request = buildRequest(memory)
        var accumulatedText: String? = null
        val accumulatedCalls: MutableList<ToolCall> = mutableListOf()
        val argumentsBuffers: MutableMap<String, StringBuilder> = mutableMapOf()

        // 1. 流式消费 LLM 输出
        config.llmClient.chatStream(request).collect { event ->
            when (event) {
                is StreamEvent.ContentDelta -> {
                    accumulatedText = (accumulatedText ?: "") + event.text
                    emit(AgentEvent.TextDelta(event.text))
                }
                is StreamEvent.ToolCallDelta -> {
                    val id = event.id ?: return@collect                  // 第一次 delta 才有 id
                    val buf = argumentsBuffers.getOrPut(id) { StringBuilder() }
                    buf.append(event.argumentsDelta)
                    if (event.name != null && accumulatedCalls.none { it.id == id }) {
                        accumulatedCalls += ToolCall(id = id, name = event.name, arguments = JsonNull)
                    }
                }
                is StreamEvent.Done -> Unit
                is StreamEvent.Error -> throw event.cause
            }
        }

        // 2. 把已累积的 assistant message 入 memory
        val finalCalls = accumulatedCalls.map { call ->
            call.copy(arguments = argumentsBuffers[call.id]?.toString()?.let {
                Json.parseToJsonElement(it)
            } ?: JsonNull)
        }
        val assistantMsg = ChatMessage.Assistant(accumulatedText, finalCalls)
        memory.add(assistantMsg)

        // 3. 没有 tool call → 终态
        if (finalCalls.isEmpty()) {
            emit(AgentEvent.Final(assistantMsg))
            return@flow
        }

        // 4. 执行 tool，emit 事件，回灌结果
        for (call in finalCalls) {
            emit(AgentEvent.ToolCallStarted(call.id, call.name))
            val result = invokeTool(call)
            emit(AgentEvent.ToolCallFinished(call.id, result))
            memory.add(ChatMessage.ToolResult(
                toolCallId = call.id, toolName = call.name,
                content = result.content, isError = result.isError
            ))
        }
    }

    throw AgentMaxIterationsException("Reached max iterations (${config.maxIterations})")
}
```

### 5.3 取消与错误语义
- 所有 `suspend` 方法尊重 coroutine 取消
- 循环顶部 `ensureActive()` 检查
- `invokeTool` 内部吞业务异常（转 `ToolResult(isError=true)` 喂回 LLM），**不吞 `CancellationException`**
- LLM 错误抛 `LlmError`（见第 8 章）
- 超过 max iterations 抛 `AgentMaxIterationsException`

### 5.4 线程安全契约
- `ReActAgent` 实例是 **immutable + stateless**（除 `config` 外不持有可变状态）
- 多个并发 `run()` / `runStream()` 调用同一个 `Agent` 实例是**安全的**
- `Memory` 是**唯一可变**的状态——并发安全性由 Memory 实现者负责
- LLM Provider 实现必须**线程安全**（`OpenAiClient` / `AnthropicClient` 内部 HTTP client 共享）

### 5.5 Tool 取消契约
- 工具自己 `suspend` 的内部操作必须响应 `coroutine` 取消
- 工具占用的资源（文件句柄、网络连接、临时文件）应在 `finally` / `use {}` 中清理
- 工具**不应**自己 `try/catch CancellationException` 后吞掉——会破坏上层取消语义
- 工具可以**额外**清理（如回滚事务），但必须在 catch 块**重新抛** `CancellationException`

### 5.6 Hook 集成点
- `ReActAgent` 在以下时点调用 `config.hooks` 中的所有 `AgentHook`：
  - 每次 LLM 调用前：`beforeLlmCall(iteration, messages)`
  - 每次 LLM 响应后：`afterLlmResponse(iteration, response)`
  - 每次 Tool 调用前：`beforeToolCall(call)`
  - 每次 Tool 调用后：`afterToolCall(call, result, durationMs)`
  - 错误发生时：`onError(iteration, cause)`
  - Run 结束时：`onRunFinished(result)`
- Hook 异常被吞掉并通过 SDK 内部 logger 记录（不向主流程传播）

---

## 6. Provider 架构

### 6.1 HTTP 客户端选型

**采用 Ktor Client 3.x + CIO engine**：

| 维度 | Ktor 3.x + CIO | OkHttp + Retrofit |
|---|---|---|
| Coroutines 原生 | ✅ 一等公民 | ⚠️ 需 adapter |
| SSE/Streaming | ✅ `bodyAsChannel().lineFlow()` | ⚠️ 需下沉到 OkHttp source |
| 跨平台潜力 | ✅ 可换 engine | ❌ JVM-only |
| Android 圈熟悉度 | 中 | 高 |

**选 Ktor 的核心理由**：streaming 是 LLM 体验的关键，`bodyAsChannel().lineFlow()` 比走 OkHttp `Source` 干净一个量级。

### 6.2 Provider 共用层

放在 `core` 模块的 `io.github.yeyi.agent.core.providers` 包，避免每个 provider 重复：

```kotlin
internal object ProviderSupport {
    fun HttpClientConfig<*>.defaultConfig() {
        install(ContentNegotiation) { json(Json { ignoreUnknownKeys = true }) }
        install(HttpTimeout) {
            requestTimeoutMillis = 60_000
            socketTimeoutMillis  = 60_000
        }
    }
}
```

### 6.3 OpenAI 兼容 Provider

**位置**：`providers/openai/io.github.yeyi.agent.providers.openai`

**入口**：
```kotlin
class OpenAiClient(
    private val apiKey: String,
    private val model: String = "gpt-4o-mini",
    private val baseUrl: String = "https://api.openai.com/v1",
    private val httpClient: HttpClient = defaultHttpClient()
) : LlmClient {
    override val providerName = "openai"
    override suspend fun chat(request: ChatRequest): ChatResponse = ...
    override fun chatStream(request: ChatRequest): Flow<StreamEvent> = ...
}
```

**支持的 OpenAI 兼容服务**（同一份代码可指向）：
- OpenAI 官方（`https://api.openai.com/v1`）
- DeepSeek（`https://api.deepseek.com/v1`）
- 阿里云 DashScope（OpenAI 兼容模式）
- LM Studio、Ollama（OpenAI 兼容模式开启后）

**OpenAI 工具调用格式**：
```json
// 请求
{
  "model": "gpt-4o-mini",
  "messages": [...],
  "tools": [{
    "type": "function",
    "function": {
      "name": "get_weather",
      "description": "Get current weather",
      "parameters": { "type": "object", "properties": {...} }
    }
  }]
}

// 响应
{
  "choices": [{
    "message": {
      "role": "assistant",
      "content": null,
      "tool_calls": [{
        "id": "call_abc",
        "type": "function",
        "function": { "name": "get_weather", "arguments": "{\"city\":\"Beijing\"}" }
      }]
    },
    "finish_reason": "tool_calls"
  }]
}
```

**SDK 内部转换**：`Tool.parametersSchema` → `tools[].function.parameters`；`response.choices[0].message.tool_calls[]` → `List<ToolCall>`。

### 6.4 Anthropic Provider

**位置**：`providers/anthropic/io.github.yeyi.agent.providers.anthropic`

**入口**：
```kotlin
class AnthropicClient(
    private val apiKey: String,
    private val model: String = "claude-sonnet-4-6",
    private val baseUrl: String = "https://api.anthropic.com",
    private val httpClient: HttpClient = defaultHttpClient()
) : LlmClient {
    override val providerName = "anthropic"
    override suspend fun chat(request: ChatRequest): ChatResponse = ...
    override fun chatStream(request: ChatRequest): Flow<StreamEvent> = ...
}
```

**Anthropic 工具调用格式**：
```json
// 请求（注意 system 在顶层，不在 messages）
{
  "model": "claude-sonnet-4-6",
  "system": "...",
  "messages": [...],
  "tools": [{
    "name": "get_weather",
    "description": "Get current weather",
    "input_schema": { "type": "object", "properties": {...} }
  }]
}

// 响应（tool_use 是 content 数组中的 block）
{
  "content": [
    { "type": "text", "text": "我需要查一下天气" },
    { "type": "tool_use", "id": "toolu_abc", "name": "get_weather", "input": {"city": "Beijing"} }
  ],
  "stop_reason": "tool_use"
}

// 回灌 tool result（role=user, content 是 tool_result block）
{
  "role": "user",
  "content": [{
    "type": "tool_result",
    "tool_use_id": "toolu_abc",
    "content": "北京：25°C 晴"
  }]
}
```

**SDK 内部转换**：
- `Tool.parametersSchema` → `tools[].input_schema`（字段名不同）
- `response.content[]` 中 `tool_use` block → `ToolCall`
- 回灌时 `ToolResult` → 下一条 `role=user` 的 `tool_result` block

**Headers 差异**：
```
Authorization: 不使用
x-api-key: <apiKey>
anthropic-version: 2023-06-01
```

### 6.5 流式事件映射

| Anthropic SSE Event | 映射到 StreamEvent |
|---|---|
| `message_start` | （忽略，等待 content_block） |
| `content_block_start` | （开始累积） |
| `content_block_delta` (text_delta) | `ContentDelta(text)` |
| `content_block_delta` (input_json_delta) | `ToolCallDelta(argumentsDelta)` |
| `content_block_stop` | （当前 block 结束） |
| `message_delta` (stop_reason) | （决定 finishReason） |
| `message_stop` | `Done(usage)` |

---

## 7. 构建系统

### 7.1 版本矩阵

| 项 | 值 | 备注 |
|---|---|---|
| Gradle | 9.2.1-bin | 需 JDK 17+ |
| AGP | 9.1.1 | 配套 Gradle 9.0+ |
| Kotlin | 2.2.0 | AGP 9.x 建议 2.1+，2.2 稳定 |
| kotlinx-coroutines | 1.10.1 | 与 Kotlin 2.2 配套 |
| kotlinx-serialization | 1.8.3 | 与 Kotlin 2.2 配套 |
| Ktor | 3.0.3 | 3.x 稳定，CIO engine |
| Compose BOM | 2025.04.00 | Sample App 用 |
| minSdk | 24 | 覆盖 97%+ 设备 |
| targetSdk | 36 | Android 16 |
| compileSdk | 36 | 与 target 对齐 |
| JVM target | 17 | AGP 9.x + Kotlin 2.2 推荐 |
| JDK 运行时 | 17 LTS | 构建机需 17+ |

### 7.2 Version Catalog（`gradle/libs.versions.toml`）

```toml
[versions]
kotlin = "2.2.0"
agp = "9.1.1"
coroutines = "1.10.1"
serialization = "1.8.3"
ktor = "3.0.3"
compose-bom = "2025.04.00"

[libraries]
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
kotlinx-serialization-json = { module = "org.jetbrains.kotlinx:kotlinx-serialization-json", version.ref = "serialization" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
ktor-client-content-negotiation = { module = "io.ktor:ktor-client-content-negotiation", version.ref = "ktor" }
ktor-serialization-kotlinx-json = { module = "io.ktor:ktor-serialization-kotlinx-json", version.ref = "ktor" }
```

### 7.3 发布配置
- `core` / `providers/*` 都用 `maven-publish` + `signing` 插件
- v0.1.0 通过 `mavenLocal()` / GitHub Packages 验证
- Maven Central：v1 稳定后申请 namespace + 配置 GPG 签名
- **当前阶段不主动 `git push`**（按用户全局规范）

### 7.4 Gradle 配置注意
- `gradle.properties` 中 `org.gradle.jvmargs=-Xmx4g -XX:+UseParallelGC`（默认 512M 不够）
- Gradle 9.x 不兼容 JDK 11，需确保本地 JDK ≥ 17
- Android 16 (API 36) 强制 foreground service 类型声明，v1 不使用 service，绕开

---

## 8. 错误处理

### 8.1 异常体系

```kotlin
sealed class AgentException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {
    class MaxIterations(max: Int) : AgentException("Reached max iterations ($max) without final answer")
    class LlmError(cause: Throwable) : AgentException("LLM call failed: ${cause.message}", cause)
    class InvalidResponse(reason: String) : AgentException("Invalid LLM response: $reason")
    class ToolNotFound(name: String, available: List<String>) :
        AgentException("Tool '$name' not found. Available: $available")
    class Cancelled : AgentException("Agent run was cancelled")
}
```

### 8.2 错误处理策略

| 错误类型 | 行为 | 是否抛异常 |
|---|---|---|
| LLM 网络/HTTP 错误 | 抛 `LlmError` | ✅ |
| LLM 响应格式错误（无法解析） | 抛 `InvalidResponse` | ✅ |
| Tool 业务异常 | 转 `ToolResult(isError=true)` 喂回 LLM | ❌（让 LLM 自我纠正） |
| Tool 未找到 | 转 `ToolResult(isError=true)` 喂回 LLM | ❌ |
| 超过 max iterations | 抛 `MaxIterations` | ✅ |
| Coroutine 取消 | 传播 `CancellationException` | ✅ |
| LLM 返回 `finishReason=Length`（被截断） | 视为终态，返回当前 message | ❌（调用方判断） |

**设计哲学**：**LLM 能自我纠正的错误不抛**（Tool 错误、未找到），**LLM 解决不了的错误抛**（网络挂、格式错、无限循环）。

---

## 9. Sample App 架构

### 9.1 技术栈
- Jetpack Compose + Material 3
- ViewModel + Kotlin Flow
- 单 Activity

### 9.2 结构

```
app/src/main/java/io/github/yeyi/agent/app/
├── MainActivity.kt
├── ui/
│   ├── ChatScreen.kt                  # 消息列表 + 输入框
│   ├── MessageBubble.kt               # 单条消息气泡
│   └── ToolCallIndicator.kt           # 工具调用小标签
├── vm/
│   └── ChatViewModel.kt               # 持 Agent + Memory
└── demo/
    ├── tools/
    │   ├── GetCurrentTimeTool.kt      # 最简 Tool
    │   ├── CalculatorTool.kt          # 多参数 + 错误处理
    │   ├── WebSearchMockTool.kt       # 异步/长耗时 Tool
    │   └── README.md                  # 标注"教学/可复用"
    └── DemoAgentFactory.kt            # 构造配好 Tools 的 Agent
```

### 9.3 ViewModel 核心

```kotlin
class ChatViewModel(
    private val agent: Agent = DemoAgentFactory.create()
) : ViewModel() {

    private val _messages = MutableStateFlow<List<UiMessage>>(emptyList())
    val messages: StateFlow<List<UiMessage>> = _messages.asStateFlow()

    private val memory = InMemoryMemory()                    // ViewModel scope = 会话 scope

    fun sendUserInput(text: String) {
        viewModelScope.launch {
            agent.runStream(text, memory).collect { event ->
                when (event) {
                    is AgentEvent.TextDelta -> appendToCurrentAssistant(event.text)
                    is AgentEvent.ToolCallStarted -> showToolIndicator(event.toolName)
                    is AgentEvent.ToolCallFinished -> hideToolIndicator()
                    is AgentEvent.Final -> commitAssistantMessage()
                    is AgentEvent.Failed -> showError(event.cause)
                }
            }
        }
    }
}
```

### 9.4 3 个演示 Tool 的设计原则

**关键约束**：**纯 Kotlin，不依赖 Android SDK**——便于 v2 Python 移植 1:1 对应。

| Tool | 教学点 | 依赖 |
|---|---|---|
| `GetCurrentTimeTool` | 零参数、无错误路径 | `java.time.Instant` |
| `CalculatorTool` | 多参数 + JSON schema 表达 + 错误处理 | `javax.script` 或自写 parser |
| `WebSearchMockTool` | 异步/IO 耗时 | `kotlinx.coroutines.delay` |

**KDoc 写明"教学/可复用"性质**。

### 9.5 演示场景（README 中列出）
1. "现在几点？" → 触发 `GetCurrentTimeTool`
2. "算 (3+5)*7" → 触发 `CalculatorTool`
3. "搜下 kotlin coroutines 教程" → 触发 mock `WebSearchTool`，展示多步推理

---

## 10. v1 稳定性承诺

### 10.1 核心接口冻结
以下 6 个接口/类型的公开 API 在 **v1 整个生命周期（v1.0.0 → v2.0.0）** 保持二进制兼容：
- `LlmClient`
- `Tool`
- `Memory`
- `Agent`
- `AgentHook`（v1 新增）
- `Skill`（v1 新增数据类）

v1.x 的小版本（v1.1, v1.2...）可以**新增**方法（带默认值）、新增 sealed 分支（带 `@SerialName`）、新增 data class 字段（带默认值），但**不删除/重命名/改签名**已有方法。

### 10.2 内部 API 隔离
- `internal` 关键字保护所有实现细节
- `io.github.yeyi.agent.core.internal` 包严禁 SDK 用户引用
- `io.github.yeyi.agent.core.providers.ProviderSupport` 是 `internal` 可见性

### 10.3 数据类稳定性
所有 `data class` 在 v1 阶段**只加字段、不删字段**。新增字段必须有默认值。

### 10.4 Skill 稳定性
- `Skill` 数据类的字段在 v1 阶段只增不减
- 新增字段必须有默认值
- v1 不引入 `interface Skill`（数据类够用）——**v2.1 引入 interface 时保持 data class 兼容**

---

## 11. v2+ 演化路线

### 11.0 模块依赖规则（所有 v2+ 模块遵循）

- 任何 v2+ 模块**必须依赖 `core`**（实现 `core` 里的接口）
- v2+ 模块**互不依赖**（`agent-sdk-persistence` 不依赖 `agent-sdk-token`）
- 装饰器类模块（`agent-sdk-token`）只引用 `core` 的接口，不引用其他实现
- 这样保证 v1 → v2 → v3 任意子集都能组合使用，不会出现"想要 X 必须先装 Y"的连锁依赖

### 11.1 阶段地图

```
v1.0  ─┬─→ v1.1  ─┬─→ v1.2  ─→ ...（小修小补）
       │          │
       │          └─→ 内部优化、性能、bug fix
       │
       └─→ v2.0  ← 第一个大版本（接口允许扩展）
            │
            ├─→ v2.1（Tool 生态）
            ├─→ v2.2（会话管理）
            ├─→ v2.3（更多 Provider）
            ├─→ v2.4（进阶模式）
            └─→ v3.0（生产化）
```

### 11.2 v2.0 持久化 + Token 管理

**为什么先做**：v1 最大痛点是杀进程丢历史、长会话爆 token。

**新增模块**：
```
agent-sdk-persistence-android/        # Android 专用
  └─ RoomMemory(db: AgentDb): Memory

agent-sdk-persistence-jvm/            # 纯 JVM
  └─ FileMemory(file: File): Memory

agent-sdk-token/                      # Token 管理
  ├─ WindowedMemory(inner: Memory, maxTokens: Int, tokenizer: Tokenizer)
  ├─ SummarizingMemory(inner: Memory, summarizer: LlmClient, keepRecent: Int)
  └─ Tokenizer interface + JvmTokenizer 实现
```

**装饰器范式**（consumer 组合）：
```kotlin
val memory = WindowedMemory(                // 外层：限 token
    inner = RoomMemory(db),                  // 内层：持久化
    maxTokens = 8000,
    tokenizer = JvmTokenizer("gpt-4o")
)
```

**明确不做**：L1 内存 + L2 磁盘二级缓存（SQLite/Room 内部有 page cache，LLM 一次只读一次 history，重复造轮子无收益）。

### 11.3 v2.1 Tool 生态 + Skill 高级特性

**Tool 生态新增模块**：
```
agent-sdk-tool-annotation/        # KSP 处理器，@AgentTool 注解
agent-sdk-tool-discovery/         # tools(packageName=...) 自动扫描
agent-sdk-tool-mcp/               # MCP 客户端适配器
agent-sdk-tool-stdlib/            # 通用工具集（time/http/json/file/shell）
```

**Skill 高级特性新增模块**（v1 已有数据类，v2.1 增强）：
```
agent-sdk-skill-registry/         # SkillRegistry：从文件/网络加载 Skill
  ├─ MarkdownSkillLoader           # 从 .md 文件加载（带 front-matter）
  └─ RemoteSkillLoader             # 从 HTTP 端点拉取

agent-sdk-skill-activator/        # SkillActivator：运行时按需激活
  ├─ LLMSkillSelector              # LLM 决策加载哪些 Skill
  └─ EmbeddingSkillSelector        # Embedding 相似度选择

agent-sdk-skill-commands/         # Slash 命令解析
  └─ /skill name "args"            # 用户输入命令，触发 Skill
```

**v1 → v2.1 Skill 演进**：
- v1：`Skill` 是 `data class`，静态 `agent { skill(Skill(...)) }` 注册
- v2.1：引入 `interface Skill`，data class 实现 interface（保持二进制兼容）
- v2.1：新增 `SkillRegistry` 抽象，支持从 markdown / JSON / HTTP 加载
- v2.1：Skill 元数据（version、author、tags）字段加入 data class

### 11.4 v2.2 会话管理

**新增模块**：
```
agent-sdk-session/
  ├─ data class Session(id, title, createdAt, updatedAt, metadata)
  ├─ interface SessionStore { list, get, create, delete, update }
  └─ class SessionAgent: Agent    // 包装 Agent，按 sessionId 加载/保存

agent-sdk-session-room/           # Room 实现
agent-sdk-session-file/           # JVM 文件实现
```

### 11.5 v2.3 更多 Provider

```
agent-sdk-provider-gemini/
agent-sdk-provider-cohere/
agent-sdk-provider-mistral/
agent-sdk-provider-ollama-native/     # Ollama 自有 API（非 OpenAI 兼容模式）
agent-sdk-provider-azure-openai/      # 不同鉴权
```

当 provider > 5 时，抽取 `agent-sdk-provider-base-openai` 共用基类。

### 11.6 v2.4 进阶模式

```
agent-sdk-algorithm/
  ├─ PlanAndExecuteAgent
  ├─ MultiAgentOrchestrator
  └─ ReflectiveAgent

agent-sdk-parallel-tools/         # 独立 tool 并行执行
agent-sdk-structured-output/      # JSON schema 强制 + 解析失败重试
```

### 11.7 v3.0 生产化

```
agent-sdk-observability/          # 指标：调用数、延迟、token、错误
agent-sdk-tracing/                # OpenTelemetry
agent-sdk-cache/                  # 精确 + 语义缓存
agent-sdk-resilience/             # 重试 / 熔断 / 限流
agent-sdk-evals/                  # Agent 评估框架
```

### 11.8 v3.x 跨平台（仅当用户改方向）
- 提取 `commonMain`，迁移到 KMP
- iOS / Web target
- Python 独立实现（共享协议规范 `agent-protocol.md`）

---

## 12. 完整可复用 SDK 的目标架构

### 12.1 6 层架构图

```
┌──────────────────────────────────────────────────────────┐
│ App Layer（调用方业务代码）                                │
└────────────────────────────┬─────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────┐
│ ① Orchestration（编排层）                                │
│   ReActAgent │ PlanAndExecuteAgent │ MultiAgentOrch     │
└────────────────────────────┬─────────────────────────────┘
                             │
┌────────────────────────────▼─────────────────────────────┐
│ ② Abstractions（核心抽象层）                              │
│   LlmClient │ Tool │ Memory │ Agent │ SessionStore      │
└──┬──────────────┬──────────────┬──────────────┬──────────┘
   │              │              │              │
┌──▼─────┐  ┌────▼─────┐  ┌─────▼────┐  ┌──────▼──────┐
│③ LLM   │  │④ Tool    │  │⑤ Memory  │  │⑥ Session    │
│Provider│  │Adapters  │  │Backends  │  │Store        │
└──┬─────┘  └────┬─────┘  └─────┬────┘  └──────┬──────┘
   │             │              │              │
┌──▼──────┐ ┌───▼──────┐  ┌────▼─────┐  ┌─────▼──────┐
│ OpenAI  │ │ MCP      │  │ Room     │  │ Room       │
│ Anthropic│ │ Stdlib   │  │ File     │  │ File       │
│ Gemini  │ │ Custom   │  │ Windowed │  │ In-Memory  │
│ Local   │ │ Async    │  │ Summary  │  │            │
└─────────┘ └──────────┘  └──────────┘  └────────────┘
```

### 12.2 5 个扩展点

| 想加什么 | 实现什么 | 注册方式 |
|---|---|---|
| **新 LLM 供应商** | `class XxxClient : LlmClient` | `agent { llmClient = XxxClient(...) }` |
| **新业务 Tool** | `class XxxTool : Tool` | `agent { tool(XxxTool()) }` |
| **新存储后端** | `class XxxMemory : Memory` | `agent { memory(::XxxMemory) }` |
| **新 Agent 算法** | `class XxxAgent : Agent` | `val a: Agent = XxxAgent(...)` |
| **新会话后端** | `class XxxSessionStore : SessionStore` | `SessionAgent(a, store)` |

### 12.3 关键设计指标

- v1 核心接口在 v2 之前**不破坏**
- 任何 v2+ 新增能力都是"新增模块 + 新增实现"，**不修改 v1 抽象**
- v2 持久化模块能用 v1.0.0 的 core 编译（接口稳定）
- Python 移植时，v1 的 4 个核心接口能 1:1 对应 Python 的 Protocol/ABC

---

## 13. 关键设计原则

1. **核心接口稳定优先**：②层是承重墙，改一次牵动所有上下层
2. **显式优于隐式**：Builder DSL、显式 Tool 注册、显式 Memory 持有
3. **装饰器优于继承**：`WindowedMemory(RoomMemory(...))` 比 `class SmartMemory : Memory` 更灵活
4. **Python 移植友好**：`JsonElement` 而非强类型；sealed 而非复杂泛型
5. **Android 友好**：全 suspend/Flow，原生支持 ViewModel + Lifecycle
6. **错误分两类**：LLM 能纠正的不抛（Tool 错误），LLM 解决不了的抛（网络挂、格式错）
7. **YAGNI 严格**：v1 不做的事写在第 1.3 节，逐条守
8. **Skill 是 Agent 的可复用资产单位**：Tool 是原子，Skill 是场景化打包；v1 数据类起步，v2+ 加载/激活/命令
9. **Hook 是 Agent 的可观测面**：零侵入 default no-op 实现，v1 即可获得日志/统计入口
10. **线程安全是契约**：Agent immutable，多并发 run 安全；Memory 实现者自管并发

---

## 14. 术语表

| 术语 | 含义 |
|---|---|
| Provider | `LlmClient` 的具体实现（OpenAiClient、AnthropicClient 等），SDK 提供 |
| Tool | 业务工具，实现 `Tool` 接口，**SDK 不提供**，由 consumer 写 |
| Memory | 消息存储抽象，v1 唯一实现 `InMemoryMemory` |
| Agent | 编排算法，v1 唯一算法 `ReActAgent` |
| Skill | `Tool` + 系统提示片段的可复用打包，v1 为数据类，v2.1+ 加动态加载/激活/命令 |
| Hook / AgentHook | Agent 生命周期回调，v1 内置 NoOp 默认实现 |
| Session | 一次对话的业务概念，**SDK 不实现**，由 consumer 持有 Memory 实例 |
| LlmClient | LLM 客户端接口，Provider 都实现它 |
| ReAct | Reason + Act 循环：LLM 推理 → 调工具 → 观察结果 → 再推理 |

---

## 15. 开放问题与待办

> 这些**不阻塞 v1 实现**，但记录在此供后续讨论。

1. **JSON Schema 来源**：v1 让 Tool 手写 schema 字符串，v2 是否用 KSP 自动生成？
2. **Token 计数接口**：v2 的 `Tokenizer` 用哪家？tiktoken-java？GPT-3.5/4 tokenizer？Anthropic 的？
3. **Session 元数据 schema**：v2 引入 `Session` 时，metadata 用 `Map<String, String>` 还是 sealed class？
4. **MCP 版本对齐**：MCP 协议还在演进，v2 引入时锚定哪个版本？
5. **Python 协议规范**：v1 阶段就定 `agent-protocol.md` 还是有 v1 实践经验后再定？
6. **多模态内容**：v1 `ChatMessage.User.content` 暂为 String，v2 改为 sealed Content（Text | Image | File）？
7. **Reasoning 模型字段**：v1 ChatRequest 加 nullable `reasoning: ReasoningConfig?` 字段是否值得？
8. **FinishReason 扩展**：v1 扩展为 6-8 个值（增加 Refusal / ContentFilter / StopSequence）？

---

**文档结束 · 等待用户审阅**
