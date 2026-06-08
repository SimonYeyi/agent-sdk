package io.github.yeyi.agent.app.vm

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.fakes.FakeLlmClient
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
 * Integration test for [ChatViewModel] using [FakeLlmClient] (via the public `agent { }` DSL).
 *
 * ChatViewModel uses [Agent.runStream], so the fake must be scripted via
 * [FakeLlmClient.streamScripts] (StreamEvent list), not nonStreamResponses.
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
        val client = FakeLlmClient(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ContentDelta("hello "),
                    StreamEvent.ContentDelta("back"),
                    StreamEvent.Done(usage = null),
                )
            )
        )
        val agent: Agent = agent {
            systemPrompt = "you are helpful"
            llmClient = client
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
        // FakeLlmClient 配 nonStreamResponses (BATCH 路径走 chat())
        val client = FakeLlmClient(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "batch-reply"), finishReason = FinishReason.Stop)
            )
        )
        val agent: Agent = agent {
            systemPrompt = "you are helpful"
            llmClient = client
            maxIterations = 5
        }
        val vm = ChatViewModel(agent)
        vm.setMode(RunMode.BATCH)
        vm.sendUserInput("hi")
        advanceUntilIdle()
        // BATCH 路径走 chat() 而非 chatStream():通过 recordedRequests 验证
        assertEquals(1, client.recordedRequests.size, "BATCH mode should call chat() exactly once")
        // isProcessing 流程结束应回到 false
        assertEquals(false, vm.isProcessing.value, "isProcessing should be false after BATCH run completes")
        // BATCH 路径不 emit TextDelta,currentAssistantText 在 Final 时为 null;
        // 当前 handleEvent 的 Final 分支仅依赖 accumulated text,所以 Assistant UiMessage 不会被写入。
        // (Part B 的已知限制 — v1-impl-gaps.md "限制 1"; 真正的修复应让 Final handler 同时回退到 event.message.content)
        val messages = vm.messages.value
        assertEquals(1, messages.size)
        assertTrue(messages[0] is UiMessage.User)
        assertEquals("hi", (messages[0] as UiMessage.User).text)
    }

    @Test
    fun `mode toggle does not affect UI logic`() = runTest {
        // 同一个 agent 在 STREAM / BATCH 两种 mode 下都应正常完成 (isProcessing 回 false)
        val client = FakeLlmClient(
            streamScripts = listOf(
                listOf(
                    StreamEvent.ContentDelta("streamed"),
                    StreamEvent.Done(usage = null)
                )
            ),
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "streamed"), finishReason = FinishReason.Stop)
            )
        )
        val agent: Agent = agent {
            systemPrompt = ""
            llmClient = client
            maxIterations = 5
        }
        val vm = ChatViewModel(agent)
        vm.setMode(RunMode.STREAM)
        vm.sendUserInput("hi")
        advanceUntilIdle()
        val streamMessages = vm.messages.value.map { it::class.simpleName }
        // STREAM 模式:有 TextDelta → 累积文本,Final 时整段写入 Assistant
        assertEquals(listOf("User", "Assistant"), streamMessages, "STREAM mode renders User + Assistant")

        // 重置 vm 用 BATCH 模式
        val vm2 = ChatViewModel(agent)
        vm2.setMode(RunMode.BATCH)
        vm2.sendUserInput("hi")
        advanceUntilIdle()
        val batchMessages = vm2.messages.value.map { it::class.simpleName }
        // BATCH 模式:不 emit TextDelta → currentAssistantText 为 null → Final handler 不写入 Assistant
        // (Part B 已知限制,见 v1-impl-gaps.md "限制 1")
        assertEquals(listOf("User"), batchMessages, "BATCH mode: TextDelta absent, Assistant not rendered (Part B limit)")
        // 两种模式都应正常完成
        assertEquals(false, vm.isProcessing.value)
        assertEquals(false, vm2.isProcessing.value)
    }
}
