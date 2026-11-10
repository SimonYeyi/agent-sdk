package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.tool.ToolExecutionContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class GenUiToolTest {

    private val cardJson =
        """{"root":{"component":"Card","children":[{"component":"Text",""" +
                """"bindings":{"content":"北京"}}]}}"""

    private fun context(
        reply: String,
        callId: String = "call_1",
    ): Pair<ToolExecutionContext, FakeLlmProvider> {
        val llm = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = reply),
                    finishReason = FinishReason.Stop,
                )
            )
        )
        return ToolExecutionContext(
            toolCallId = callId,
            agentContext = testAgentContext(llmProvider = llm),
        ) to llm
    }

    @Test
    fun `生成的卡片编码进结果 metadata 可被消费方读取`() = runTest {
        val (ctx, _) = context(cardJson)

        val returned = GenUiTool().execute(
            buildJsonObject { put("content", JsonPrimitive("北京今天晴")) },
            ctx,
        )

        val card = assertNotNull(returned.decodeCard())
        assertEquals("call_1", card.instanceId)
        assertEquals("Card", card.root.component)
        assertEquals("北京", card.root.children.single().bindings["content"]!!.jsonPrimitive.content)
    }

    @Test
    fun `回执只告知卡片已送达`() = runTest {
        val (ctx, _) = context(cardJson)

        val returned = GenUiTool().execute(
            buildJsonObject { put("content", JsonPrimitive("北京今天晴")) },
            ctx,
        )

        assertFalse(returned.isError)
        assertEquals(
            ToolExecutionResult.success(
                "(this has already been shown to the user as a ui-card)",
                metadata = returned.metadata,
            ),
            returned,
        )
    }

    @Test
    fun `出卡即终结`() = runTest {
        val (ctx, _) = context(cardJson)
        val tool = GenUiTool()

        val returned = tool.execute(
            buildJsonObject { put("content", JsonPrimitive("北京今天晴")) },
            ctx,
        )

        assertTrue(tool.finalizable(buildJsonObject {}, returned))
    }

    @Test
    fun `生成失败时拒绝终结`() = runTest {
        val (ctx, _) = context("这不是 JSON")
        val tool = GenUiTool()

        assertFailsWith<IllegalStateException> {
            tool.execute(buildJsonObject { put("content", JsonPrimitive("x")) }, ctx)
        }
        assertFalse(tool.finalizable(buildJsonObject {}, ToolExecutionResult.error("failed")))
    }

    @Test
    fun `模型回的内容带 markdown 代码块也能解出卡片`() = runTest {
        val (ctx, _) = context("```json\n$cardJson\n```")

        val returned = GenUiTool().execute(
            buildJsonObject { put("content", JsonPrimitive("北京今天晴")) },
            ctx,
        )

        assertEquals("Card", assertNotNull(returned.decodeCard()).root.component)
    }

    @Test
    fun `模型回的内容前后有解释文字也能解出卡片`() = runTest {
        val (ctx, _) = context("好的，这是卡片：\n$cardJson\n希望你喜欢。")

        val returned = GenUiTool().execute(
            buildJsonObject { put("content", JsonPrimitive("北京今天晴")) },
            ctx,
        )

        assertEquals("Card", assertNotNull(returned.decodeCard()).root.component)
    }

    @Test
    fun `模型直接回节点对象也能解出卡片`() = runTest {
        val (ctx, _) = context("""{"component":"Card","children":[]}""")

        val returned = GenUiTool().execute(
            buildJsonObject { put("content", JsonPrimitive("北京今天晴")) },
            ctx,
        )

        assertEquals("Card", assertNotNull(returned.decodeCard()).root.component)
    }

    @Test
    fun `缺少必传的 content 时判失败`() = runTest {
        val (ctx, _) = context(cardJson)

        // content 是必传项：缺了就没有可排版的内容
        assertFailsWith<IllegalArgumentException> {
            GenUiTool().execute(buildJsonObject {}, ctx)
        }
    }

    @Test
    fun `模型没回内容时判失败`() = runTest {        val llm = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(content = null),
                    finishReason = FinishReason.Stop,
                )
            )
        )
        val ctx = ToolExecutionContext(
            toolCallId = "call_1",
            agentContext = testAgentContext(llmProvider = llm),
        )

        assertFailsWith<IllegalStateException> {
            GenUiTool().execute(buildJsonObject { put("content", JsonPrimitive("x")) }, ctx)
        }
    }

    @Test
    fun `内容与展示意图一起交给生成模型`() = runTest {
        val (ctx, llm) = context(cardJson)

        GenUiTool().execute(
            buildJsonObject {
                put("content", JsonPrimitive("北京今天晴"))
                put("intent", JsonPrimitive("做成待办清单"))
            },
            ctx,
        )

        val request = llm.recordedRequests.single()
        val system = request.messages.filterIsInstance<ChatMessage.System>().single().content
        // 系统提示给出输出契约与组件词汇
        assertTrue(system.contains("root"))
        assertTrue(system.contains("Card"))
        val user = request.messages.filterIsInstance<ChatMessage.User>().single()
            .parts.filterIsInstance<io.github.yeyi.agent.llm.ContentPart.Text>().single().text
        assertTrue(user.contains("北京今天晴"))
        assertTrue(user.contains("做成待办清单"))
    }

    @Test
    fun `入参 schema 声明内容必传 展示意图可选`() = runTest {
        val schema = GenUiTool().parametersSchema
        assertTrue(schema is ToolParameters.JsonSchema)
        val obj = Json.parseToJsonElement((schema as ToolParameters.JsonSchema).schema).jsonObject
        val properties = obj["properties"]!!.jsonObject
        assertTrue("content" in properties)
        assertTrue("intent" in properties)
        val required = (obj["required"] as? kotlinx.serialization.json.JsonArray).orEmpty()
            .map { it.jsonPrimitive.content }
        assertTrue("content" in required)
        assertFalse("intent" in required)
    }

    @Test
    fun `注入的组件词汇表进入系统提示`() = runTest {
        val (ctx, llm) = context(cardJson)

        GenUiTool(componentSpec = "可用组件：- OnlyOne：只有这一个组件")
            .execute(buildJsonObject { put("content", JsonPrimitive("x")) }, ctx)

        val system = llm.recordedRequests.single()
            .messages.filterIsInstance<ChatMessage.System>().single().content
        assertTrue(system.contains("OnlyOne"))
        assertFalse(system.contains("Badge"))
    }
}
