package io.github.yeyi.agent.app.vm

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.StreamEvent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Integration test for [ChatViewModel] using [FakeLlmProvider] (via the public `agent { }` DSL).
 *
 * ChatViewModel uses [Agent.runStream], so the fake must be scripted via
 * [FakeLlmProvider.streamScripts] (StreamEvent list), not nonStreamResponses.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ChatViewModelTest {

    @BeforeTest
    fun setUp() {
        // viewModelScope launches on Dispatchers.Main; provide a TestDispatcher
        // so coroutines advance deterministically with advanceUntilIdle().
        Dispatchers.setMain(StandardTestDispatcher())
    }

    @AfterTest
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `sendUserInput appends user message and assistant reply`() = runTest {
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ContentDelta("hello "),
                    StreamEvent.ContentDelta("back"),
                    StreamEvent.Done(usage = null, finishReason = FinishReason.Stop),
                )
            )
        )
        val agent: Agent = agent {
            systemPrompt = "you are helpful"
            llmProvider = provider
            maxIterations = 5
        }
        val vm = ChatViewModel(agent)

        vm.sendUserInput("hi")
        advanceUntilIdle()

        val messages = vm.messages.value
        assertEquals(2, messages.size, "expected [User, Assistant], got $messages")
        assertTrue(messages[0] is UiMessage.User, "messages[0] should be User, got ${messages[0]::class.simpleName}")
        assertTrue(messages[1] is UiMessage.Assistant, "messages[1] should be Assistant, got ${messages[1]::class.simpleName}")
        assertEquals("hi", (messages[0] as UiMessage.User).text)
        assertEquals("hello back", (messages[1] as UiMessage.Assistant).text)
        assertEquals(false, vm.isProcessing.value, "isProcessing should be false after run completes")
    }

    @Test
    fun `sendUserInput uses batch mode when RunMode is BATCH`() = runTest {
        // FakeLlmProvider �?nonStreamResponses (BATCH 路径�?chat())
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "batch-reply"), finishReason = FinishReason.Stop)
            )
        )
        val agent: Agent = agent {
            systemPrompt = "you are helpful"
            llmProvider = provider
            maxIterations = 5
        }
        val vm = ChatViewModel(agent)
        vm.setMode(RunMode.BATCH)
        vm.sendUserInput("hi")
        advanceUntilIdle()
        // BATCH 路径�?chat():通过 recordedRequests 验证
        assertEquals(1, provider.recordedRequests.size, "BATCH mode should call chat() exactly once")
        // 两种 mode 都应渲染同样的消息结�?[User, Assistant]
        val messages = vm.messages.value
        assertEquals(2, messages.size, "expected [User, Assistant], got $messages")
        assertTrue(messages[0] is UiMessage.User)
        assertTrue(messages[1] is UiMessage.Assistant)
        assertEquals("hi", (messages[0] as UiMessage.User).text)
        assertEquals("batch-reply", (messages[1] as UiMessage.Assistant).text)
        assertEquals(false, vm.isProcessing.value)
    }

    @Test
    fun `mode toggle does not affect UI logic`() = runTest {
        val provider = FakeLlmProvider(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ContentDelta("streamed"),
                    StreamEvent.Done(usage = null, finishReason = FinishReason.Stop)
                )
            ),
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "streamed"), finishReason = FinishReason.Stop)
            )
        )
        val agent: Agent = agent {
            systemPrompt = ""
            llmProvider = provider
            maxIterations = 5
        }
        val vm = ChatViewModel(agent)
        vm.setMode(RunMode.STREAM)
        vm.sendUserInput("hi")
        advanceUntilIdle()
        val streamMessages = vm.messages.value.map { it::class.simpleName }
        val streamTexts = vm.messages.value.map { (it as? UiMessage.Assistant)?.text }

        val vm2 = ChatViewModel(agent)
        vm2.setMode(RunMode.BATCH)
        vm2.sendUserInput("hi")
        advanceUntilIdle()
        val batchMessages = vm2.messages.value.map { it::class.simpleName }
        val batchTexts = vm2.messages.value.map { (it as? UiMessage.Assistant)?.text }

        // 模式切换不改�?UI 消息结构与文�?        assertEquals(streamMessages, batchMessages, "STREAM and BATCH should produce same message shapes")
        assertEquals(streamTexts, batchTexts, "STREAM and BATCH should produce same final text")
        assertEquals(false, vm.isProcessing.value)
        assertEquals(false, vm2.isProcessing.value)
    }
}
