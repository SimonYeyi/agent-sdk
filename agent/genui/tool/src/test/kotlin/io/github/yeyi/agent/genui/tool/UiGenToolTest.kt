package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UiGenToolTest {

    private class FakeUiGenTool(
        private val data: JsonElement,
        private val card: CardIr?,
        registry: UiSinkRegistry,
    ) : UiGenTool(registry) {
        var getResultArguments: JsonElement? = null
        var buildCardData: JsonElement? = null
        var buildCardArguments: JsonElement? = null

        override val name: String = "fake_tool"
        override val description: String = "fake"
        override val parametersSchema: ToolParameters = ToolParameters.Empty

        override suspend fun getResult(
            arguments: JsonElement,
            context: ToolExecutionContext,
        ): JsonElement {
            getResultArguments = arguments
            return data
        }

        override fun buildCard(
            result: JsonElement?,
            arguments: JsonElement,
            context: ToolExecutionContext
        ): CardIr? {
            buildCardData = result
            buildCardArguments = arguments
            return card
        }
    }

    private object UnusedLlm : LlmProvider {
        override val name: String = "unused"
        override suspend fun chat(request: ChatRequest): ChatResponse =
            error("LlmProvider.chat must not be called in UiGenToolTest")
        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> =
            flowOf(ChatResponseEvent.Error(IllegalStateException("unused")))
    }

    private fun context(callId: String = "call_1", agentId: String = "agent_1"): ToolExecutionContext = ToolExecutionContext(
        toolCallId = callId,
        agentContext = testAgentContext(
            llmProvider = UnusedLlm,
            agentId = agentId,
        ),
    )

    /** 创建 registry 并登记 sink，默认登记在 "agent_1" 名下。 */
    private fun registeredRegistry(sink: UiSink, agentId: String = "agent_1"): UiSinkRegistry =
        UiSinkRegistry().apply { register(agentId, sink) }

    private val arguments: JsonElement =
        JsonObject(mapOf("city" to JsonPrimitive("上海")))

    @Test
    fun `execute 接线 getResult 结果进 buildCard 并发射卡片`() = runTest {
        val sink = UiSink()
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text"))
        val tool = FakeUiGenTool(data, card, registeredRegistry(sink))

        val received = mutableListOf<CardIr>()
        val job = backgroundScope.launch { sink.cards.take(1).collect { received += it } }
        runCurrent() // 排空调度队列，确保订阅注册后再执行

        val returned = tool.execute(arguments, context(agentId = "agent_1"))
        runCurrent() // 处理订阅者恢复任务，确保卡片已投递

        job.join()
        assertEquals(arguments, tool.getResultArguments)
        // buildCard 收到的是 getResult 整理后的结构化数据，以及本轮原始入参
        assertEquals(data, tool.buildCardData)
        assertEquals(arguments, tool.buildCardArguments)
        assertEquals(listOf(card), received)
    }

    @Test
    fun `buildCard 返回 null 时不发射卡片`() = runTest {
        val sink = UiSink()
        val tool = FakeUiGenTool(
            data = JsonObject(mapOf("city" to JsonPrimitive("北京"))),
            card = null,
            registry = registeredRegistry(sink),
        )

        var cardCount = 0
        val job = backgroundScope.launch { sink.cards.collect { cardCount++ } }
        runCurrent() // 排空调度队列，确保订阅注册后再执行

        val returned = tool.execute(arguments, context(agentId = "agent_1"))
        runCurrent() // 处理订阅者恢复任务，确认无卡片投递

        job.cancel()
        assertEquals(0, cardCount)
    }

    @Test
    fun `execute 回执为结构化数据文本`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val sink = UiSink()
        val tool = FakeUiGenTool(
            data,
            CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
            registeredRegistry(sink),
        )
        val job = backgroundScope.launch { sink.cards.collect { } }
        runCurrent() // 排空调度队列，确保订阅注册后再执行

        val returned = tool.execute(arguments, context())
        runCurrent()

        job.cancel()
        assertEquals(ToolExecutionResult.success(data.toString()), returned)
    }

    @Test
    fun `执行成功后默认可终结`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val tool = FakeUiGenTool(data, null, UiSinkRegistry())

        val returned = tool.execute(arguments, context())

        assertTrue(tool.finalizable(returned))
    }

    @Test
    fun `错误回执拒绝终结`() = runTest {
        val tool = object : UiGenTool(UiSinkRegistry()) {
            override val name: String = "flaky_card"
            override val description: String = "flaky"
            override val parametersSchema: ToolParameters = ToolParameters.Empty
            override suspend fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement =
                error("card generation failed")
            override fun buildCard(
                result: JsonElement?,
                arguments: JsonElement,
                context: ToolExecutionContext
            ): CardIr? = null
        }

        // execute 抛异常向上传播，registry 会统一转为错误回执（isError=true），
        // finalizable 收到错误回执后拒绝终结，模型下一轮感知失败并重试
        assertFailsWith<IllegalStateException> { tool.execute(arguments, context()) }

        assertFalse(tool.finalizable(ToolExecutionResult.error("card generation failed")))
    }

    @Test
    fun `agentId 未登记时抛异常暴露配置缺失`() = runTest {
        // 空 registry：即使 context 携带 agentId，也未登记 sink——产卡工具装配配置缺失应 fail-fast
        val tool = FakeUiGenTool(
            data = JsonObject(mapOf("city" to JsonPrimitive("北京"))),
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
            registry = UiSinkRegistry(),
        )

        val e = assertFailsWith<IllegalStateException> { tool.execute(arguments, context(agentId = "agent_9")) }
        assertTrue(e.message!!.contains("agent_9"))
    }

    @Test
    fun `多 agent 共享工具实例时卡片路由隔离`() = runTest {
        val sinkA = UiSink()
        val sinkB = UiSink()
        val registry = UiSinkRegistry().apply {
            register("agent_a", sinkA)
            register("agent_b", sinkB)
        }
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val tool = FakeUiGenTool(data, CardIr(instanceId = "call_1", root = TreeNode(component = "Text")), registry)

        val receivedA = mutableListOf<CardIr>()
        val receivedB = mutableListOf<CardIr>()
        val jobA = backgroundScope.launch { sinkA.cards.take(1).collect { receivedA += it } }
        val jobB = backgroundScope.launch { sinkB.cards.take(1).collect { receivedB += it } }
        runCurrent() // 排空调度队列，确保订阅注册后再执行

        // 同一工具实例，两个 agent 先后执行
        tool.execute(arguments, context(callId = "call_a", agentId = "agent_a"))
        tool.execute(arguments, context(callId = "call_b", agentId = "agent_b"))
        runCurrent() // 处理订阅者恢复任务，确保卡片已投递

        jobA.join()
        jobB.join()
        // 各 agent 只收到自己的卡片，不串味
        assertEquals(1, receivedA.size)
        assertEquals(1, receivedB.size)
    }
}
