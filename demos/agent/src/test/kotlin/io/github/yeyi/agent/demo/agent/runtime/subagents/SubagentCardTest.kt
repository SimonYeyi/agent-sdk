package io.github.yeyi.agent.demo.agent.runtime.subagents

import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.demo.agent.runtime.tools.GetLocationTool
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.genui.tool.decodeCard
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.subagent.SubagentContext
import io.github.yeyi.agent.subagent.SubagentTask
import io.github.yeyi.agent.subagent.subagent
import io.github.yeyi.agent.tool.ToolExecutionContext
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * 子 agent 的产卡去向：子 agent 继承父 agent 的工具实例，产卡工具连同结果 metadata 一起继承——
 * 子 agent 产出的卡片编码进工具结果 metadata，实时路径从 ToolCallEnd.result.decodeCard 读取，
 * 恢复路径从 MemoryEntry.decodeCard 读取，无独立旁路通道。
 */
class SubagentCardTest {

    @Test
    fun `子 agent 继承父 agent 的产卡工具 卡片可经结果 metadata 读取`() = runBlocking {
        val locationTool = GetLocationTool()

        val llm = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(
                        toolCalls = listOf(
                            ToolCall(
                                id = "call_1",
                                name = "get_location",
                                arguments = buildJsonObject {
                                    put("show_ui", JsonPrimitive(true))
                                },
                            )
                        )
                    ),
                    finishReason = FinishReason.Stop,
                ),
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop),
            )
        )
        // 父 agent 上下文：产卡工具随父 agent 构造，卡片编码进工具结果 metadata
        val parentContext = testAgentContext(
            persona = AgentPersona("main"),
            maxIterations = 5,
            llmProvider = llm,
            tools = listOf(locationTool),
        )
        val sub = subagent("locator", "查位置", instruction = "调用 get_location 查当前位置")

        // 产卡工具出卡即终结：子 agent 的回合以空正文收尾，委派结果为空内容错误。
        // 继承来的产卡工具在子 agent 上下文中正常出卡终结，无通道绑定也不影响链路。
        val failure = assertFailsWith<IllegalStateException> {
            sub.activate(SubagentTask("查当前位置"), SubagentContext(parentContext))
        }
        assertTrue(failure.message!!.contains("returned empty content"))

        // 卡片编码进工具结果 metadata：消费方经 ToolExecutionResult.decodeCard 读取渲染
        val executed = locationTool.execute(
            buildJsonObject { put("show_ui", JsonPrimitive(true)) },
            ToolExecutionContext(toolCallId = "call_1", agentContext = parentContext),
        )
        assertEquals("Location", executed.decodeCard()!!.root.component)
    }
}
