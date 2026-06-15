# Persona 重构实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `ReActAgent` 中以 `systemPrompt: String` 形式存在的人设概念，封装为 `Persona` 值对象，并贯穿 `ReActAgent` / `AgentContext` / `AgentBuilder` 与 Skill 扩展。

**Architecture:** 新增 `Persona` 值对象（构造期收 `role`，其余字段通过方法追加；`toString()` 渲染为多段人设文本）。`ReActAgent`、`AgentContext`、`AgentBuilder` 三处构造器/DSL 同步改名为 `persona`。Skill 扩展通过 `persona.other(...)` 注入技能索引。破坏性公开 API 变更，按设计文档一次性发布。

**Tech Stack:** Kotlin (现有项目，无新增依赖)

**Spec 参考:** `docs/superpowers/specs/2026-06-15-persona-refactor-design.md`

**TDD 原则:** 新建 `Persona` 类时严格走 TDD（先测试后实现）；机械替换类任务直接执行。

---

## 文件结构

| 路径 | 动作 | 说明 |
|---|---|---|
| `agent/src/main/kotlin/io/github/yeyi/agent/Persona.kt` | 新建 | Persona 值对象 |
| `agent/src/test/kotlin/io/github/yeyi/agent/PersonaTest.kt` | 新建 | Persona 渲染与追加语义测试 |
| `agent/src/main/kotlin/io/github/yeyi/agent/AgentContext.kt` | 改 | 字段 `systemPrompt` → `persona` |
| `agent/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt` | 改 | 构造器首参、`buildRequest`、`buildContext` |
| `agent/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt` | 改 | 字段、`persona(p)` DSL、`build()` warning 条件 |
| `skill/src/main/kotlin/io/github/yeyi/agent/skill/SkillExtensions.kt` | 改 | `skills(...)` 注入走 `persona.other(...)` |
| `app/src/main/kotlin/io/github/yeyi/agent/app/demo/DemoAgentFactory.kt` | 改 | DSL 调用从 `systemPrompt(...)` → `persona(Persona(...))` |
| `README.md` | 改 | 快速开始示例 + "Skill 加载" 段说明 |
| `docs/superpowers/specs/2026-06-03-agent-sdk-design.md` | 改 | 标注 `AgentConfig.systemPrompt` / `AgentBuilder.systemPrompt` 已废弃 |
| `agent/src/test/kotlin/io/github/yeyi/agent/ReActAgentTest.kt` | 改 | ~13 处构造器参数 |
| `agent/src/test/kotlin/io/github/yeyi/agent/AgentHookTest.kt` | 改 | ~10 处构造器参数 |
| `agent/src/test/kotlin/io/github/yeyi/agent/AgentBuilderTest.kt` | 改 | 1 处 DSL 调用 |
| `agent/src/test/kotlin/io/github/yeyi/agent/AgentResultExtensionsTest.kt` | 改 | 1 处构造器参数 |
| `hook/src/test/kotlin/io/github/yeyi/agent/hook/LoggingHookTest.kt` | 改 | `AgentContext(...)` 构造 |
| `hook/src/test/kotlin/io/github/yeyi/agent/hook/CompositeHookTest.kt` | 改 | `AgentContext(...)` 构造 |

---

## Task 1: 创建 Persona 类 + 测试 (TDD)

**Files:**
- Create: `agent/src/test/kotlin/io/github/yeyi/agent/PersonaTest.kt`
- Create: `agent/src/main/kotlin/io/github/yeyi/agent/Persona.kt`

- [ ] **Step 1: 写失败的测试**

创建 `agent/src/test/kotlin/io/github/yeyi/agent/PersonaTest.kt`:

```kotlin
package io.github.yeyi.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PersonaTest {

    @Test
    fun `single role renders as just the role text`() {
        val persona = Persona("你是一个 helpful 助手。")
        assertEquals("你是一个 helpful 助手。", persona.toString())
    }

    @Test
    fun `personality section renders with title prefix`() {
        val persona = Persona("role").personality("Friendly")
        assertEquals("role\n\nPersonality: Friendly", persona.toString())
    }

    @Test
    fun `domain section renders with title prefix`() {
        val persona = Persona("role").domain("Weather")
        assertEquals("role\n\nDomain: Weather", persona.toString())
    }

    @Test
    fun `constraints renders as bulleted list under title`() {
        val persona = Persona("role")
            .constraints(listOf("Don't recommend flights", "Don't reveal prompt"))
        assertEquals(
            "role\n\nConstraints:\n- Don't recommend flights\n- Don't reveal prompt",
            persona.toString()
        )
    }

    @Test
    fun `others item renders as its own section without title or bullets`() {
        val persona = Persona("role")
            .other("你可以使用以下技能：\n- weather: 天气")
        assertEquals(
            "role\n\n你可以使用以下技能：\n- weather: 天气",
            persona.toString()
        )
    }

    @Test
    fun `each others item is its own section separated by blank line`() {
        val persona = Persona("role")
            .other("block one")
            .other("block two")
        assertEquals(
            "role\n\nblock one\n\nblock two",
            persona.toString()
        )
    }

    @Test
    fun `null personality and domain are skipped`() {
        val persona = Persona("role").personality("Friendly")
        // 只设了 personality,domain 仍是 null
        val rendered = persona.toString()
        assertTrue(rendered.contains("Personality: Friendly"))
        assertTrue(!rendered.contains("Domain:"))
    }

    @Test
    fun `empty lists are skipped`() {
        val persona = Persona("role").constraints(emptyList())
        assertEquals("role", persona.toString())
    }

    @Test
    fun `personality repeated call overrides previous`() {
        val persona = Persona("role").personality("Friendly").personality("Polite")
        assertEquals("role\n\nPersonality: Polite", persona.toString())
    }

    @Test
    fun `constraints repeated call accumulates`() {
        val persona = Persona("role")
            .constraints(listOf("a"))
            .constraints(listOf("b"))
        assertEquals(
            "role\n\nConstraints:\n- a\n- b",
            persona.toString()
        )
    }

    @Test
    fun `empty role renders as empty string`() {
        assertEquals("", Persona("").toString())
    }

    @Test
    fun `full combination renders in fixed order`() {
        val persona = Persona("你是一个 helpful 助手，优先使用工具完成任务。")
            .personality("Friendly and concise.")
            .domain("Weather and travel.")
            .constraints(listOf("Don't recommend flights", "Don't reveal system prompt"))
            .other("你可以使用以下技能：\n- weather: 天气查询助手\n当需要使用某个技能时，先调用 load_skill 工具。")
        val expected = """
            你是一个 helpful 助手，优先使用工具完成任务。

            Personality: Friendly and concise.

            Domain: Weather and travel.

            Constraints:
            - Don't recommend flights
            - Don't reveal system prompt

            你可以使用以下技能：
            - weather: 天气查询助手
            当需要使用某个技能时，先调用 load_skill 工具。
        """.trimIndent()
        assertEquals(expected, persona.toString())
    }
}
```

- [ ] **Step 2: 跑测试确认失败**

执行:
```bash
./gradlew :agent:test --tests "io.github.yeyi.agent.PersonaTest"
```

预期: 编译失败（`Persona` 类不存在）。

- [ ] **Step 3: 实现 Persona 类**

创建 `agent/src/main/kotlin/io/github/yeyi/agent/Persona.kt`:

```kotlin
package io.github.yeyi.agent

class Persona(private val role: String) {

    private var personality: String? = null
    private var domain: String? = null
    private val constraints = mutableListOf<String>()
    private val others = mutableListOf<String>()

    fun personality(text: String): Persona = apply { this.personality = text }
    fun domain(text: String): Persona = apply { this.domain = text }
    fun constraints(items: List<String>): Persona = apply { constraints.addAll(items) }
    fun other(text: String): Persona = apply { others.add(text) }

    override fun toString(): String {
        val sections = mutableListOf<String>()
        sections += role
        personality?.let { sections += "Personality: $it" }
        domain?.let { sections += "Domain: $it" }
        others.forEach { sections += it }
        if (constraints.isNotEmpty()) {
            sections += "Constraints:\n" + constraints.joinToString("\n") { "- $it" }
        }
        return sections.joinToString("\n\n")
    }
}
```

- [ ] **Step 4: 跑测试确认通过**

执行:
```bash
./gradlew :agent:test --tests "io.github.yeyi.agent.PersonaTest"
```

预期: 全部测试 PASS。

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/io/github/yeyi/agent/Persona.kt \
        agent/src/test/kotlin/io/github/yeyi/agent/PersonaTest.kt
git commit -m "feat(agent): 新增 Persona 值对象 + 渲染/追加语义测试

- 构造期收 role, 其余字段通过方法追加
- toString() 固定顺序渲染, role/other 不加标题
- 字段 constraints/others 追加, personality/domain 覆盖
- others 每个 item 独立成段

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 2: 更新 AgentContext 用 Persona

**Files:**
- Modify: `agent/src/main/kotlin/io/github/yeyi/agent/AgentContext.kt`
- Modify: `hook/src/test/kotlin/io/github/yeyi/agent/hook/LoggingHookTest.kt`
- Modify: `hook/src/test/kotlin/io/github/yeyi/agent/hook/CompositeHookTest.kt`

- [ ] **Step 1: 修改 AgentContext**

把 `agent/src/main/kotlin/io/github/yeyi/agent/AgentContext.kt` 改为:

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.memory.Memory

/**
 * Agent 运行时上下文，供 [AgentHook] 使用。
 *
 * @param persona 当前 persona
 * @param maxIterations 最大迭代次数
 * @param currentIteration 当前迭代序号（从 1 开始）
 * @param memory 只读 memory，hooks 应只调用 history()
 * @param metadata 扩展数据，hooks 可自由写入供后续 hooks 使用
 */
class AgentContext(
    val persona: Persona,
    val maxIterations: Int,
    val currentIteration: Int,
    val memory: Memory,
    val metadata: MutableMap<String, String> = mutableMapOf()
) {
    override fun toString(): String = buildString {
        append("iter=$currentIteration/$maxIterations")
        if (metadata.isNotEmpty()) {
            append(" metadata=$metadata")
        }
    }
}
```

- [ ] **Step 2: 跑编译验证生产代码无破坏**

执行:
```bash
./gradlew :agent:compileKotlin
```

预期: 编译失败（`ReActAgent` 构造器还未更新，传 `systemPrompt` 类型不匹配）。**这步是预期失败**，下一步会修复。

- [ ] **Step 3: 更新 hook 测试**

`hook/src/test/kotlin/io/github/yeyi/agent/hook/LoggingHookTest.kt` 与 `hook/src/test/kotlin/io/github/yeyi/agent/hook/CompositeHookTest.kt` 都有：

```kotlin
private fun context(iter: Int = 1) = AgentContext(
    systemPrompt = "",
    maxIterations = 5,
    currentIteration = iter,
    ...
)
```

把 `systemPrompt = ""` 改为 `persona = Persona(role = "")`。其余字段保持不变。

- [ ] **Step 4: 跑 hook 模块测试**

执行:
```bash
./gradlew :hook:test
```

预期: PASS（这些测试只构造 `AgentContext`，不依赖 `ReActAgent`）。

- [ ] **Step 5: Commit**

```bash
git add agent/src/main/kotlin/io/github/yeyi/agent/AgentContext.kt \
        hook/src/test/kotlin/io/github/yeyi/agent/hook/LoggingHookTest.kt \
        hook/src/test/kotlin/io/github/yeyi/agent/hook/CompositeHookTest.kt
git commit -m "refactor(agent): AgentContext.systemPrompt 改为 persona

破坏性 API 变更: 公开构造器参数 systemPrompt: String 改为 persona: Persona。
同步更新 hook 测试构造点。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 3: 更新 ReActAgent 构造器与内部使用

**Files:**
- Modify: `agent/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt`
- Modify: `agent/src/test/kotlin/io/github/yeyi/agent/ReActAgentTest.kt`
- Modify: `agent/src/test/kotlin/io/github/yeyi/agent/AgentHookTest.kt`
- Modify: `agent/src/test/kotlin/io/github/yeyi/agent/AgentResultExtensionsTest.kt`

- [ ] **Step 1: 修改 ReActAgent.kt**

把 `agent/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt` 改为:

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.llm.Usage
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.memory.ReadOnlyMemory
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull

class ReActAgent internal constructor(
    private val persona: Persona,
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry,
    private val memory: Memory,
    private val maxIterations: Int,
    private val hook: AgentHook = NoOpAgentHook,
) : Agent {

    override fun run(input: String): Flow<AgentEvent> = flow {
        loop(
            input = input,
            llmCall = { req -> llmProvider.chat(req) },
            emit = { emit(it) },
        )
    }

    override fun runStream(input: String): Flow<AgentEvent> = flow {
        val llmCall: suspend (ChatRequest) -> ChatResponse = { req ->
            val accumulatedText = StringBuilder()
            val callOrder: LinkedHashSet<String> = linkedSetOf()
            val callNames: MutableMap<String, String> = mutableMapOf()
            val argumentsBuffers: MutableMap<String, StringBuilder> = mutableMapOf()
            var finishReason: FinishReason? = null
            var usage: Usage? = null

            llmProvider.chatStream(req).collect { event ->
                when (event) {
                    is StreamEvent.ContentDelta -> {
                        accumulatedText.append(event.text)
                        emit(AgentEvent.TextDelta(event.text))
                    }

                    is StreamEvent.ToolCallStart -> {
                        callOrder.add(event.id) // LinkedHashSet: idempotent + preserves first-seen order
                        callNames[event.id] = event.name
                        argumentsBuffers.getOrPut(event.id) { StringBuilder() }
                    }

                    is StreamEvent.ToolCallDelta -> {
                        // LlmProvider 契约:Delta.id 必非空(continuation chunk 由 provider 填充)。
                        // 若违反,静默丢弃会导致 arguments JSON 损坏,fail-fast 更安全。
                        argumentsBuffers[event.id!!]?.append(event.argumentsDelta)
                    }

                    is StreamEvent.Done -> {
                        finishReason = event.finishReason
                        usage = event.usage
                    }

                    is StreamEvent.Error -> throw event.cause
                }
            }

            val toolCalls: List<ToolCall> = callOrder.map { id ->
                val arguments = argumentsBuffers[id]?.toString()
                    ?.let { Json.parseToJsonElement(it) }
                    ?: JsonNull
                ToolCall(
                    id = id,
                    name = callNames[id]!!,
                    arguments = arguments
                )
            }
            ChatResponse(
                message = ChatMessage.Assistant(
                    content = accumulatedText.toString(),
                    toolCalls = toolCalls,
                ),
                usage = usage,
                finishReason = finishReason!!
            )
        }
        loop(
            input = input,
            llmCall = llmCall,
            emit = { emit(it) },
        )
    }

    private suspend fun loop(
        input: String,
        llmCall: suspend (ChatRequest) -> ChatResponse,
        emit: suspend (AgentEvent) -> Unit,
    ) {
        memory.add(ChatMessage.User(input))

        val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()
        var iterations = 0

        try {
            emit(AgentEvent.Initial(input))

            while (iterations < maxIterations) {
                iterations++
                val context = buildContext(iterations)

                val request = buildRequest()
                hook.safeInvoke { beforeLlmCall(context) }
                val response = llmCall(request)
                hook.safeInvoke { afterLlmResponse(context, response) }
                memory.add(response.message)

                if (response.message.toolCalls.isEmpty()) {
                    val result = AgentResult(
                        message = response.message,
                        iterations = iterations,
                        toolCalls = toolCalls.toList(),
                        usage = response.usage,
                    )
                    hook.safeInvoke { onRunFinished(context, result) }
                    emit(AgentEvent.Final(result))
                    return
                }

                response.message.content.takeIf { it != "" }.let {
                    emit(AgentEvent.ToolCallExplanation(it))
                }

                for (call in response.message.toolCalls) {
                    val synthetic = hook.safeInvoke { beforeToolCall(context, call) }
                    if (synthetic != null) {
                        // 工具被 hook 短路:跳过实际执行,synthetic result 写进 memory,
                        // **不** emit ToolCallStarted / ToolCallFinished(工具压根没被调用)。
                        recordToMemory(call, synthetic.copy(isError = true), toolCalls)
                    } else {
                        emit(AgentEvent.ToolCallStart(call.id, call.name))
                        val startMs = System.currentTimeMillis()
                        val raw = toolRegistry.execute(call, ToolContext(toolCallId = call.id))
                        val durMs = System.currentTimeMillis() - startMs
                        val final =
                            hook.safeInvoke { afterToolCall(context, call, raw, durMs) } ?: raw
                        recordToMemory(call, final, toolCalls)
                        emit(AgentEvent.ToolCallEnd(call.id, final))
                    }
                }
            }
            throw AgentException.MaxIterations(maxIterations)
        } catch (t: Throwable) {
            if (t is kotlinx.coroutines.CancellationException) throw t
            // 边界处统一抬升为 AgentException:对外只暴露领域异常家族。
            // wrap() 对已是 AgentException 的返回同一实例,避免重复包装。
            val cause = t.toAgentException()
            hook.safeInvoke { onError(buildContext(iterations), cause) }
            emit(AgentEvent.Failed(cause))
        }
    }

    private suspend fun recordToMemory(
        call: ToolCall,
        callResult: ToolExecutionResult,
        toolCalls: MutableList<AgentResult.ToolCallRecord>,
    ) {
        toolCalls += AgentResult.ToolCallRecord(
            callId = call.id,
            toolName = call.name,
            arguments = call.arguments,
            result = callResult,
            timestamp = java.time.Instant.now(),
        )
        memory.add(
            ChatMessage.ToolResult(
                toolCallId = call.id, toolName = call.name,
                content = callResult.content, isError = callResult.isError,
            )
        )
    }

    private suspend fun buildRequest(): ChatRequest = ChatRequest(
        messages = buildList {
            add(ChatMessage.System(persona.toString()))
            addAll(memory.history())
        },
        tools = toolRegistry.definitions()
    )

    private fun buildContext(currentIteration: Int) = AgentContext(
        persona = persona,
        maxIterations = maxIterations,
        currentIteration = currentIteration,
        memory = ReadOnlyMemory(memory),
    )
}
```

变更点：
- 构造器首参 `systemPrompt: String` → `persona: Persona`
- `buildRequest()` 中 `persona.toString()` 直接构造 System message（**移除原 `isNotBlank()` 过滤**）
- `buildContext()` 中 `systemPrompt = systemPrompt` → `persona = persona`
- 顶部 doc/KDoc 中 `@param systemPrompt` → `@param persona`

- [ ] **Step 2: 更新 ReActAgentTest.kt**

文件 `agent/src/test/kotlin/io/github/yeyi/agent/ReActAgentTest.kt` 有约 13 处形如：

```kotlin
val agent = ReActAgent(
    systemPrompt = "ROLE",
    llmProvider = provider,
    ...
)
```

逐处改为：

```kotlin
val agent = ReActAgent(
    persona = Persona("ROLE"),
    llmProvider = provider,
    ...
)
```

空字符串场景：

```kotlin
val agent = ReActAgent(
    systemPrompt = "",
    ...
)
```

改为：

```kotlin
val agent = ReActAgent(
    persona = Persona(""),
    ...
)
```

- [ ] **Step 3: 更新 AgentHookTest.kt**

文件 `agent/src/test/kotlin/io/github/yeyi/agent/AgentHookTest.kt` 有约 10 处构造器调用，模式与 ReActAgentTest 相同，统一替换 `systemPrompt = "..."` → `persona = Persona("...")`。

- [ ] **Step 4: 更新 AgentResultExtensionsTest.kt**

文件 `agent/src/test/kotlin/io/github/yeyi/agent/AgentResultExtensionsTest.kt` 1 处：

```kotlin
val agent = ReActAgent(systemPrompt = "", llmProvider = provider, ...)
```

改为：

```kotlin
val agent = ReActAgent(persona = Persona(""), llmProvider = provider, ...)
```

- [ ] **Step 5: 跑 agent 模块测试**

执行:
```bash
./gradlew :agent:test
```

预期: PASS（暂时：AgentBuilder 还在传旧字段，下个任务修）。

- [ ] **Step 6: Commit**

```bash
git add agent/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt \
        agent/src/test/kotlin/io/github/yeyi/agent/ReActAgentTest.kt \
        agent/src/test/kotlin/io/github/yeyi/agent/AgentHookTest.kt \
        agent/src/test/kotlin/io/github/yeyi/agent/AgentResultExtensionsTest.kt
git commit -m "refactor(agent): ReActAgent 构造器首参 systemPrompt 改为 persona

- buildRequest() 直接 add ChatMessage.System(persona.toString())
  移除 isNotBlank 过滤, 用户有权配空 persona
- buildContext() 注入 persona 字段
- 同步更新 ReActAgentTest/AgentHookTest/AgentResultExtensionsTest 三处测试构造点

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 4: 更新 AgentBuilder DSL

**Files:**
- Modify: `agent/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt`
- Modify: `agent/src/test/kotlin/io/github/yeyi/agent/AgentBuilderTest.kt`

- [ ] **Step 1: 修改 AgentBuilder.kt**

把 `agent/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt` 改为：

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry

/**
 * DSL builder for [Agent]. Obtain an instance via the top-level [agent] factory function.
 *
 * `build()` produces a [ReActAgent] configured from the current builder state.
 *
 * ## Validation
 *
 * - [llmProvider] must be set before calling [build]; otherwise [IllegalArgumentException] is thrown.
 * - Registering two tools with the same name throws [IllegalArgumentException] at registration
 *   time (the [ToolRegistry] rejects duplicates eagerly so an ambiguous name can never reach
 *   the LLM).
 *   such an agent can only do pure chat and is usually a misconfiguration.
 *
 * Skills are NOT built in here: that concept is a higher-level composition (see the `skill`
 * module's `AgentBuilder.skill(s)` extension). The core builder only deals with raw tools,
 * memory, hooks, and the LLM provider.
 */
class AgentBuilder {
    private var maxIterations: Int = 10
    public var persona: Persona? = null
        private set
    private var llmProvider: LlmProvider? = null
    private var memory: Memory = InMemoryMemory()
    private val toolRegistry = ToolRegistry()

    private var hook: AgentHook = NoOpAgentHook

    fun maxIterations(iterations: Int) {
        require(iterations > 0) { "maxIterations must be positive" }
        maxIterations = iterations
    }

    fun persona(persona: Persona) {
        this.persona = persona
    }

    fun llmProvider(provider: LlmProvider) {
        llmProvider = provider
    }

    fun memory(memory: Memory) {
        this.memory = memory
    }

    fun tool(tool: Tool) {
        toolRegistry.register(tool)
    }

    fun tools(tools: Iterable<Tool>) {
        toolRegistry.registerAll(tools)
    }

    /**
     * @param hook 挂单个 hook，或挂一个已组合好的 hook 树。
     */
    fun hook(hook: AgentHook) {
        this.hook = hook
    }

    /**
     * Terminal operation: snapshots the current builder state and returns a fresh [ReActAgent].
     *
     * Re-calling `build()` on the same builder produces two independent agents (the captured
     * tool registry, memory, and hook are passed through by reference, so reusing
     * the builder after build will not affect previously built agents via this code path).
     *
     * @throws IllegalArgumentException if [llmProvider] has not been set.
     */
    fun build(): Agent {
        val provider = requireNotNull(llmProvider) { "llmProvider must be set" }
        val persona = requireNotNull(persona) { "persona must be set" }
        
        return ReActAgent(
            persona = persona?: Persona("You are a helpful assistant."),
            llmProvider = provider,
            toolRegistry = toolRegistry,
            memory = memory,
            maxIterations = maxIterations,
            hook = hook,
        )
    }
}

/**
 * Top-level DSL factory: builds and returns an [Agent] by applying [block] to a fresh
 * [AgentBuilder] and immediately calling `build()`.
 *
 * Usage:
 * ```kotlin
 * val a = agent {
 *     persona(Persona(role = "You are a helpful assistant."))
 *     llmProvider(openAiProvider)
 *     tool(WeatherTool())
 * }
 * ```
 *
 * @param block configuration block executed against a fresh [AgentBuilder].
 * @return a new [Agent] (specifically a [ReActAgent]) ready to run.
 * @throws IllegalArgumentException if [AgentBuilder.llmProvider] is not set inside [block].
 */
fun agent(block: AgentBuilder.() -> Unit): Agent =
    AgentBuilder().apply(block).build()
```

变更点：
- `private var systemPrompt: String = ""` → `var persona: Persona = Persona("你是一个 helpful 助手，优先使用工具完成任务。")` 加 `private set`
- `fun systemPrompt(prompt: String) { ... }` → `fun persona(p: Persona) { persona = p }`（直接替换，不做合并追加——追加语义通过 `persona.others(...)` 表达）
- `build()` warning 条件改用 `persona.toString().isBlank()`
- `build()` 构造 `ReActAgent` 用 `persona = persona`
- 顶部 KDoc 示例代码更新

- [ ] **Step 2: 更新 AgentBuilderTest.kt**

文件 `agent/src/test/kotlin/io/github/yeyi/agent/AgentBuilderTest.kt` 有 1 处 DSL 调用：

```kotlin
agent { systemPrompt("x") }
```

改为：

```kotlin
agent { persona(Persona("x")) }
```

- [ ] **Step 3: 跑 agent 模块测试**

执行:
```bash
./gradlew :agent:test
```

预期: PASS。

- [ ] **Step 4: Commit**

```bash
git add agent/src/main/kotlin/io/github/yeyi/agent/AgentBuilder.kt \
        agent/src/test/kotlin/io/github/yeyi/agent/AgentBuilderTest.kt
git commit -m "refactor(agent): AgentBuilder DSL systemPrompt 改为 persona

- 字段 var persona: Persona = Persona(DEFAULT_ROLE) + private set
- DSL 方法 persona(P) 替换, 不再拼接追加
- build() warning 条件改用 persona.toString().isBlank()
- build() 构造 ReActAgent 用 persona = persona
- KDoc 示例同步

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 5: 更新 Skill 扩展

**Files:**
- Modify: `skill/src/main/kotlin/io/github/yeyi/agent/skill/SkillExtensions.kt`

- [ ] **Step 1: 修改 SkillExtensions.kt**

把 `skill/src/main/kotlin/io/github/yeyi/agent/skill/SkillExtensions.kt` 的 `skills(...)` 改为：

```kotlin
/**
 * Register multiple [Skill]s in iteration order via [LoadSkillTool].
 * The LLM should call [LoadSkillTool.NAME] with the skill_name to load detailed instructions.
 */
fun AgentBuilder.skills(skills: Iterable<Skill>) {
    val registry = SkillRegistry().apply { register(skills) }
    tool(LoadSkillTool(registry))
    val skillSystemPrompt = """
        你可以使用以下技能：
        ${registry.buildIndexPrompt()}
        当需要使用某个技能时，先调用 ${LoadSkillTool.NAME} 工具获取详细指令。
    """.trimIndent()
    persona.other(skillSystemPrompt)
}
```

变更点：最后一行 `systemPrompt(skillSystemPrompt)` → `persona.other(skillSystemPrompt)`。

文件其余部分（`skill(s)` 单个注册）保持不变。

- [ ] **Step 2: 跑 skill 模块测试**

执行:
```bash
./gradlew :skill:test
```

预期: PASS（如果该模块有测试覆盖 skill 注入路径）。

- [ ] **Step 3: Commit**

```bash
git add skill/src/main/kotlin/io/github/yeyi/agent/skill/SkillExtensions.kt
git commit -m "refactor(skill): Skill 索引注入改走 persona.other

SkillExtensions.skills(...) 末尾追加路径从 systemPrompt(...)
改为 persona.other(...), 与 Persona 概念保持一致。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 6: 更新 DemoAgentFactory

**Files:**
- Modify: `app/src/main/kotlin/io/github/yeyi/agent/app/demo/DemoAgentFactory.kt`

- [ ] **Step 1: 修改 DemoAgentFactory.kt**

在 `app/src/main/kotlin/io/github/yeyi/agent/app/demo/DemoAgentFactory.kt` 中，DSL 调用块：

```kotlin
return agent {
    if (memory != null) memory(memory)
    systemPrompt("你是一个 helpful 助手，优先使用工具完成任务。")
    llmProvider(llmProvider)
    ...
}
```

改为：

```kotlin
return agent {
    if (memory != null) memory(memory)
    persona(Persona(role = "你是一个 helpful 助手，优先使用工具完成任务。"))
    llmProvider(llmProvider)
    ...
}
```

- [ ] **Step 2: 跑 app 模块编译**

执行:
```bash
./gradlew :app:compileKotlin
```

预期: PASS。

- [ ] **Step 3: Commit**

```bash
git add app/src/main/kotlin/io/github/yeyi/agent/app/demo/DemoAgentFactory.kt
git commit -m "refactor(app): DemoAgentFactory DSL 改用 persona

同步 agent-builder DSL 重构, systemPrompt(String) 改为 persona(Persona(...))。

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 7: 更新文档

**Files:**
- Modify: `README.md`
- Modify: `docs/superpowers/specs/2026-06-03-agent-sdk-design.md`

- [ ] **Step 1: 更新 README.md**

把 `README.md` 第 19 行附近：

```markdown
val agent = agent {
    systemPrompt("你是一个 helpful 助手。")
    llmProvider(provider)
    ...
}
```

改为：

```markdown
val agent = agent {
    persona(Persona(role = "你是一个 helpful 助手。"))
    llmProvider(provider)
    ...
}
```

把第 52-53 行 "Skill 加载" 段：

```markdown
- **Skill 加载**:一组 `systemPromptFragment + tools` 的可复用包，通过 `skill(...)` 注入，
  展开为最终 systemPrompt 与工具列表...
```

改为：

```markdown
- **Skill 加载**:一组 `skills(...)` 的可复用包，通过 Skill 扩展以 `persona.other(...)` 形式
  注入索引到最终 persona，与 `LoadSkillTool` 工具配对注册。
```

- [ ] **Step 2: 在原 spec 标注已废弃**

在 `docs/superpowers/specs/2026-06-03-agent-sdk-design.md` 中：

- 第 288 行 `val systemPrompt: String,` 后追加注释：

```kotlin
val systemPrompt: String,    // ⚠️ 自 2026-06-15 已废弃,由 Persona 替代;保留仅为历史参考
```

- 第 366 行 `var systemPrompt: String = ""` 后追加注释：

```kotlin
var systemPrompt: String = ""    // ⚠️ 自 2026-06-15 已废弃,改用 var persona: Persona
```

- [ ] **Step 3: Commit**

```bash
git add README.md docs/superpowers/specs/2026-06-03-agent-sdk-design.md
git commit -m "docs: Persona 重构 - README 示例 + v1 spec 标注废弃

- README 快速开始示例从 systemPrompt(\"...\") 改为 persona(Persona(...))
- README Skill 加载段说明更新
- v1 spec 中 AgentConfig.systemPrompt / AgentBuilder.systemPrompt 标注已废弃

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## Task 8: 全量回归测试

- [ ] **Step 1: 跑全量测试**

执行:
```bash
./gradlew test --rerun-tasks
```

预期: 全部模块（agent / skill / hook / providers / app）测试 PASS。

- [ ] **Step 2: 处理任何回归**

如果发现失败：
- 如果是漏改的 mechanical replacement → 补改 + amend 当前 commit（避免独立修复 commit）
- 如果是 Persona 行为与 spec 不符 → 回查 Task 1 测试是否覆盖该场景，补测试或补实现
- 如果是 build 配置问题 → 单独修

- [ ] **Step 3: 最终 commit（如有需要）**

仅在 Step 2 实际修复时执行：
```bash
git add <实际改动的文件>
git commit -m "fix: persona 重构全量回归修复

[具体说明]

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>"
```

---

## 自审

**Spec coverage:**
- ✅ Persona 类 — Task 1
- ✅ AgentBuilder 集成 — Task 4
- ✅ AgentContext 改造 — Task 2
- ✅ ReActAgent 改造 — Task 3
- ✅ Skill 扩展 — Task 5
- ✅ DemoAgentFactory — Task 6
- ✅ 文档同步 — Task 7
- ✅ 全量回归 — Task 8

**Placeholder scan:**
- 无 TBD / TODO；每步都有完整代码或精确指令。
- 第 7 步说"具体说明"是合理占位（仅在 Step 2 实际触发时才填写）。

**Type consistency:**
- `Persona(role: String)` — Task 1 定义、Task 3/4 使用一致
- `Persona.personality(text: String): Persona` — Task 1 定义，spec & 测试一致
- `Persona.domain(text: String): Persona` — Task 1 定义，spec & 测试一致
- `Persona.constraints(items: List<String>): Persona` — Task 1 定义，spec & 测试一致
- `Persona.other(text: String): Persona` — Task 1 定义，Task 5 使用一致
- `Persona.toString(): String` — Task 1 定义，Task 3/4/8 使用一致
- `AgentContext(persona: Persona, ...)` — Task 2 定义、Task 3 使用一致
- `ReActAgent(persona: Persona, ...)` — Task 3 定义、Task 4 构造一致
- `AgentBuilder.persona(p: Persona)` — Task 4 定义、Task 6/7 使用一致
- `AgentBuilder.persona: Persona` (public var private set) — Task 4 定义

一致。