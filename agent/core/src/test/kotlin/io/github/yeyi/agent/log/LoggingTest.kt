package io.github.yeyi.agent.log

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggingTest {

    private val captured = ByteArrayOutputStream()
    private val originalErr = System.err

    @BeforeTest
    fun setUp() {
        System.setErr(PrintStream(captured))
    }

    @AfterTest
    fun tearDown() {
        System.setErr(originalErr)
        captured.reset()
    }

    private fun stderr(): String = captured.toString(Charsets.UTF_8)

    // --- debug ---

    @Test
    fun `debug outputs with DEBUG prefix and tag`() {
        val log = LoggingTagged("agent")
        log.debug("test debug message")
        val out = stderr()
        assertTrue(out.contains("[DEBUG]"))
        assertTrue(out.contains("agent"))
        assertTrue(out.contains("test debug message"))
    }

    // --- info ---

    @Test
    fun `info outputs with INFO prefix and tag`() {
        val log = LoggingTagged("agent")
        log.info("test info message")
        val out = stderr()
        assertTrue(out.contains("[INFO]"))
        assertTrue(out.contains("agent"))
        assertTrue(out.contains("test info message"))
    }

    // --- warn ---

    @Test
    fun `warn outputs with WARN prefix and tag`() {
        val log = LoggingTagged("agent")
        log.warn("test warn message")
        val out = stderr()
        assertTrue(out.contains("[WARN]"))
        assertTrue(out.contains("agent"))
        assertTrue(out.contains("test warn message"))
    }

    // --- error ---

    @Test
    fun `error outputs with ERROR prefix and tag`() {
        val log = LoggingTagged("agent")
        log.error("test error message")
        val out = stderr()
        assertTrue(out.contains("[ERROR]"))
        assertTrue(out.contains("agent"))
        assertTrue(out.contains("test error message"))
    }

    // --- multiple calls ---

    @Test
    fun `multiple log calls produce separate lines`() {
        val log = LoggingTagged("agent")
        log.debug("debug msg")
        log.info("info msg")
        log.warn("warn msg")
        log.error("error msg")
        val out = stderr()
        val lines = out.lines().filter { it.isNotEmpty() }
        assertEquals(4, lines.size)
        assertTrue(lines[0].contains("[DEBUG]"))
        assertTrue(lines[1].contains("[INFO]"))
        assertTrue(lines[2].contains("[WARN]"))
        assertTrue(lines[3].contains("[ERROR]"))
    }

    // --- different tags ---

    @Test
    fun `different tags appear in output`() {
        val agentLog = LoggingTagged("agent")
        val hookLog = LoggingTagged("hook")
        agentLog.info("from agent")
        hookLog.info("from hook")
        val out = stderr()
        assertTrue(out.contains("agent"))
        assertTrue(out.contains("hook"))
    }
}
