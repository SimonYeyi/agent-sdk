package io.github.yeyi.agent.demo.agent.runtime.subagents

import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.demo.agent.runtime.tools.GetLocationTool
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.fakes.testAgentContext
import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.tool.UiSink
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.subagent.SubagentContext
import io.github.yeyi.agent.subagent.SubagentTask
import io.github.yeyi.agent.subagent.subagent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.buildJsonObject
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * 子 agent 的产卡去向：子 agent 继承父 agent 的工具实例，产卡工具连同卡片通道一起继承——
 * 子 agent 的卡片落在父 agent 的通道上。
 *
 * 这条路径在「按 agentId 查全局路由表」时代是坏的：子 agent 不设 id（走默认 UUID），
 * 查表必然落空，产卡工具 fail-fast。
 */
class SubagentCardTest {

    @Test
    fun `子 agent 产卡落在父 agent 的通道`() = runBlocking {
        val sink = UiSink()
        val received = mutableListOf<CardIr>()
        val scope = CoroutineScope(Dispatchers.Default)
        val job = scope.launch { sink.cards.take(1).collect { received += it } }

        val llm = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(
                    ChatMessage.Assistant(
                        toolCalls = listOf(
                            ToolCall(
                                id = "call_1",
                                name = "get_location",
                                arguments = buildJsonObject {},
                            )
                        )
                    ),
                    finishReason = FinishReason.Stop,
                ),
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop),
            )
        )
        // 父 agent 上下文：产卡工具绑父 agent 的卡片通道
        val parentContext = testAgentContext(
            persona = AgentPersona("main"),
            maxIterations = 5,
            llmProvider = llm,
            tools = listOf(GetLocationTool(sink)),
        )
        val sub = subagent("locator", "查位置", instruction = "调用 get_location 查当前位置")

        sub.activate(SubagentTask("查当前位置"), SubagentContext(parentContext))

        // 子 agent 的回合跑在自己的 dispatcher 上，等卡片真正落地
        var waited = 0L
        while (received.isEmpty() && waited < 3_000) {
            delay(20)
            waited += 20
        }
        job.cancel()

        assertEquals(1, received.size, "子 agent 的卡片应落在父 agent 的通道上")
        assertEquals("Location", received.single().root.component)
    }
}
