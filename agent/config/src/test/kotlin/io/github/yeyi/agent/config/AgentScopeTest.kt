package io.github.yeyi.agent.config

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.Agent.Shutdownable
import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.ReActAgent
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.memory.Summary
import io.github.yeyi.agent.modality.ModalityAdapter
import io.github.yeyi.agent.skill.Skill
import io.github.yeyi.agent.subagent.Subagent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.lazy_loading.LazyTool
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import io.github.yeyi.agent.toolset.Toolset
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AgentScopeTest {

    private class NoopHook : AgentHook {
        override suspend fun beforeMemoryCompress(context: AgentContext, summaries: List<Summary>) {}
        override suspend fun afterMemoryCompress(context: AgentContext, summaries: List<Summary>) {}
        override suspend fun beforeLlmCall(context: AgentContext, request: ChatRequest): ChatRequest = request
        override suspend fun afterLlmResponse(context: AgentContext, response: ChatResponse) {}
        override suspend fun beforeToolCall(context: AgentContext, call: ToolCall): ToolExecutionResult? = null
        override suspend fun afterToolCall(
            context: AgentContext,
            call: ToolCall,
            result: ToolExecutionResult,
            synthetic: Boolean,
            durationMs: Long
        ): ToolExecutionResult = result
        override suspend fun onRunCompleted(context: AgentContext, result: AgentResult) {}
        override suspend fun onRunFailed(context: AgentContext, cause: Throwable) {}
    }

    private fun createPassthroughModalityAdapter() = object : ModalityAdapter(null) {
        override suspend fun resolve(
            messages: List<ChatMessage>,
            resolver: Resolver
        ): List<ChatMessage> = messages.map { resolver.resolve(it, true) }
    }


    private fun createTestDefinition(
        tools: List<String>? = null,
        toolsets: List<String>? = null,
        skills: List<String>? = null,
        subagents: List<String>? = null,
        mcps: List<AgentDefinition.Mcp>? = null
    ) = AgentDefinition(
        name = "testAgent",
        persona = "Test persona",
        provider = AgentDefinition.Provider.Anthropic(
            baseUrl = "https://api.anthropic.com",
            model = "claude-3-5-sonnet",
            apiKey = "sk-test"
        ),
        tools = tools,
        toolsets = toolsets,
        skills = skills,
        subagents = subagents,
        mcps = mcps
    )

    private fun createTool(name: String) = object : Tool {
        override val name: String = name
        override val description: String = "Tool $name"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolExecutionContext): ToolExecutionResult =
            ToolExecutionResult.success(name)
    }

    private fun createSkill(name: String) = object : Skill {
        override val name: String = name
        override val description: String = "Skill $name"
        override suspend fun load(): String = "Skill $name content"
    }

    private fun createSubagent(name: String) = object : Subagent {
        override val name: String = name
        override val description: String = "Subagent $name"
        override val maxIterations: Int? = null
        override val memory: io.github.yeyi.agent.memory.Memory? = null
        override val tools: List<Tool>? = null
        override suspend fun load(): String = "Subagent $name content"
    }

    @Test
    fun `resolve with null tools returns all from both registries`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(createTool("tool1"))
            register(createTool("tool2"))
        }
        val lazyToolRegistry = LazyToolRegistry().apply {
            register(LazyTool(createTool("lazy1")))
        }
        val scope = AgentScope(
            memory = InMemoryMemory(),
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry
        )
        val definition = createTestDefinition(tools = null)

        val info = scope.resolver().resolve(definition)

        assertEquals(2, info.toolRegistry!!.all().size)
        assertEquals(1, info.lazyToolRegistry!!.all().size)
    }

    @Test
    fun `resolve with emptyList tools returns null for both`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(createTool("tool1"))
        }
        val lazyToolRegistry = LazyToolRegistry().apply {
            register(LazyTool(createTool("lazy1")))
        }
        val scope = AgentScope(
            memory = InMemoryMemory(),
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry
        )
        val definition = createTestDefinition(tools = emptyList())

        val info = scope.resolver().resolve(definition)

        assertTrue(info.toolRegistry == null || info.toolRegistry.all().isEmpty())
        assertNull(info.lazyToolRegistry)
    }

    @Test
    fun `resolve with specific tools filters correctly`() = runTest {
        val toolRegistry = io.github.yeyi.agent.tool.ToolRegistry().apply {
            register(createTool("weather"))
            register(createTool("search"))
            register(createTool("calculator"))
        }
        val lazyToolRegistry = LazyToolRegistry().apply {
            register(LazyTool(createTool("lazyWeather")))
        }
        val scope = AgentScope(
            memory = InMemoryMemory(),
            toolRegistry = toolRegistry,
            lazyToolRegistry = lazyToolRegistry
        )
        val definition = createTestDefinition(tools = listOf("weather", "lazyWeather"))

        val info = scope.resolver().resolve(definition)

        assertEquals(1, info.toolRegistry!!.all().size)
        assertEquals("weather", info.toolRegistry.all()[0].name)
        assertEquals(1, info.lazyToolRegistry!!.all().size)
        assertEquals("lazyWeather", info.lazyToolRegistry.all()[0].name)
    }

    @Test
    fun `resolve with null toolsets returns all`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("ts1", "Toolset 1"))
            register(Toolset("ts2", "Toolset 2"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), toolsetRegistry = toolsetRegistry)
        val definition = createTestDefinition(toolsets = null)

        val info = scope.resolver().resolve(definition)

        assertEquals(2, info.toolsetRegistry!!.all().size)
    }

    @Test
    fun `resolve with emptyList toolsets returns null`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("ts1", "Toolset 1"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), toolsetRegistry = toolsetRegistry)
        val definition = createTestDefinition(toolsets = emptyList())

        val info = scope.resolver().resolve(definition)

        assertNull(info.toolsetRegistry)
    }

    @Test
    fun `resolve with specific toolsets filters correctly`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("weather", "Weather"))
            register(Toolset("search", "Search"))
            register(Toolset("calculator", "Calculator"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), toolsetRegistry = toolsetRegistry)
        val definition = createTestDefinition(toolsets = listOf("weather", "search"))

        val info = scope.resolver().resolve(definition)

        assertEquals(2, info.toolsetRegistry!!.all().size)
        assertTrue(info.toolsetRegistry.all().any { it.name == "weather" })
        assertTrue(info.toolsetRegistry.all().any { it.name == "search" })
        assertTrue(info.toolsetRegistry.all().none { it.name == "calculator" })
    }

    @Test
    fun `resolve with null skills returns all`() = runTest {
        val skillRegistry = io.github.yeyi.agent.skill.SkillRegistry().apply {
            register(createSkill("skill1"))
            register(createSkill("skill2"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), skillRegistry = skillRegistry)
        val definition = createTestDefinition(skills = null)

        val info = scope.resolver().resolve(definition)

        assertEquals(2, info.skillRegistry!!.all().size)
    }

    @Test
    fun `resolve with emptyList skills returns null`() = runTest {
        val skillRegistry = io.github.yeyi.agent.skill.SkillRegistry().apply {
            register(createSkill("skill1"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), skillRegistry = skillRegistry)
        val definition = createTestDefinition(skills = emptyList())

        val info = scope.resolver().resolve(definition)

        assertNull(info.skillRegistry)
    }

    @Test
    fun `resolve with null subagents returns all`() = runTest {
        val subagentRegistry = io.github.yeyi.agent.subagent.SubagentRegistry().apply {
            register(createSubagent("subagent1"))
            register(createSubagent("subagent2"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), subagentRegistry = subagentRegistry)
        val definition = createTestDefinition(subagents = null)

        val info = scope.resolver().resolve(definition)

        assertEquals(2, info.subagentRegistry!!.all().size)
    }

    @Test
    fun `resolve with emptyList subagents returns null`() = runTest {
        val subagentRegistry = io.github.yeyi.agent.subagent.SubagentRegistry().apply {
            register(createSubagent("subagent1"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), subagentRegistry = subagentRegistry)
        val definition = createTestDefinition(subagents = emptyList())

        val info = scope.resolver().resolve(definition)

        assertNull(info.subagentRegistry)
    }

    @Test
    fun `resolve with null mcps returns null`() = runTest {
        val scope = AgentScope(memory = InMemoryMemory())
        val definition = createTestDefinition(mcps = null)

        val info = scope.resolver().resolve(definition)

        assertNull(info.mcpRegistry)
    }

    @Test
    fun `resolve with emptyList mcps returns null`() = runTest {
        val scope = AgentScope(memory = InMemoryMemory())
        val definition = createTestDefinition(mcps = emptyList())

        val info = scope.resolver().resolve(definition)

        assertNull(info.mcpRegistry)
    }

    @Test
    fun `resolve with Http transport creates Mcp`() = runTest {
        val scope = AgentScope(memory = InMemoryMemory())
        val definition = createTestDefinition(
            mcps = listOf(
                AgentDefinition.Mcp(
                    name = "httpMcp",
                    description = "HTTP MCP",
                    transport = AgentDefinition.Mcp.Transport.Http(
                        url = "https://mcp.example.com",
                        headers = mapOf("Authorization" to "Bearer token")
                    )
                )
            )
        )

        val info = scope.resolver().resolve(definition)

        assertTrue(info.mcpRegistry != null)
    }

    @Test
    fun `resolve with Stdio transport creates Mcp`() = runTest {
        val scope = AgentScope(memory = InMemoryMemory())
        val definition = createTestDefinition(
            mcps = listOf(
                AgentDefinition.Mcp(
                    name = "stdioMcp",
                    description = "Stdio MCP",
                    transport = AgentDefinition.Mcp.Transport.Stdio(
                        command = "npx",
                        args = listOf("-y", "@modelcontextprotocol/server-filesystem")
                    )
                )
            )
        )

        val info = scope.resolver().resolve(definition)

        assertTrue(info.mcpRegistry != null)
    }

    @Test
    fun `toolsets with mcps both resolve and spawn merges without conflict`() = runTest {
        val toolsetRegistry = io.github.yeyi.agent.toolset.ToolsetRegistry().apply {
            register(Toolset("ts1", "Toolset 1"))
        }
        val scope = AgentScope(memory = InMemoryMemory(), toolsetRegistry = toolsetRegistry)
        val definition = createTestDefinition(
            toolsets = listOf("ts1"),
            mcps = listOf(
                AgentDefinition.Mcp(
                    name = "stdioMcp",
                    description = "Stdio MCP",
                    transport = AgentDefinition.Mcp.Transport.Stdio(command = "echo")
                )
            )
        )

        val info = scope.resolver().resolve(definition)

        // mcps 与 toolsets 同时非空：两者都解析，spawn 经共享插件自动合并，不再互斥
        assertNotNull(info.toolsetRegistry)
        assertNotNull(info.mcpRegistry)

        val agent = definition.spawn(scope)
        assertNotNull(agent)
    }

    @Test
    fun `spawn wires scope hook and modalityAdapter`() = runTest {
        val scope = AgentScope(
            memory = InMemoryMemory(),
            hook = NoopHook(),
            modalityAdapter = createPassthroughModalityAdapter()
        )
        val definition = createTestDefinition()

        val agent = definition.spawn(scope)

        assertNotNull(agent)
    }

    @Test
    fun `spawnedAgent is shutdownable and shutdown is terminal and idempotent`() = runTest {
        val spawned: Shutdownable = createTestDefinition().spawn(AgentScope(memory = InMemoryMemory()))

        spawned.shutdown()
        spawned.shutdown() // 幂等，第二次无副作用

        // shutdown 后 run 抛 IllegalStateException（终态语义，与 BossAgent 一致）
        assertFailsWith<IllegalStateException> { (spawned as Agent).run(AgentQuery.text("ping")) }
    }

    @Test
    fun `spawnedAgent shutdown releases mcp registry`() = runTest {
        val definition = createTestDefinition(
            mcps = listOf(
                AgentDefinition.Mcp(
                    name = "stdioMcp",
                    description = "Stdio MCP",
                    transport = AgentDefinition.Mcp.Transport.Stdio(command = "echo")
                )
            )
        )
        val spawned = definition.spawn(AgentScope(memory = InMemoryMemory()))

        spawned.shutdown()
        spawned.shutdown() // 幂等
    }

    @Test
    fun `spawnedAgent shutdown terminates in-flight run flow`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val delegate = agent {
            llmProvider(GatedLlm(started, gate))
            memory(InMemoryMemory())
        } as ReActAgent
        val spawned = SpawnedAgent(delegate)

        val caught = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                spawned.run(AgentQuery.text("ping")).collect { }
                caught.complete(null)
            } catch (e: CancellationException) {
                caught.complete(e)
            }
        }
        started.await() // 确认 delegate 已挂起在 LLM 调用
        spawned.shutdown()
        job.join()
        // in-flight run 被 shutdown 终止：收集以取消异常收尾（而非静默截断），
        // 也不会挂起等待 gate。
        assertTrue(caught.await() is CancellationException)
    }

    @Test
    fun `spawnedAgent cancel interrupts in-flight run and stays usable`() = runTest {
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val delegate = agent {
            llmProvider(GatedLlm(started, gate))
            memory(InMemoryMemory())
        } as ReActAgent
        val spawned = SpawnedAgent(delegate)

        var completed = false
        val caught = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                spawned.run(AgentQuery.text("ping")).collect { }
                caught.complete(null)
            } catch (e: CancellationException) {
                caught.complete(e)
            }
            completed = true
        }
        started.await()
        spawned.cancel()
        job.join()
        // in-flight run 被 cancel 打断：取消异常原样上抛给收集方，收集方协程本身未被取消
        assertTrue(caught.await() is CancellationException)
        assertTrue(completed)
        assertFalse(job.isCancelled)
        // cancel 是可恢复的中断 —— spawn 产物本身仍然可用
        spawned.shutdown()
        assertFailsWith<IllegalStateException> { spawned.run(AgentQuery.text("ping")) }
    }

    @Test
    fun `spawnedAgent run forwards delegate events to collector`() = runTest {
        // 正常收集路径：delegate emit 的事件必须原样转发给收集方。
        // 此前实现从 launch 子协程 emit 违反 flow 不变式（"Emission from another coroutine"），
        // 本测试守护该路径不再回归。
        val delegate = agent {
            llmProvider(StubLlm)
            memory(InMemoryMemory())
        } as ReActAgent
        val spawned = SpawnedAgent(delegate)

        val events = spawned.run(AgentQuery.text("ping")).toList()

        assertEquals(2, events.size)
        assertTrue(events[0] is AgentEvent.Initial)
        assertTrue(events[1] is AgentEvent.Final)
    }

    @Test
    fun `spawnedAgent run collected after shutdown does not start delegate`() = runTest {
        // run() 在 shutdown 前调用，但 collect 在 shutdown 后——此时 delegate 不应再启动：
        // 收集以取消异常收尾，而非静默零事件，也非启动一轮 LLM 调用踩到已回收的 MCP 注册表。
        val llm = CountingLlm()
        val delegate = agent {
            llmProvider(llm)
            memory(InMemoryMemory())
        } as ReActAgent
        val spawned = SpawnedAgent(delegate)

        val flow = spawned.run(AgentQuery.text("ping"))
        spawned.shutdown()

        assertFailsWith<CancellationException> { flow.toList() }
        assertEquals(0, llm.calls)
    }

    @Test
    fun `spawnedAgent runStream terminates in-flight stream flow`() = runTest {
        // runStream 与 run 遵循同一终态契约：shutdown 终止进行中的流式 flow，收集以取消异常收尾。
        val started = CompletableDeferred<Unit>()
        val gate = CompletableDeferred<Unit>()
        val delegate = agent {
            llmProvider(GatedLlm(started, gate))
            memory(InMemoryMemory())
        } as ReActAgent
        val spawned = SpawnedAgent(delegate)

        val caught = CompletableDeferred<Throwable?>()
        val job = launch {
            try {
                spawned.runStream(AgentQuery.text("ping")).collect { }
                caught.complete(null)
            } catch (e: CancellationException) {
                caught.complete(e)
            }
        }
        started.await() // 确认 delegate 已挂起在 LLM 调用
        spawned.shutdown()
        job.join()
        assertTrue(caught.await() is CancellationException)
    }

    @Test
    fun `spawnedAgent runStream collected after shutdown does not start delegate`() = runTest {
        // runStream 入口 check 通过后 shutdown、collect 前已关闭——delegate 不应再启动，
        // 收集以取消异常收尾而非静默零事件。
        val llm = CountingLlm()
        val delegate = agent {
            llmProvider(llm)
            memory(InMemoryMemory())
        } as ReActAgent
        val spawned = SpawnedAgent(delegate)

        val flow = spawned.runStream(AgentQuery.text("ping"))
        spawned.shutdown()

        assertFailsWith<CancellationException> { flow.toList() }
        assertEquals(0, llm.calls)
    }

    @Test
    fun `spawnedAgent runStream and steer after shutdown throw`() = runTest {
        // 已关闭的 Agent 调用 runStream/steer 直接抛 ISE（与 run 一致，而非透传 delegate）。
        val delegate = agent {
            llmProvider(StubLlm)
            memory(InMemoryMemory())
        } as ReActAgent
        val spawned = SpawnedAgent(delegate)
        spawned.shutdown()

        assertFailsWith<IllegalStateException> { spawned.runStream(AgentQuery.text("ping")) }
        assertFailsWith<IllegalStateException> { spawned.steer(AgentQuery.text("ping")) }
    }

    @Test
    fun `spawnedAgent run without collect still occupies delegate slot`() = runTest {
        // 与 ReActAgent "run 即占槽" 语义对齐：run() 返回后即使未收集，
        // delegate 槽位已被占用——steer() 返回 true 而非误判空闲，
        // 否则调用方按 KDoc 模式（steer()==false 触发 fallback run）会并发双 run。
        val delegate = agent {
            llmProvider(StubLlm)
            memory(InMemoryMemory())
        } as ReActAgent
        val spawned = SpawnedAgent(delegate)

        spawned.run(AgentQuery.text("ping")) // 不 collect
        assertTrue(spawned.steer(AgentQuery.text("ping")))
    }

    // ===== SpawnedAgent 测试桩 =====

    /** 立即返回固定回复的 LLM。 */
    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: ChatRequest): ChatResponse =
            ChatResponse(message = ChatMessage.Assistant("done"), finishReason = FinishReason.Stop)

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flowOf()
    }

    /** 首次调用挂起在 gate 上，用于构造 in-flight run 的 LLM。 */
    private class GatedLlm(
        private val started: CompletableDeferred<Unit>,
        private val gate: CompletableDeferred<Unit>
    ) : LlmProvider {
        override val name: String = "gated"
        override suspend fun chat(request: ChatRequest): ChatResponse {
            started.complete(Unit)
            gate.await()
            return ChatResponse(message = ChatMessage.Assistant("done"), finishReason = FinishReason.Stop)
        }

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow {
            started.complete(Unit)
            gate.await()
            emit(ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
    }

    /** 记录调用次数的 LLM，用于断言 delegate 是否被启动。 */
    private class CountingLlm : LlmProvider {
        var calls = 0
            private set
        override val name: String = "counting"
        override suspend fun chat(request: ChatRequest): ChatResponse {
            calls++
            return ChatResponse(message = ChatMessage.Assistant("done"), finishReason = FinishReason.Stop)
        }

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flowOf()
    }
}
