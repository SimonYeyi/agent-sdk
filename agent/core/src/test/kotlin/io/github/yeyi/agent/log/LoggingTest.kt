package io.github.yeyi.agent.log

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class LoggingTest {

    private val capturedOut = ByteArrayOutputStream()
    private val capturedErr = ByteArrayOutputStream()
    private val originalOut = System.out
    private val originalErr = System.err

    @BeforeTest
    fun setUp() {
        System.setOut(PrintStream(capturedOut))
        System.setErr(PrintStream(capturedErr))
    }

    @AfterTest
    fun tearDown() {
        System.setOut(originalOut)
        System.setErr(originalErr)
        capturedOut.reset()
        capturedErr.reset()
    }

    private fun stdout(): String = capturedOut.toString(Charsets.UTF_8)
    private fun stderr(): String = capturedErr.toString(Charsets.UTF_8)
    private fun allOutput(): String = stdout() + stderr()

    // --- debug ---

    @Test
    fun `debug outputs with DEBUG prefix and tag`() {
        val log = LoggingTagged("agent")
        log.debug("test debug message")
        val out = allOutput()
        assertTrue(out.contains("[DEBUG]"))
        assertTrue(out.contains("agent"))
        assertTrue(out.contains("test debug message"))
    }

    // --- info ---

    @Test
    fun `info outputs with INFO prefix and tag`() {
        val log = LoggingTagged("agent")
        log.info("test info message")
        val out = allOutput()
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
        val out = allOutput()
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
        val out = allOutput()
        assertTrue(out.contains("agent"))
        assertTrue(out.contains("hook"))
    }

    // --- delegate injection ---

    private fun createDefaultDelegate(): LogDelegate = object : LogDelegate {
        override fun debug(tag: String, msg: String) {
            System.out.println("[DEBUG] $tag: $msg")
        }

        override fun info(tag: String, msg: String) {
            System.out.println("[INFO] $tag: $msg")
        }

        override fun warn(tag: String, msg: String?, e: Throwable?) {
            System.err.println("[WARN] $tag: ${msg ?: ""}${if (e != null) "\n${e.stackTraceToString()}" else ""}")
        }

        override fun error(tag: String, msg: String?, e: Throwable?) {
            System.err.println("[ERROR] $tag: ${msg ?: ""}${if (e != null) "\n${e.stackTraceToString()}" else ""}")
        }
    }

    @Test
    fun `delegate injection`() {
        val fakeDelegate = FakeLogDelegate()
        Logging.setDelegate(fakeDelegate)

        val log = LoggingTagged("test")
        log.info("test message")

        assertEquals(1, fakeDelegate.entries.size)
        assertEquals("test message", fakeDelegate.entries[0].msg)
        assertEquals(FakeLogDelegate.Level.INFO, fakeDelegate.entries[0].level)
        assertEquals("test", fakeDelegate.entries[0].tag)

        Logging.setDelegate(createDefaultDelegate())
    }

    @Test
    fun `delegate with throwable`() {
        val fakeDelegate = FakeLogDelegate()
        Logging.setDelegate(fakeDelegate)

        val log = LoggingTagged("test")
        val exception = RuntimeException("test exception")
        log.warn("warning message", exception)

        assertEquals(1, fakeDelegate.entries.size)
        assertEquals("warning message", fakeDelegate.entries[0].msg)
        assertEquals(exception, fakeDelegate.entries[0].throwable)
        assertEquals(FakeLogDelegate.Level.WARN, fakeDelegate.entries[0].level)

        Logging.setDelegate(createDefaultDelegate())
    }

    @Test
    fun `multiple delegates`() {
        val fakeDelegate1 = FakeLogDelegate()
        val fakeDelegate2 = FakeLogDelegate()

        Logging.setDelegate(fakeDelegate1)
        val log1 = LoggingTagged("test1")
        log1.info("message1")

        Logging.setDelegate(fakeDelegate2)
        val log2 = LoggingTagged("test2")
        log2.info("message2")

        assertEquals(1, fakeDelegate1.entries.size)
        assertEquals(1, fakeDelegate2.entries.size)
        assertEquals("message1", fakeDelegate1.entries[0].msg)
        assertEquals("message2", fakeDelegate2.entries[0].msg)

        Logging.setDelegate(createDefaultDelegate())
    }
}
