package io.github.yeyi.agent.demo.agent.vm

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ChatResponseEvent
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicInteger
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

        val vm = ChatViewModel(agentFactory = { _, _ -> agent })

        vm.submit("hi")
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
        val vm = ChatViewModel(agentFactory = { _, _ -> agent })
        vm.setMode(RunMode.BATCH)
        vm.submit("hi")
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
        val vm = ChatViewModel(agentFactory = { _, _ -> agent })
        vm.setMode(RunMode.STREAM)
        vm.submit("hi")
        awaitValue { vm.messages.value.takeIf { it.size >= 2 } }
        awaitValue { Unit.takeIf { !vm.isProcessing.value } }
        val streamMessages = vm.messages.value.map { it::class.simpleName }
        val streamTexts = vm.messages.value.map { (it as? UiMessage.Assistant)?.text }

        // 各 VM 用各自的 agent：同一个 agent 不能并发跑两轮
        val vm2 = ChatViewModel(agentFactory = { _, _ ->
            agent {
                persona(AgentPersona("you are helpful"))
                llmProvider(provider)
                maxIterations(5)
            }
        })
        vm2.setMode(RunMode.BATCH)
        vm2.submit("hi")
        awaitValue { vm2.messages.value.takeIf { it.size >= 2 } }
        awaitValue { Unit.takeIf { !vm2.isProcessing.value } }
        val batchMessages = vm2.messages.value.map { it::class.simpleName }
        val batchTexts = vm2.messages.value.map { (it as? UiMessage.Assistant)?.text }

        // 模式切换不改变 UI 消息结构与文本
        assertEquals(streamMessages, batchMessages, "STREAM and BATCH should produce same message shapes")
        assertEquals(streamTexts, batchTexts, "STREAM and BATCH should produce same final text")
    }

    /**
     * steer 投递不到 = 没有活跃轮次（core 的契约：先还槽、后发终局事件），
     * 于是直接开新轮，不等上一轮收尾。
     */
    @Test
    fun `steer 投递不到时直接开新轮`(): Unit = runBlocking {
        val fake = FakeSteerAgent()
        val vm = ChatViewModel(agentFactory = { _, _ -> fake })

        vm.submit("hi")
        awaitValue { fake.channels.size.takeIf { it == 1 } }
        assertEquals(1, fake.runs.get(), "steer 投递不到应直接开新轮")

        vm.submit("再来一句")
        awaitValue { fake.channels.size.takeIf { it == 2 } }
        assertEquals(2, fake.runs.get(), "steer 投递不到应直接开新轮")
    }

    /**
     * 进行中状态由事件流驱动：[AgentEvent.Initial] 置起，轮次收尾归位。
     */
    @Test
    fun `进行中状态由 Initial 事件与轮次收尾驱动`(): Unit = runBlocking {
        val fake = FakeSteerAgent()
        val vm = ChatViewModel(agentFactory = { _, _ -> fake })

        vm.submit("hi")
        awaitValue { fake.channels.size.takeIf { it == 1 } }
        assertTrue(!vm.isProcessing.value, "还没收到 Initial 事件，不应是进行中")

        fake.channels[0].trySend(AgentEvent.Initial(AgentQuery.text("hi")))
        awaitValue { Unit.takeIf { vm.isProcessing.value } }
        assertTrue(vm.isProcessing.value, "Initial 事件后应是进行中")

        fake.channels[0].close()
        awaitValue { Unit.takeIf { !vm.isProcessing.value } }
        assertTrue(!vm.isProcessing.value, "轮次收尾后进行中状态应归位")
    }

    /**
     * steer 投递得到时不另开新轮：输入注入当前轮。
     */
    @Test
    fun `steer 投递得到时不另开新轮`(): Unit = runBlocking {
        val fake = FakeSteerAgent(steerable = true)
        val vm = ChatViewModel(agentFactory = { _, _ -> fake })

        vm.submit("hi")
        kotlinx.coroutines.delay(200)
        assertEquals(0, fake.runs.get(), "steer 投递得到时不应开新轮")
    }

    /**
     * 没有活跃轮次时 submit 直接开新轮（steer 投递不到 → 降级）。
     */
    @Test
    fun `submit 在无活跃轮次时开新轮`(): Unit = runBlocking {
        val fake = FakeSteerAgent()
        val vm = ChatViewModel(agentFactory = { _, _ -> fake })

        vm.submit("hi")
        awaitValue { Unit.takeIf { fake.runs.get() == 1 } }
        assertEquals(1, fake.runs.get(), "无活跃轮次时应开新轮")
    }

    /** steer 可控的假 agent：轮次由测试用 channel 控制何时收尾、发出什么事件。 */
    private class FakeSteerAgent(private val steerable: Boolean = false) : Agent, Agent.Steerable {
        val runs = AtomicInteger()
        val channels = mutableListOf<Channel<AgentEvent>>()

        override fun run(query: AgentQuery): Flow<AgentEvent> {
            runs.incrementAndGet()
            val channel = Channel<AgentEvent>(Channel.UNLIMITED)
            channels += channel
            return channel.receiveAsFlow()
        }

        override fun steer(query: AgentQuery): Boolean = steerable
    }
}
