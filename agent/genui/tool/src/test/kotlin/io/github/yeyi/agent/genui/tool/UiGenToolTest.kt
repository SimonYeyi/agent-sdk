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
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UiGenToolTest {

    private class FakeUiGenTool(
        private val data: JsonElement?,
        private val card: CardIr?,
        sink: UiSink,
        schema: ToolParameters = ToolParameters.Empty,
    ) : UiGenTool(sink) {
        var getResultArguments: JsonElement? = null
        var buildCardData: JsonElement? = null
        var buildCardArguments: JsonElement? = null
        var buildCardCalls: Int = 0

        override val name: String = "fake_tool"
        override val description: String = "fake"
        override val toolSchema: ToolParameters = schema

        override suspend fun getResult(
            arguments: JsonElement,
            context: ToolExecutionContext,
        ): JsonElement? {
            getResultArguments = arguments
            return data
        }

        override fun buildCard(
            arguments: JsonElement,
            result: JsonElement?,
            context: ToolExecutionContext
        ): CardIr? {
            buildCardCalls++
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

    private fun context(
        callId: String = "call_1",
        agentId: String = "agent_1"
    ): ToolExecutionContext = ToolExecutionContext(
        toolCallId = callId,
        agentContext = testAgentContext(
            llmProvider = UnusedLlm,
            agentId = agentId,
        ),
    )

    private val arguments: JsonElement =
        JsonObject(mapOf("city" to JsonPrimitive("上海")))

    /** 模型显式要求出卡的入参。 */
    private val showCardArgs: JsonElement =
        JsonObject(mapOf("city" to JsonPrimitive("上海"), "show_ui" to JsonPrimitive(true)))

    /** 模型看到的 schema：基类把业务 schema 与框架参数 show_ui 合并后的结果。 */
    private fun modelSchema(tool: UiGenTool): JsonObject {
        val schema = tool.parametersSchema
        assertTrue(schema is ToolParameters.JsonSchema, "expected merged JsonSchema, got $schema")
        return Json.parseToJsonElement((schema as ToolParameters.JsonSchema).schema).jsonObject
    }

    @Test
    fun `execute 接线 getResult 结果进 buildCard 并发射卡片`() = runTest {
        val sink = UiSink()
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text"))
        val tool = FakeUiGenTool(data, card, sink)

        val received = mutableListOf<CardIr>()
        val job = backgroundScope.launch { sink.cards.take(1).collect { received += it } }
        runCurrent() // 排空调度队列，确保订阅注册后再执行

        tool.execute(showCardArgs, context(agentId = "agent_1"))
        runCurrent() // 处理订阅者恢复任务，确保卡片已投递

        job.join()
        // 业务侧看到的入参已剥掉框架参数 show_ui
        assertEquals(arguments, tool.getResultArguments)
        // buildCard 收到的是 getResult 整理后的结构化数据，以及本轮业务入参
        assertEquals(data, tool.buildCardData)
        assertEquals(arguments, tool.buildCardArguments)
        assertEquals(listOf(card), received)
    }

    @Test
    fun `execute 回执为结构化数据文本`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val sink = UiSink()
        val tool = FakeUiGenTool(
            data,
            CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
            sink,
        )
        val job = backgroundScope.launch { sink.cards.collect { } }
        runCurrent() // 排空调度队列，确保订阅注册后再执行

        val returned = tool.execute(showCardArgs, context())
        runCurrent()

        job.cancel()
        // 产了卡：回执附带「卡片已展示给用户」的告知
        assertEquals(
            ToolExecutionResult.success(
                "${data.toString()} (this has already been shown to the user as a ui-card)"
            ),
            returned
        )
    }

    @Test
    fun `无业务结果时回执只有卡片已展示告知`() = runTest {
        val sink = UiSink()
        // 卡片即问询：无业务数据可回执，回执只剩「卡片已展示给用户」
        val tool = FakeUiGenTool(
            data = null,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
            sink = sink,
        )
        val job = backgroundScope.launch { sink.cards.collect { } }
        runCurrent() // 排空调度队列，确保订阅注册后再执行

        val returned = tool.execute(showCardArgs, context())
        runCurrent()

        job.cancel()
        assertEquals(
            ToolExecutionResult.success("(this has already been shown to the user as a ui-card)"),
            returned
        )
    }

    @Test
    fun `无业务结果又不出卡时判失败`() = runTest {
        val sink = UiSink()
        // 既无数据回执又不出卡：本轮什么都没交付给用户
        val tool = FakeUiGenTool(
            data = null,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
            sink = sink,
        )
        val job = backgroundScope.launch { sink.cards.collect { } }
        runCurrent()

        assertFailsWith<IllegalStateException> {
            tool.execute(JsonObject(mapOf("show_ui" to JsonPrimitive(false))), context())
        }

        job.cancel()
    }

    @Test
    fun `既无数据又不出卡时错误回执指向模型侧的修法`() = runTest {
        val sink = UiSink()
        val tool = FakeUiGenTool(
            data = null,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
            sink = sink,
        )

        // 错误措辞要指向模型能改的地方：带 show_ui=true 重新调用
        val failure = assertFailsWith<IllegalStateException> {
            tool.execute(JsonObject(mapOf("show_ui" to JsonPrimitive(false))), context())
        }
        assertTrue(failure.message?.contains("show_ui=true") == true)
    }

    @Test
    fun `出卡即终结 卡片交到用户手上本轮到此为止`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val sink = UiSink()
        val tool = FakeUiGenTool(
            data = data,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
            sink = sink,
        )
        val job = backgroundScope.launch { sink.cards.take(1).collect { } }
        runCurrent()

        val returned = tool.execute(showCardArgs, context())
        runCurrent()

        job.join()
        assertTrue(tool.finalizable(showCardArgs, returned))
    }

    @Test
    fun `show_ui=true 却未产出卡片时判失败不终结`() = runTest {
        val sink = UiSink()
        // 模型要求出卡就必须真有卡片：没有卡片就没有交付给用户的东西
        val tool = FakeUiGenTool(
            data = null,
            card = null,
            sink = sink,
        )
        val job = backgroundScope.launch { sink.cards.collect { } }
        runCurrent()

        assertFailsWith<IllegalStateException> { tool.execute(showCardArgs, context()) }
        assertFalse(tool.finalizable(showCardArgs, ToolExecutionResult.error("no card")))

        job.cancel()
    }

    @Test
    fun `show_ui=false 时不调用 buildCard 且不终结`() = runTest {
        val sink = UiSink()
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val tool = FakeUiGenTool(
            data = data,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
            sink = sink,
        )
        var cardCount = 0
        val job = backgroundScope.launch { sink.cards.collect { cardCount++ } }
        runCurrent()

        val noCardArgs =
            JsonObject(mapOf("city" to JsonPrimitive("上海"), "show_ui" to JsonPrimitive(false)))
        val returned = tool.execute(noCardArgs, context())
        runCurrent()

        job.cancel()
        // 不出卡由模型说了算：buildCard 压根不被调用
        assertEquals(0, tool.buildCardCalls)
        assertEquals(0, cardCount)
        // 回执即数据，不追加「卡片已展示」的告知
        assertEquals(ToolExecutionResult.success(data.toString()), returned)
        assertFalse(tool.finalizable(noCardArgs, returned))
    }

    @Test
    fun `错误回执拒绝终结`() = runTest {
        val tool = object : UiGenTool(UiSink()) {
            override val name: String = "flaky_card"
            override val description: String = "flaky"
            override val toolSchema: ToolParameters = ToolParameters.Empty

            override suspend fun getResult(
                arguments: JsonElement,
                context: ToolExecutionContext
            ): JsonElement =
                error("card generation failed")

            override fun buildCard(
                arguments: JsonElement,
                result: JsonElement?,
                context: ToolExecutionContext
            ): CardIr? = null
        }

        // execute 抛异常向上传播，工具执行链路统一转为错误回执（isError=true），
        // finalizable 收到错误回执后拒绝终结，模型下一轮感知失败并重试
        assertFailsWith<IllegalStateException> { tool.execute(arguments, context()) }

        assertFalse(
            tool.finalizable(
                arguments,
                ToolExecutionResult.error("card generation failed")
            )
        )
    }

    @Test
    fun `两个 agent 各自的产卡工具互不串味`() = runTest {
        val sinkA = UiSink()
        val sinkB = UiSink()
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        // 产卡工具随 agent 构造：每个 agent 一份实例，绑定自己的 sink
        val toolA = FakeUiGenTool(
            data,
            CardIr(instanceId = "call_a", root = TreeNode(component = "Text")),
            sinkA,
        )
        val toolB = FakeUiGenTool(
            data,
            CardIr(instanceId = "call_b", root = TreeNode(component = "Text")),
            sinkB,
        )

        val receivedA = mutableListOf<CardIr>()
        val receivedB = mutableListOf<CardIr>()
        val jobA = backgroundScope.launch { sinkA.cards.take(1).collect { receivedA += it } }
        val jobB = backgroundScope.launch { sinkB.cards.take(1).collect { receivedB += it } }
        runCurrent() // 排空调度队列，确保订阅注册后再执行

        toolA.execute(showCardArgs, context(callId = "call_a", agentId = "agent_a"))
        toolB.execute(showCardArgs, context(callId = "call_b", agentId = "agent_b"))
        runCurrent() // 处理订阅者恢复任务，确保卡片已投递

        jobA.join()
        jobB.join()
        // 各 agent 只收到自己的卡片，不串味
        assertEquals(1, receivedA.size)
        assertEquals(1, receivedB.size)
        assertEquals("call_a", receivedA.single().instanceId)
        assertEquals("call_b", receivedB.single().instanceId)
    }

    @Test
    fun `show_ui 缺省时不出卡也不终结`() = runTest {
        val sink = UiSink()
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val tool = FakeUiGenTool(
            data = data,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
            sink = sink,
        )
        var cardCount = 0
        val job = backgroundScope.launch { sink.cards.collect { cardCount++ } }
        runCurrent()

        // 模型没传 show_ui：按不出卡、不终结处理（中间步骤工具调用不打断推理链）
        val returned = tool.execute(arguments, context())
        runCurrent()

        job.cancel()
        assertEquals(0, cardCount)
        assertEquals(0, tool.buildCardCalls)
        assertEquals(ToolExecutionResult.success(data.toString()), returned)
        assertFalse(tool.finalizable(arguments, returned))
    }

    @Test
    fun `框架参数 show_ui 不渗进业务入参`() = runTest {
        val sink = UiSink()
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val tool = FakeUiGenTool(
            data = data,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
            sink = sink,
        )
        val job = backgroundScope.launch { sink.cards.take(1).collect { } }
        runCurrent()

        val withShowUi = JsonObject(
            mapOf("city" to JsonPrimitive("北京"), "show_ui" to JsonPrimitive(true))
        )
        tool.execute(withShowUi, context())
        runCurrent()

        job.join()
        // 业务侧只看得到业务入参：show_ui 由基类剥掉，严格解码的工具不必为它放宽
        val businessOnly = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        assertEquals(businessOnly, tool.getResultArguments)
        assertEquals(businessOnly, tool.buildCardArguments)
    }

    @Test
    fun `模型看到的 schema 含 show_ui 且保留业务参数`() = runTest {
        val businessSchema = ToolParameters.JsonSchema(
            """{"type":"object","properties":{"city":{"type":"string"}},"required":["city"]}"""
        )
        val tool = FakeUiGenTool(
            data = null,
            card = null,
            sink = UiSink(),
            schema = businessSchema,
        )

        val schema = modelSchema(tool)
        val properties = schema["properties"]!!.jsonObject

        assertTrue("city" in properties)
        val showUi = properties.getValue("show_ui").jsonObject
        assertEquals("boolean", showUi.getValue("type").jsonPrimitive.content)
        assertTrue(showUi.getValue("description").jsonPrimitive.content.isNotEmpty())
        // show_ui 必传：模型每次调用都得表态出不出卡
        val required = (schema["required"] as? JsonArray).orEmpty().map { it.jsonPrimitive.content }
        assertTrue("show_ui" in required)
        // 业务必填项保留
        assertTrue("city" in required)
    }

    @Test
    fun `业务 schema 不是 JSON 对象时判装配错误`() = runTest {
        val tool = FakeUiGenTool(
            data = null,
            card = null,
            sink = UiSink(),
            schema = ToolParameters.JsonSchema("""[1,2,3]"""),
        )

        // 并不进 show_ui 就等于模型永远看不到这个参数：装配错误，不能静默放过
        val failure = assertFailsWith<IllegalArgumentException> { tool.parametersSchema }
        assertTrue(failure.message?.contains("show_ui") == true)
    }

    @Test
    fun `业务 schema 不是合法 JSON 时判装配错误`() = runTest {
        val tool = FakeUiGenTool(
            data = null,
            card = null,
            sink = UiSink(),
            schema = ToolParameters.JsonSchema("""{"type":"object","""),
        )

        assertFailsWith<IllegalArgumentException> { tool.parametersSchema }
    }

    @Test
    fun `业务 schema 自带 show_ui 属性时判装配错误`() = runTest {
        val tool = FakeUiGenTool(
            data = null,
            card = null,
            sink = UiSink(),
            schema = ToolParameters.JsonSchema(
                """{"type":"object","properties":{"show_ui":{"type":"string"}}}"""
            ),
        )

        // 框架版本会静默盖掉业务同名属性：业务参数就此消失，必须拦住
        val failure = assertFailsWith<IllegalArgumentException> { tool.parametersSchema }
        assertTrue(failure.message?.contains("show_ui") == true)
    }

    @Test
    fun `无业务参数的工具也给模型 show_ui`() = runTest {
        val tool = FakeUiGenTool(data = null, card = null, sink = UiSink())

        val properties = modelSchema(tool)["properties"]!!.jsonObject

        assertTrue("show_ui" in properties)
    }
}
