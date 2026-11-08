package io.github.yeyi.agent.demo.agent.runtime.tools

import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.tool.UiGenTool
import io.github.yeyi.agent.genui.tool.UiSink
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class AllToolsTest {

    private fun stubContext(agentId: String = "test"): ToolExecutionContext = ToolExecutionContext(
        toolCallId = "test-call-id",
        agentContext = testAgentContext(agentId = agentId)
    )

    /**
     * 产卡工具的执行:起订阅者,执行后取回卡片。
     *
     * 产卡工具随 agent 构造并绑定自己的 sink,无订阅者时 emit 抛异常——订阅必须先于执行就位。
     */
    private suspend fun TestScope.executeAndCollectCard(
        agentId: String = "agent_1",
        makeTool: (UiSink) -> UiGenTool,
    ): Pair<ToolExecutionResult, CardIr?> {
        val sink = UiSink()
        val received = mutableListOf<CardIr>()
        val job = backgroundScope.launch { sink.cards.take(1).collect { received += it } }
        runCurrent()

        val result = makeTool(sink).execute(buildJsonObject {}, stubContext(agentId))
        runCurrent()
        if (received.isNotEmpty()) job.join() else job.cancel()
        return result to received.firstOrNull()
    }

    @Test
    fun `GetDateTimeTool 产出时间卡片`() = runTest {
        val (result, card) = executeAndCollectCard { GetDateTimeTool(it) }
        assertFalse(result.isError)
        // 回执携带日期时间数据（数据进记忆，卡片旁路）
        assertTrue(result.parts.text.contains("date"))
        assertTrue(result.parts.text.contains("time"))
        val root = assertNotNull(card).root
        // 方式 1 标准 IR：Card 外壳承载组件树，时间数据在子节点
        assertEquals("Card", root.component)
        assertTrue(root.children.isNotEmpty())
    }

    @Test
    fun `GetLocationTool 产出定位卡片`() = runTest {
        val (result, card) = executeAndCollectCard { GetLocationTool(it) }
        assertFalse(result.isError)
        val root = assertNotNull(card).root
        assertEquals("Location", root.component)
        assertEquals("珠海", root.bindings["city"]!!.jsonPrimitive.content)
        // 模板卡：工具只交 root + 数据，按钮与动作都在模板里，卡片定义不再一分为二
        assertTrue(root.children.isEmpty())
        assertNull(root.action)
    }

    @Test
    fun `CalculatorTool computes simple expression`() = runTest {
        val tool = CalculatorTool()
        val arguments = buildJsonObject { put("expression", JsonPrimitive("(3+5)*7")) }
        val result = tool.execute(arguments, stubContext())
        assertEquals("(3+5)*7 = 56", result.parts.text)
        assertFalse(result.isError)
    }

    @Test
    fun `CalculatorTool returns error on invalid input`() = runTest {
        val tool = CalculatorTool()
        val arguments = buildJsonObject { put("expression", JsonPrimitive("abc")) }
        val result = tool.execute(arguments, stubContext())
        assertTrue(result.isError, "expected error result, got: ${result.parts.text}")
    }
}
