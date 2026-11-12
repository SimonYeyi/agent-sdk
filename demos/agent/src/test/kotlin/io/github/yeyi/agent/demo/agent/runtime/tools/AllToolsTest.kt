package io.github.yeyi.agent.demo.agent.runtime.tools

import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.tool.WithUiTool
import io.github.yeyi.agent.genui.tool.decodeCard
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
     * 产卡工具的执行：执行后直接从结果 metadata 读取卡片。
     */
    private suspend fun executeAndDecodeCard(
        agentId: String = "agent_1",
        makeTool: () -> WithUiTool,
    ): Pair<ToolExecutionResult, CardIr?> {
        val result = makeTool().execute(
            buildJsonObject { put("show_ui", JsonPrimitive(true)) },
            stubContext(agentId),
        )
        return result to result.decodeCard()
    }

    @Test
    fun `GetDateTimeTool 产出时间卡片`() = runTest {
        val (result, card) = executeAndDecodeCard { GetDateTimeTool() }
        assertFalse(result.isError)
        // 回执携带日期时间数据（数据进记忆，卡片内嵌结果 metadata）
        assertTrue(result.parts.text.contains("date"))
        assertTrue(result.parts.text.contains("time"))
        val root = assertNotNull(card).root
        // 方式 1 标准 IR：Card 外壳承载组件树，时间数据在子节点
        assertEquals("Card", root.component)
        assertTrue(root.children.isNotEmpty())
    }

    @Test
    fun `GetLocationTool 产出定位卡片`() = runTest {
        val (result, card) = executeAndDecodeCard { GetLocationTool() }
        assertFalse(result.isError)
        val root = assertNotNull(card).root
        assertEquals("Location", root.component)
        assertEquals("珠海", root.bindings["city"]!!.jsonPrimitive.content)
        // 模板卡：工具只交 root + 数据，按钮与动作都在模板里，卡片定义不再一分为二
        assertTrue(root.children.isEmpty())
        assertNull(root.action)
    }

    @Test
    fun `带 show_ui 入参时仍能取数 不因未知键失败`() = runTest {
        // 框架参数只是附加：工具的入参解码不该因多出的 show_ui 键而失败
        val result = WeatherCardTool().execute(
            buildJsonObject {
                put("city", JsonPrimitive("北京"))
                put("show_ui", JsonPrimitive(true))
            },
            stubContext(),
        )

        assertFalse(result.isError)
        assertTrue(result.parts.text.contains("北京"))
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
