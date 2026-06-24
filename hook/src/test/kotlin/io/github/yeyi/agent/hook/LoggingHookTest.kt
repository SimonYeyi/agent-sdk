package io.github.yeyi.agent.hook

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.fakes.FakeLlmProvider
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.ToolCall
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.tool.Tool
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

    private fun context(iter: Int = 1) = AgentContext(
        persona = Persona(role = ""),
        maxIterations = 5,
        currentIteration = iter,
        memory = InMemoryMemory(),
        llmProvider = FakeLlmProvider(),
        tools = emptyList<Tool>(),
        maxRounds = 20,
    )

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
    fun `beforeLlmCall writes a debug line with iter`() = runTest {
        val h = LoggingHook()
        h.beforeLlmCall(context(3))
        val out = stderr()
        assertTrue(out.contains("hook"), "should tag with hook")
        assertTrue(out.contains("iter=3"))
    }

    @Test
    fun `afterLlmResponse writes a debug line with iter`() = runTest {
        val h = LoggingHook()
        val r = ChatResponse(
            message = ChatMessage.Assistant(
                content = "hi",
                toolCalls = listOf(toolCall(), toolCall())
            ),
            finishReason = FinishReason.ToolCalls
        )
        h.afterLlmResponse(context(2), r)
        val out = stderr()
        assertTrue(out.contains("iter=2"))
    }

    @Test
    fun `beforeToolCall always returns null and logs id+name`() = runTest {
        val h = LoggingHook()
        val r = h.beforeToolCall(context(), toolCall())
        assertNull(r, "LoggingHook must never short-circuit")
        val out = stderr()
        assertTrue(out.contains("id=c1"))
        assertTrue(out.contains("name=echo"))
    }

    @Test
    fun `afterToolCall returns input unchanged and logs duration and isError`() = runTest {
        val h = LoggingHook()
        val input = ToolExecutionResult("payload", isError = true)
        val out1 = h.afterToolCall(context(), toolCall(), input, 42)
        assertSame(input, out1, "LoggingHook must not rewrite results")
        val log = stderr()
        assertTrue(log.contains("dur=42ms"))
        assertTrue(log.contains("isError=true"))
    }

    @Test
    fun `afterToolCall with isError=false logs isError=false`() = runTest {
        val h = LoggingHook()
        h.afterToolCall(context(), toolCall(), ToolExecutionResult("ok", isError = false), 1)
        assertTrue(stderr().contains("isError=false"))
    }

    @Test
    fun `onError writes a warn line with class and message`() = runTest {
        val h = LoggingHook()
        h.onError(context(), AgentException.LlmError(RuntimeException("boom")))
        val out = stderr()
        assertTrue(out.contains("LlmError"))
        assertTrue(out.contains("boom"))
    }

    @Test
    fun `onRunFinished writes an info line with iterations`() = runTest {
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
        h.onRunFinished(context(iter = 5), r)
        val out = stderr()
        assertTrue(out.contains("iter=5/5"))
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
    fun `all LoggingHook callbacks produce at least one log line per call`() = runTest {
        val h = LoggingHook()
        h.beforeLlmCall(context())
        h.afterLlmResponse(context(), emptyResponse())
        h.beforeToolCall(context(), toolCall())
        h.afterToolCall(context(), toolCall(), ToolExecutionResult("x"), 1)
        h.onError(context(), AgentException.LlmError(RuntimeException("e")))
        h.onRunFinished(
            context(),
            AgentResult(
                message = ChatMessage.Assistant(content = "ok"),
                iterations = 1,
                toolCalls = emptyList(),
                usage = null,
            )
        )
        // 6 calls → at least 6 log lines (error with stack trace may produce multiple lines)
        val lines = stderr().lines().filter { it.isNotEmpty() }
        assertTrue(lines.size >= 6, "expected at least 6 log lines, got ${lines.size}")
    }
}
