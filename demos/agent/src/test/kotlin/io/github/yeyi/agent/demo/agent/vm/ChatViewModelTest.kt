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
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.onCompletion
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
     * steer 注入的指令有两个时刻：**签收**时（steer 返回 true）给一条瞬时提示，
     * **入卷**时（被消费、写入记忆）才渲染成用户气泡 —— 渲染时刻与记忆里的
     * 事实一致：指令进对话才渲染，不会先渲染、后落记忆。
     */
    @Test
    fun `steer 注入的指令入卷时出现在会话里`(): Unit = runBlocking {
        val fake = FakeSteerAgent(steerable = true, steerNeedsActiveRound = true)
        val vm = ChatViewModel(agentFactory = { _, _ -> fake })

        vm.submit("hi") // 无活跃轮次 → 开新轮
        awaitValue { fake.channels.size.takeIf { it == 1 } }

        vm.submit("改成红色") // 有活跃轮次 → steer 签收（此刻还没进对话）
        assertEquals(1, fake.runs.get(), "steer 投递得到时不应开新轮")
        assertTrue(
            vm.messages.value.none { it is UiMessage.User && it.text == "改成红色" },
            "签收时指令还没进对话，不该渲染"
        )
        assertTrue(vm.steerHint.value != null, "签收后应有提示：指令已注入当前轮次")

        fake.consumeSteering() // 消费 → 入卷 → 事件流发出 Steering
        val messages = awaitValue {
            vm.messages.value.takeIf { list -> list.any { it is UiMessage.User && it.text == "改成红色" } }
        }
        assertTrue(
            messages.any { it is UiMessage.User && it.text == "改成红色" },
            "入卷的指令应出现在会话里：${messages.map { it::class.simpleName }}"
        )
        awaitValue { Unit.takeIf { vm.steerHint.value == null } }
        assertTrue(vm.steerHint.value == null, "入卷后提示应归位")
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

    /**
     * 回归：旧轮的收尾不得抹掉新会话的状态。
     *
     * clearMessages 换掉 agent 后，旧轮的收集协程仍活到旧轮终局为止 ——
     * 它的收尾（finally）落在新会话的状态上：进行中标记被置回 false、
     * 流式气泡被清、工具标记被清。故收尾要与事件同守一道会话守卫。
     */
    @Test
    fun `旧轮收尾不得抹掉新会话的状态`(): Unit = runBlocking {
        // 每次建 agent 都给一个新实例 —— 会话身份靠 agent 实例区分
        val agents = mutableListOf<FakeSteerAgent>()
        val vm = ChatViewModel(agentFactory = { _, _ ->
            FakeSteerAgent().also { agents += it }
        })

        // 第一轮：进行中
        vm.submit("旧会话")
        awaitValue { agents.size.takeIf { it == 1 } }
        agents[0].channels[0].trySend(AgentEvent.Initial(AgentQuery.text("旧会话")))
        awaitValue { Unit.takeIf { vm.isProcessing.value } }

        // 换会话：旧轮仍在跑（它的 channel 未关，收尾未发生）
        vm.clearMessages()
        assertTrue(!vm.isProcessing.value, "新会话应从非进行中状态开始")

        // 新会话的第一轮：进行中，且有流式气泡
        vm.submit("新会话")
        awaitValue { agents.size.takeIf { it == 2 } }
        agents[1].channels[0].trySend(AgentEvent.Initial(AgentQuery.text("新会话")))
        awaitValue { Unit.takeIf { vm.isProcessing.value } }
        agents[1].channels[0].trySend(AgentEvent.TextDelta("新会话的回复"))
        awaitValue { Unit.takeIf { vm.liveBubble.value != null } }

        // 旧轮此刻才收尾 —— 不得影响新会话的状态
        agents[0].channels[0].close()
        agents[0].completions[0].await()
        kotlinx.coroutines.delay(200) // 让旧轮收集协程的收尾跑完

        assertTrue(vm.isProcessing.value, "旧轮收尾不得把新会话的进行中状态置回 false")
        assertTrue(vm.liveBubble.value != null, "旧轮收尾不得清掉新会话的流式气泡")
    }

    /** steer 可控的假 agent：轮次由测试用 channel 控制何时收尾、发出什么事件。 */
    private class FakeSteerAgent(
        private val steerable: Boolean = false,
        /** 只在有活跃轮次时才投递（对齐 core 的"无活跃轮次 ⟹ 返回 false"）。 */
        private val steerNeedsActiveRound: Boolean = false,
    ) : Agent, Agent.Steerable {
        val runs = AtomicInteger()
        val channels = mutableListOf<Channel<AgentEvent>>()
        /** 每轮流结束的时刻 —— 测试据此知道旧轮已收尾。 */
        val completions = mutableListOf<CompletableDeferred<Unit>>()
        /** 已签收但尚未消费的指令 —— 等价于 core 的信箱。 */
        val pendingSteerings = mutableListOf<AgentQuery>()

        override fun run(query: AgentQuery): Flow<AgentEvent> {
            runs.incrementAndGet()
            val channel = Channel<AgentEvent>(Channel.UNLIMITED)
            val done = CompletableDeferred<Unit>()
            channels += channel
            completions += done
            return channel.receiveAsFlow().onCompletion { done.complete(Unit) }
        }

        /**
         * 对齐 core：签收只返回值（指令进信箱），入卷才发事件
         * （指令被消费、写入记忆的那一刻）。
         */
        override fun steer(query: AgentQuery): Boolean {
            if (!steerable) return false
            if (steerNeedsActiveRound && channels.isEmpty()) return false
            pendingSteerings += query
            return true
        }

        /** 模拟 core 的消费：指令入卷，该轮事件流发出 [AgentEvent.Steering]。 */
        fun consumeSteering() {
            pendingSteerings.forEach { channels.lastOrNull()?.trySend(AgentEvent.Steering(it)) }
            pendingSteerings.clear()
        }
    }
}
