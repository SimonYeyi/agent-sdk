package io.github.yeyi.agent.app.vm

import io.github.yeyi.agent.core.agent.Agent
import io.github.yeyi.agent.core.agent.agent
import io.github.yeyi.agent.core.agent.fakes.FakeLlmClient
import io.github.yeyi.agent.core.llm.StreamEvent
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
}
