package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.tool.ToolExecutionResult
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoggingHookTest {

    private lateinit var originalErr: PrintStream
    private lateinit var captured: ByteArrayOutputStream

    @BeforeTest
    fun captureStderr() {
        originalErr = System.err
        captured = ByteArrayOutputStream()
        System.setErr(PrintStream(captured, true, Charsets.UTF_8))
    }

    @AfterTest
    fun restoreStderr() {
        System.setErr(originalErr)
    }

    private fun stderr(): String = captured.toString(Charsets.UTF_8)

    private fun emptyResponse() = ChatResponse(
        message = ChatMessage.Assistant(content = ""),
        finishReason = FinishReason.Stop
    )

    private fun toolCall() = ToolCall(
        id = "c1",
        name = "echo",
        arguments = JsonObject(mapOf("k" to JsonPrimitive("v")))
    )

    @Test
    fun `beforeLlmCall writes a warn line with iter and message count`() = runTest {
        val h = LoggingHook()
        h.beforeLlmCall(3, listOf(ChatMessage.User("hi"), ChatMessage.User("again")))
        val out = stderr()
        assertTrue(out.contains("hook"), "should tag with hook")
        assertTrue(out.contains("iter=3"))
        assertTrue(out.contains("messages=2"))
    }

    @Test
    fun `afterLlmResponse writes a warn line with iter and tool-call count`() = runTest {
        val h = LoggingHook()
        val r = ChatResponse(
            message = ChatMessage.Assistant(
                content = "hi",
                toolCalls = listOf(toolCall(), toolCall())
            ),
            finishReason = FinishReason.ToolCalls
        )
        h.afterLlmResponse(2, r)
        val out = stderr()
        assertTrue(out.contains("iter=2"))
        assertTrue(out.contains("toolCalls=2"))
    }

    @Test
    fun `beforeToolCall always returns null and logs id+name`() = runTest {
        val h = LoggingHook()
        val r = h.beforeToolCall(toolCall())
        assertNull(r, "LoggingHook must never short-circuit")
        val out = stderr()
        assertTrue(out.contains("id=c1"))
        assertTrue(out.contains("name=echo"))
    }

    @Test
    fun `afterToolCall returns input unchanged and logs duration and isError`() = runTest {
        val h = LoggingHook()
        val input = ToolExecutionResult("payload", isError = true)
        val out1 = h.afterToolCall(toolCall(), input, 42)
        assertSame(input, out1, "LoggingHook must not rewrite results")
        val log = stderr()
        assertTrue(log.contains("dur=42ms"))
        assertTrue(log.contains("isError=true"))
    }

    @Test
    fun `afterToolCall with isError=false logs isError=false`() = runTest {
        val h = LoggingHook()
        h.afterToolCall(toolCall(), ToolExecutionResult("ok", isError = false), 1)
        assertTrue(stderr().contains("isError=false"))
    }

    @Test
    fun `onError writes a warn line with class and message`() = runTest {
        val h = LoggingHook()
        h.onError(4, RuntimeException("boom"))
        val out = stderr()
        assertTrue(out.contains("iter=4"))
        assertTrue(out.contains("RuntimeException"))
        assertTrue(out.contains("boom"))
    }

    @Test
    fun `onRunFinished writes a warn line with iterations and tool-call count`() = runTest {
        val h = LoggingHook()
        val r = AgentResult(
            message = ChatMessage.Assistant(content = "done"),
            iterations = 5,
            toolCalls = listOf(
                AgentResult.ToolCallRecord(
                    callId = "c1", toolName = "echo",
                    arguments = JsonObject(emptyMap()),
                    result = ToolExecutionResult("ok"),
                    timestamp = java.time.Instant.now()
                )
            ),
            usage = null,
        )
        h.onRunFinished(r)
        val out = stderr()
        assertTrue(out.contains("iter=5"))
        assertTrue(out.contains("toolCalls=1"))
    }

    // --- Open subclass behavior ---

    private class CustomLoggingHook : LoggingHook() {
        var afterLlmResponseCalled: Int = 0
        override suspend fun afterLlmResponse(iteration: Int, response: ChatResponse) {
            // custom override: track that it was called, no stderr noise
            afterLlmResponseCalled = iteration
        }
    }

    @Test
    fun `subclass can override individual methods`() = runTest {
        val h = CustomLoggingHook()
        h.afterLlmResponse(7, emptyResponse())
        assertEquals(7, h.afterLlmResponseCalled, "subclass override should be invoked")
    }

    @Test
    fun `subclass inherits LoggingHook base methods`() = runTest {
        val h = CustomLoggingHook()
        h.beforeLlmCall(1, emptyList())
        // base method should still run for non-overridden hooks
        val out = captured.toString(Charsets.UTF_8)
        assertTrue(out.contains("hook"))
        assertTrue(out.contains("iter=1"))
    }

    // --- All methods are no-op semantically for the agent ---

    @Test
    fun `LoggingHook satisfies Hook interface (AgentHook-compatible)`() {
        // Compile-time check: LoggingHook is a Hook, and Hook extends AgentHook,
        // so this assignment would not type-check if either side were missing.
        val h: io.github.yeyi.agent.AgentHook = LoggingHook()
        assertEquals("LoggingHook", h::class.simpleName)
    }

    @Test
    fun `all LoggingHook callbacks produce exactly one warn line per call`() = runTest {
        val h = LoggingHook()
        h.beforeLlmCall(1, emptyList())
        h.afterLlmResponse(1, emptyResponse())
        h.beforeToolCall(toolCall())
        h.afterToolCall(toolCall(), ToolExecutionResult("x"), 1)
        h.onError(1, RuntimeException("e"))
        h.onRunFinished(
            AgentResult(
                message = ChatMessage.Assistant(content = "ok"),
                iterations = 1,
                toolCalls = emptyList(),
                usage = null,
            )
        )
        // 6 calls → 6 warn lines (each ends with \n)
        val lines = stderr().lines().filter { it.isNotEmpty() }
        assertEquals(6, lines.size, "expected one log line per callback")
    }
}
