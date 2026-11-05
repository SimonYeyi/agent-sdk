package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentPersona
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

@OptIn(ExperimentalCoroutinesApi::class)
class UiGenToolTest {

    private class FakeUiGenTool(
        private val data: JsonElement,
        private val card: CardIr?,
        uiSink: UiSink,
    ) : UiGenTool(uiSink) {
        var getDataArguments: JsonElement? = null
        var buildCardData: JsonElement? = null

        override val name: String = "fake_tool"
        override val description: String = "fake"
        override val parametersSchema: ToolParameters = ToolParameters.Empty

        override fun getData(
            arguments: JsonElement,
            context: ToolExecutionContext,
        ): JsonElement {
            getDataArguments = arguments
            return data
        }

        override fun buildCard(data: JsonElement, context: ToolExecutionContext): CardIr? {
            buildCardData = data
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

    private fun context(callId: String = "call_1"): ToolExecutionContext = ToolExecutionContext(
        toolCallId = callId,
        agentContext = AgentContext(
            persona = AgentPersona(""),
            maxIterations = 1,
            currentIteration = 1,
            memory = InMemoryMemory(),
            llmProvider = UnusedLlm,
            tools = emptyList(),
            maxRounds = 20,
        ),
    )

    private val arguments: JsonElement =
        JsonObject(mapOf("city" to JsonPrimitive("上海")))

    @Test
    fun `execute 接线 getData 结果进 buildCard 并发射卡片`() = runTest {
        val sink = UiSink()
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val card = CardIr(instanceId = "call_1", root = TreeNode(component = "Text"))
        val tool = FakeUiGenTool(data, card, sink)

        val received = mutableListOf<CardIr>()
        val job = backgroundScope.launch { sink.cards.take(1).collect { received += it } }
        runCurrent() // 排空调度队列，确保订阅注册后再执行

        val returned = tool.execute(arguments, context())
        runCurrent() // 处理订阅者恢复任务，确保卡片已投递

        job.join()
        assertEquals(arguments, tool.getDataArguments)
        // buildCard 收到的是 getData 整理后的结构化数据
        assertEquals(data, tool.buildCardData)
        assertEquals(listOf(card), received)
    }

    @Test
    fun `buildCard 返回 null 时不发射卡片`() = runTest {
        val sink = UiSink()
        val tool = FakeUiGenTool(
            data = JsonObject(mapOf("city" to JsonPrimitive("北京"))),
            card = null,
            uiSink = sink,
        )

        var cardCount = 0
        val job = backgroundScope.launch { sink.cards.collect { cardCount++ } }
        runCurrent() // 排空调度队列，确保订阅注册后再执行

        val returned = tool.execute(arguments, context())
        runCurrent() // 处理订阅者恢复任务，确认无卡片投递

        job.cancel()
        assertEquals(0, cardCount)
    }

    @Test
    fun `execute 回执为结构化数据文本`() = runTest {
        val data = JsonObject(mapOf("city" to JsonPrimitive("北京")))
        val tool = FakeUiGenTool(data, CardIr(instanceId = "call_1", root = TreeNode(component = "Text")), UiSink())

        val returned = tool.execute(arguments, context())

        assertEquals(ToolExecutionResult.success(data.toString()), returned)
    }
}
