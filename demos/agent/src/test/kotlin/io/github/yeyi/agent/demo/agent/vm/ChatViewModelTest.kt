package io.github.yeyi.agent.demo.agent.vm

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ChatResponseEvent
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for [ChatViewModel] using [FakeLlmProvider] (via the public `agent { }` DSL).
 *
 * ChatViewModel 在 STREAM 模式调用 Streamable.runStream,所以 fake 必须通过
 * [FakeLlmProvider.streamScripts] (ChatResponseEvent list) 编排,而非 nonStreamResponses.
 */
class ChatViewModelTest {
    // 不替换 Main：用例用 runBlocking + 真实等待（agent 执行体在 Dispatchers.Default），
    // 真机上 viewModelScope 跑 Main.immediate，与真实单线程 Main 同型。

    /**
     * 用真实时间等 VM 的异步结果：架构 2 下 agent 执行体跑在自己的 `Dispatchers.Default` scope，
     * `runTest` 的虚拟时间推不动它（advanceUntilIdle 只排干 Main 上的 viewModelScope）。
     * 断言必须落在测试协程里，不能放进 launch —— join 不传播失败。
     */
    private suspend fun <T> awaitValue(timeoutMs: Long = 5_000, block: () -> T?): T =
        withTimeout(timeoutMs) {
            var value = block()
            while (value == null) {
                kotlinx.coroutines.delay(10)
                value = block()
            }
            value
        }

    @Test
    fun `sendUserInput appends user message and assistant reply`(): Unit = runBlocking {
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    ChatResponseEvent.ContentDelta("hello "),
                    ChatResponseEvent.ContentDelta("back"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop),
                )
            )
        )
        val agent: Agent = agent {
            persona(AgentPersona("you are helpful"))
            llmProvider(provider)
            maxIterations(5)
        }

        val vm = ChatViewModel(agentFactory = { memory -> agent })

        vm.sendUserInput("hi")
        val messages = awaitValue { vm.messages.value.takeIf { it.size >= 2 } }

        assertTrue(messages[0] is UiMessage.User, "messages[0] should be User, got ${messages[0]::class.simpleName}")
        assertTrue(messages[1] is UiMessage.Assistant, "messages[1] should be Assistant, got ${messages[1]::class.simpleName}")
        assertEquals("hi", (messages[0] as UiMessage.User).text)
        assertEquals("hello back", (messages[1] as UiMessage.Assistant).text)
        awaitValue { Unit.takeIf { !vm.isProcessing.value } }
    }

    @Test
    fun `sendUserInput uses batch mode when RunMode is BATCH`(): Unit = runBlocking {
        // FakeLlmProvider �?nonStreamResponses (BATCH 路径�?chat())
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "batch-reply"), finishReason = FinishReason.Stop)
            )
        )
        val agent: Agent = agent {
            persona(AgentPersona("you are helpful"))
            llmProvider(provider)
            maxIterations(5)
        }
        val vm = ChatViewModel(agentFactory = { memory -> agent })
        vm.setMode(RunMode.BATCH)
        vm.sendUserInput("hi")
        val messages = awaitValue { vm.messages.value.takeIf { it.size >= 2 } }
        // BATCH 路径走 chat():通过 recordedRequests 验证
        assertEquals(1, provider.recordedRequests.size, "BATCH mode should call chat() exactly once")
        // 两种 mode 都应渲染同样的消息结构 [User, Assistant]
        assertTrue(messages[0] is UiMessage.User)
        assertTrue(messages[1] is UiMessage.Assistant)
        assertEquals("hi", (messages[0] as UiMessage.User).text)
        assertEquals("batch-reply", (messages[1] as UiMessage.Assistant).text)
        awaitValue { Unit.takeIf { !vm.isProcessing.value } }
    }

    @Test
    fun `mode toggle does not affect UI logic`(): Unit = runBlocking {
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    ChatResponseEvent.ContentDelta("streamed"),
                    ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            ),
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "streamed"), finishReason = FinishReason.Stop)
            )
        )
        val agent: Agent = agent {
            persona(AgentPersona("you are helpful"))
            llmProvider(provider)
            maxIterations(5)
        }
        val vm = ChatViewModel(agentFactory = { memory -> agent })
        vm.setMode(RunMode.STREAM)
        vm.sendUserInput("hi")
        awaitValue { vm.messages.value.takeIf { it.size >= 2 } }
        awaitValue { Unit.takeIf { !vm.isProcessing.value } }
        val streamMessages = vm.messages.value.map { it::class.simpleName }
        val streamTexts = vm.messages.value.map { (it as? UiMessage.Assistant)?.text }

        // 各 VM 用各自的 agent：同一个 agent 不能并发跑两轮
        val vm2 = ChatViewModel(agentFactory = {
            agent {
                persona(AgentPersona("you are helpful"))
                llmProvider(provider)
                maxIterations(5)
            }
        })
        vm2.setMode(RunMode.BATCH)
        vm2.sendUserInput("hi")
        awaitValue { vm2.messages.value.takeIf { it.size >= 2 } }
        awaitValue { Unit.takeIf { !vm2.isProcessing.value } }
        val batchMessages = vm2.messages.value.map { it::class.simpleName }
        val batchTexts = vm2.messages.value.map { (it as? UiMessage.Assistant)?.text }

        // 模式切换不改变 UI 消息结构与文本
        assertEquals(streamMessages, batchMessages, "STREAM and BATCH should produce same message shapes")
        assertEquals(streamTexts, batchTexts, "STREAM and BATCH should produce same final text")
    }
}
