@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package io.github.yeyi.agent.realtime

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.cancel
import kotlin.test.Test
import kotlin.test.assertEquals

class DelegationHandlerTest {

    private class FakeDelegation(
        override val capabilities: List<String>,
    ) : RealtimeDelegation {
        private val replyEmitter = MutableSharedFlow<DelegationReply>(extraBufferCapacity = 16)
        override val replies = replyEmitter.asSharedFlow()
        val dispatched = Channel<String>(Channel.UNLIMITED)

        override suspend fun run(task: String) {
            dispatched.send(task)
        }

        fun emit(reply: DelegationReply) {
            check(replyEmitter.tryEmit(reply))
        }
    }

    @Test
    fun `appendInstructions with capabilities appends protocol`() = runTest {
        val delegation = FakeDelegation(capabilities = listOf("灯光控制", "空调控制"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = DelegationHandler(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = {},
        )

        val result = handler.appendInstructions("你是助手")

        assertEquals(true, result.startsWith("你是助手"))
        assertEquals(true, result.contains("灯光控制"))
        assertEquals(true, result.contains("空调控制"))
        scope.cancel()
    }

    @Test
    fun `appendInstructions with empty capabilities returns base unchanged`() = runTest {
        val delegation = FakeDelegation(capabilities = emptyList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = DelegationHandler(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = {},
        )

        val result = handler.appendInstructions("你是助手")

        assertEquals(true, result.startsWith("你是助手"))
        assertEquals(true, result.contains("委派协议"))
        scope.cancel()
    }

    @Test
    fun `handle with UserTranscriptCompleted sets pendingAsr`() {
        val delegation = FakeDelegation(capabilities = emptyList())
        val handler = DelegationHandler(
            delegation = delegation,
            scopeProvider = { null },
            onReply = {},
        )

        val result = handler.handle(RealtimeEvent.UserTranscriptCompleted("帮我开灯"))

        assertEquals(RealtimeEvent.UserTranscriptCompleted("帮我开灯"), result)
    }

    @Test
    fun `handle with marker text triggers delegation and strips marker`() = runTest {
        val delegation = FakeDelegation(capabilities = listOf("灯光控制"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = DelegationHandler(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = {},
        )

        handler.handle(RealtimeEvent.UserTranscriptCompleted("帮我开灯"))
        val result = handler.handle(RealtimeEvent.AssistantTextDelta("|好的，正在开灯"))

        assertEquals(RealtimeEvent.AssistantTextDelta("好的，正在开灯"), result)
        val called = withTimeout(5_000) { delegation.dispatched.receive() }
        assertEquals("帮我开灯", called)
        scope.cancel()
    }

    @Test
    fun `start collects replies and invokes onReply`() = runTest {
        val delegation = FakeDelegation(capabilities = emptyList())
        val received = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = DelegationHandler(
            delegation = delegation,
            scopeProvider = { scope },
            onReply = { text -> received.add(text) },
        )

        handler.start()
        // Give the collection coroutine time to start before emitting
        delay(50)

        delegation.emit(DelegationReply.Confirmation("正在处理"))
        delegation.emit(DelegationReply.Success("完成"))
        delegation.emit(DelegationReply.Failure("参数错误"))

        delay(200)

        assertEquals(listOf("正在处理", "完成", "参数错误"), received)
        scope.cancel()
    }
}
