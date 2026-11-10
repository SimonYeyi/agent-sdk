package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.MemoryEntry
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class UiToolTest {

    private class FakeUiTool(
        private val data: JsonElement?,
        private val card: CardIr?,
        schema: ToolParameters = ToolParameters.Empty,
    ) : UiTool() {
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
    private fun modelSchema(tool: UiTool): JsonObject {
        val schema = tool.parametersSchema
        assertTrue(schema is ToolParameters.JsonSchema, "expected merged JsonSchema, got $schema")
        return Json.parseToJsonElement((schema as ToolParameters.JsonSchema).schema).jsonObject
    }

    /** 产卡工具写入结果 metadata 的形态：CARD_METADATA_KEY -> 编码后的卡片 IR。 */
    private fun cardMetadata(card: CardIr): JsonObject =
        buildJsonObject { put(UiTool.CARD_METADATA_KEY, Json.encodeToJsonElement(card)) }

    @Test
    fun `execute 接线 getResult 结果进 buildCard 并编码进结果 metadata`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text"))
        val tool = FakeUiTool(data, card)

        val returned = tool.execute(showCardArgs, context(agentId = "agent_1"))

        // 业务侧看到的入参已剥掉框架参数 show_ui
        assertEquals(arguments, tool.getResultArguments)
        // buildCard 收到的是 getResult 整理后的结构化数据，以及本轮业务入参
        assertEquals(data, tool.buildCardData)
        assertEquals(arguments, tool.buildCardArguments)
        // 卡片编码进结果 metadata，消费方经 decodeCard 读取
        assertEquals(card, returned.decodeCard())
    }

    @Test
    fun `execute 回执为结构化数据文本`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text"))
        val tool = FakeUiTool(data, card)

        val returned = tool.execute(showCardArgs, context())

        // 产了卡：回执附带「卡片已展示给用户」的告知，卡片内嵌进结果 metadata
        assertEquals(
            ToolExecutionResult.success(
                "${data.toString()} (this has already been shown to the user as a ui-card)",
                metadata = cardMetadata(card),
            ),
            returned
        )
    }

    @Test
    fun `无业务结果时回执只有卡片已展示告知`() = runTest {
        // 卡片即问询：无业务数据可回执，回执只剩「卡片已展示给用户」
        val card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text"))
        val tool = FakeUiTool(
            data = null,
            card = card,
        )

        val returned = tool.execute(showCardArgs, context())

        assertEquals(
            ToolExecutionResult.success(
                "(this has already been shown to the user as a ui-card)",
                metadata = cardMetadata(card),
            ),
            returned
        )
    }

    @Test
    fun `产卡后卡片可经结果 metadata 读取 随记忆链路持久化`() = runTest {
        val card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text"))
        val tool = FakeUiTool(
            data = null,
            card = card,
        )

        val returned = tool.execute(showCardArgs, context())

        // 实时路径：消费方经 ToolExecutionResult.decodeCard 读取卡片
        assertEquals(card, returned.decodeCard())
        // 卡片随结果 metadata 走记忆链路：execute 编码进 CARD_METADATA_KEY，
        // 由归档层提取到 MemoryEntry 顶层 metadata；恢复路径经 MemoryEntry.decodeCard
        // 读取解码渲染
        val cardFromMetadata = MemoryEntry(
            ChatMessage.ToolResult(toolCallId = "call_1", toolName = "fake", parts = emptyList()),
            metadata = returned.metadata,
        ).decodeCard()
        assertEquals(card, cardFromMetadata)
    }

    @Test
    fun `无 metadata 时 decodeCard 返回 null`() {
        // 普通工具结果无卡片 metadata：读卡为空
        assertNull(ToolExecutionResult.success("plain").decodeCard())
        // 有 metadata 但不存在卡片键：同样读卡为空
        assertNull(
            ToolExecutionResult.success(
                "meta",
                metadata = buildJsonObject { put("other", JsonPrimitive(1)) },
            ).decodeCard()
        )
    }

    @Test
    fun `无业务结果又不出卡时判失败`() = runTest {
        // 既无数据回执又不出卡：本轮什么都没交付给用户
        val tool = FakeUiTool(
            data = null,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
        )

        assertFailsWith<IllegalStateException> {
            tool.execute(JsonObject(mapOf("show_ui" to JsonPrimitive(false))), context())
        }
    }

    @Test
    fun `既无数据又不出卡时错误回执指向模型侧的修法`() = runTest {
        val tool = FakeUiTool(
            data = null,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
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
        val tool = FakeUiTool(
            data = data,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
        )

        val returned = tool.execute(showCardArgs, context())

        assertTrue(tool.finalizable(showCardArgs, returned))
    }

    @Test
    fun `show_ui=true 却未产出卡片时判失败不终结`() = runTest {
        // 模型要求出卡就必须真有卡片：没有卡片就没有交付给用户的东西
        val tool = FakeUiTool(
            data = null,
            card = null,
        )

        assertFailsWith<IllegalStateException> { tool.execute(showCardArgs, context()) }
        assertFalse(tool.finalizable(showCardArgs, ToolExecutionResult.error("no card")))
    }

    @Test
    fun `show_ui=false 时不调用 buildCard 且不终结`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val tool = FakeUiTool(
            data = data,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
        )

        // 不出卡由模型说了算：buildCard 压根不被调用
        val noCardArgs =
            JsonObject(mapOf("city" to JsonPrimitive("上海"), "show_ui" to JsonPrimitive(false)))
        val returned = tool.execute(noCardArgs, context())

        assertEquals(0, tool.buildCardCalls)
        // 回执即数据，不追加「卡片已展示」的告知
        assertEquals(ToolExecutionResult.success(data.toString()), returned)
        assertFalse(tool.finalizable(noCardArgs, returned))
    }

    @Test
    fun `错误回执拒绝终结`() = runTest {
        val tool = object : UiTool() {
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
    fun `两个 agent 各自的产卡工具卡片互不串味`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        // 产卡工具随 agent 构造：每个 agent 一份实例，卡片编码进各自结果 metadata
        val toolA = FakeUiTool(
            data,
            CardIr(instanceId = "call_a", root = TreeNode(component = "Text")),
        )
        val toolB = FakeUiTool(
            data,
            CardIr(instanceId = "call_b", root = TreeNode(component = "Text")),
        )

        val returnedA = toolA.execute(showCardArgs, context(callId = "call_a", agentId = "agent_a"))
        val returnedB = toolB.execute(showCardArgs, context(callId = "call_b", agentId = "agent_b"))

        // 各 agent 只从自己的结果里读到自己的卡片，不串味
        assertEquals("call_a", returnedA.decodeCard()!!.instanceId)
        assertEquals("call_b", returnedB.decodeCard()!!.instanceId)
    }

    @Test
    fun `show_ui 缺省时不出卡也不终结`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val tool = FakeUiTool(
            data = data,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
        )

        // 模型没传 show_ui：按不出卡、不终结处理（中间步骤工具调用不打断推理链）
        val returned = tool.execute(arguments, context())

        assertEquals(0, tool.buildCardCalls)
        assertEquals(ToolExecutionResult.success(data.toString()), returned)
        assertFalse(tool.finalizable(arguments, returned))
    }

    @Test
    fun `框架参数 show_ui 不渗进业务入参`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val tool = FakeUiTool(
            data = data,
            card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text")),
        )

        val withShowUi = JsonObject(
            mapOf("city" to JsonPrimitive("北京"), "show_ui" to JsonPrimitive(true))
        )
        tool.execute(withShowUi, context())

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
        val tool = FakeUiTool(
            data = null,
            card = null,
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
        val tool = FakeUiTool(
            data = null,
            card = null,
            schema = ToolParameters.JsonSchema("""[1,2,3]"""),
        )

        // 并不进 show_ui 就等于模型永远看不到这个参数：装配错误，不能静默放过
        val failure = assertFailsWith<IllegalArgumentException> { tool.parametersSchema }
        assertTrue(failure.message?.contains("show_ui") == true)
    }

    @Test
    fun `业务 schema 不是合法 JSON 时判装配错误`() = runTest {
        val tool = FakeUiTool(
            data = null,
            card = null,
            schema = ToolParameters.JsonSchema("""{"type":"object",""""),
        )

        assertFailsWith<IllegalArgumentException> { tool.parametersSchema }
    }

    @Test
    fun `业务 schema 自带 show_ui 属性时判装配错误`() = runTest {
        val tool = FakeUiTool(
            data = null,
            card = null,
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
        val tool = FakeUiTool(data = null, card = null)

        val properties = modelSchema(tool)["properties"]!!.jsonObject

        assertTrue("show_ui" in properties)
    }
}
