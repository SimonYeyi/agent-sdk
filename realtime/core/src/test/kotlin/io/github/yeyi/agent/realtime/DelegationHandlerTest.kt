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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class DelegationHandlerTest {

    private class FakeDelegation(
        override val capabilities: List<String>,
        override val classifier: IntentionClassifier? = null,
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

    private fun newHandler(
        delegation: FakeDelegation,
        scope: CoroutineScope,
        onReplacementAck: suspend (String) -> Unit = {},
        onReply: suspend (String) -> Unit = {},
    ) = DelegationHandler(
        delegation = delegation,
        scopeProvider = { scope },
        onReply = onReply,
        onReplacementAck = onReplacementAck,
    )

    @Test
    fun `appendInstructions with capabilities appends protocol`() = runTest {
        val delegation = FakeDelegation(capabilities = listOf("灯光控制", "空调控制"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope)

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
        val handler = newHandler(delegation, scope)

        val result = handler.appendInstructions("你是助手")

        assertEquals(true, result.startsWith("你是助手"))
        assertEquals(true, result.contains("委派协议"))
        scope.cancel()
    }

    @Test
    fun `handle with UserTranscriptCompleted sets pendingAsr`() = runTest {
        val delegation = FakeDelegation(capabilities = emptyList())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope)

        val result = handler.handle(RealtimeEvent.UserTranscriptCompleted("帮我开灯"))

        assertEquals(RealtimeEvent.UserTranscriptCompleted("帮我开灯"), result)
        scope.cancel()
    }

    @Test
    fun `handle with marker text triggers delegation and strips marker`() = runTest {
        val delegation = FakeDelegation(capabilities = listOf("灯光控制"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope)

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
        val handler = newHandler(delegation, scope, onReply = { text -> received.add(text) })

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

    @Test
    fun `classifier delegable triggers onReplacementAck and runDelegation`() = runTest {
        val task = "打开空调"
        val ack = "好的"
        val delegation = FakeDelegation(
            capabilities = emptyList(),
            classifier = object : IntentionClassifier {
                override suspend fun classify(asr: String) = Intention.Delegated(ack, task)
            },
        )
        var replacementAckCalledWith: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope, onReplacementAck = { replacementAckCalledWith = it })

        handler.handle(RealtimeEvent.UserTranscriptCompleted("帮我开空调"))

        assertEquals(ack, replacementAckCalledWith)
        assertEquals(task, withTimeout(5_000) { delegation.dispatched.receive() })
        scope.cancel()
    }

    @Test
    fun `classifier casual with ack triggers onReplacementAck only`() = runTest {
        val ack = "好的"
        val delegation = FakeDelegation(
            capabilities = emptyList(),
            classifier = object : IntentionClassifier {
                override suspend fun classify(asr: String) = Intention.Casual(ack)
            },
        )
        var replacementAckCalledWith: String? = null
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope, onReplacementAck = { replacementAckCalledWith = it })

        handler.handle(RealtimeEvent.UserTranscriptCompleted("你好"))

        assertEquals(ack, replacementAckCalledWith)
        scope.cancel()
    }

    @Test
    fun `classifier casual with null ack does not trigger onReplacementAck`() = runTest {
        val delegation = FakeDelegation(
            capabilities = emptyList(),
            classifier = object : IntentionClassifier {
                override suspend fun classify(asr: String) = Intention.Casual(null)
            },
        )
        var replacementAckCalled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope, onReplacementAck = { replacementAckCalled = true })

        handler.handle(RealtimeEvent.UserTranscriptCompleted("你好"))

        assertFalse(replacementAckCalled)
        scope.cancel()
    }

    @Test
    fun `classifier exception is caught and treated as Casual null`() = runTest {
        val delegation = FakeDelegation(
            capabilities = emptyList(),
            classifier = object : IntentionClassifier {
                override suspend fun classify(asr: String): Intention {
                    throw RuntimeException("classify failed")
                }
            },
        )
        var replacementAckCalled = false
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope, onReplacementAck = { replacementAckCalled = true })

        // should not throw
        handler.handle(RealtimeEvent.UserTranscriptCompleted("你好"))

        assertFalse(replacementAckCalled)
        scope.cancel()
    }

    @Test
    fun `classifier with ack suppresses all assistant event types`() = runTest {
        val delegation = FakeDelegation(
            capabilities = emptyList(),
            classifier = object : IntentionClassifier {
                override suspend fun classify(asr: String) = Intention.Delegated("好的", "task")
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope)

        handler.handle(RealtimeEvent.UserTranscriptCompleted("开空调"))

        assertNull(handler.handle(RealtimeEvent.AssistantTextDelta("hi")))
        assertNull(handler.handle(RealtimeEvent.AssistantAudioStarted))
        assertNull(handler.handle(RealtimeEvent.AssistantAudioDelta(byteArrayOf(1))))
        assertNull(handler.handle(RealtimeEvent.AssistantAudioDone))
        assertNull(handler.handle(RealtimeEvent.ResponseDone))
        assertNull(handler.handle(RealtimeEvent.ResponseCanceled))
        scope.cancel()
    }

    @Test
    fun `UserTranscriptStarted resets suppression state`() = runTest {
        val delegation = FakeDelegation(
            capabilities = emptyList(),
            classifier = object : IntentionClassifier {
                override suspend fun classify(asr: String) = Intention.Delegated("好的", "task")
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope)

        handler.handle(RealtimeEvent.UserTranscriptCompleted("开空调"))
        assertNull(handler.handle(RealtimeEvent.AssistantTextDelta("hi")))

        handler.handle(RealtimeEvent.UserTranscriptStarted("test"))

        val notSuppressed = handler.handle(RealtimeEvent.AssistantTextDelta("hello"))
        assertNotNull(notSuppressed)
        scope.cancel()
    }

    @Test
    fun `classifier null falls back to marker path`() = runTest {
        val delegation = FakeDelegation(capabilities = listOf("灯光控制"))
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope)

        handler.handle(RealtimeEvent.UserTranscriptCompleted("帮我开灯"))
        val result = handler.handle(RealtimeEvent.AssistantTextDelta("|好的，正在开灯"))

        assertEquals(RealtimeEvent.AssistantTextDelta("好的，正在开灯"), result)
        scope.cancel()
    }

    @Test
    fun `classifier not null appendInstructions returns base unchanged`() = runTest {
        val delegation = FakeDelegation(
            capabilities = listOf("灯光控制"),
            classifier = object : IntentionClassifier {
                override suspend fun classify(asr: String) = Intention.Casual(null)
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope)

        val result = handler.appendInstructions("你是助手")

        assertEquals("你是助手", result)
        assertFalse(result.contains("委派协议"))
        scope.cancel()
    }

    @Test
    fun `Casual with ack also suppresses assistant events`() = runTest {
        val delegation = FakeDelegation(
            capabilities = emptyList(),
            classifier = object : IntentionClassifier {
                override suspend fun classify(asr: String) = Intention.Casual("好的")
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val handler = newHandler(delegation, scope)

        handler.handle(RealtimeEvent.UserTranscriptCompleted("你好"))

        assertNull(handler.handle(RealtimeEvent.AssistantTextDelta("hi")))
        assertNull(handler.handle(RealtimeEvent.AssistantAudioStarted))
        scope.cancel()
    }
}