package io.github.yeyi.agent.error

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.isContextOverflow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AgentExceptionTest {
    @Test
    fun `MaxIterations carries the limit in its message`() {
        val ex = AgentException.MaxIterations(10)
        assertTrue(ex.message!!.contains("10"))
    }

    @Test
    fun `LlmError wraps cause`() {
        val cause = IllegalStateException("network down")
        val ex = AgentException.LlmError(cause)
        assertEquals(cause, ex.cause)
        assertTrue(ex.message!!.contains("network down"))
    }

    @Test
    fun `InvalidResponse carries reason`() {
        val ex = AgentException.InvalidResponse("missing field")
        assertTrue(ex.message!!.contains("missing field"))
    }

    @Test
    fun `ToolNotFound lists available names`() {
        val ex = AgentException.ToolNotFound("foo", listOf("bar", "baz"))
        assertTrue(ex.message!!.contains("foo"))
        assertTrue(ex.message!!.contains("bar"))
        assertTrue(ex.message!!.contains("baz"))
    }

    @Test
    fun `ContextOverflow carries reason and optional cause`() {
        val cause = RuntimeException("original")
        val ex = AgentException.ContextOverflow("too many tokens", cause)
        assertEquals("too many tokens", ex.reason)
        assertEquals(cause, ex.cause)
        assertTrue(ex.message!!.contains("Context overflow"))
        assertTrue(ex.message!!.contains("too many tokens"))
    }

    @Test
    fun `ContextOverflow without cause`() {
        val ex = AgentException.ContextOverflow("input exceeds limit")
        assertEquals("input exceeds limit", ex.reason)
        assertEquals(null, ex.cause)
    }

    @Test
    fun `isContextOverflow returns true for ContextOverflow exception`() {
        assertTrue(AgentException.ContextOverflow("too long").isContextOverflow())
    }

    @Test
    fun `isContextOverflow matches common error patterns`() {
        val patterns = listOf(
            "context length exceeded",
            "maximum context length",
            "too many tokens",
            "token limit exceeded",
            "context overflow",
            "payload too large",
            "max tokens exceeded",
            "prompt is too long",
            "input exceeds max tokens",
            "context window full",
            "input token count exceeds limit",
            "text length exceeds maximum allowed",
            "request size limit exceeded",
            "request entity too large",
            "content too large",
            "ctx exceeded",
            "sequence length exceeds configured context size",
            "kv cache full",
            "message too long",
            "exceeds the maximum number of tokens",
            "conversation history too long",
            "prompt too big",
            "input too long",
            "sequence too long",
            "tokens limit reached"
        )
        patterns.forEach { pattern ->
            assertTrue(
                RuntimeException(pattern).isContextOverflow(),
                "should match: $pattern"
            )
        }
    }

    @Test
    fun `isContextOverflow is case insensitive`() {
        assertTrue(RuntimeException("CONTEXT LENGTH EXCEEDED").isContextOverflow())
        assertTrue(RuntimeException("Token Limit Exceeded").isContextOverflow())
    }

    @Test
    fun `isContextOverflow returns false for unrelated errors`() {
        assertFalse(RuntimeException("connection timeout").isContextOverflow())
        assertFalse(RuntimeException("invalid json").isContextOverflow())
        assertFalse(RuntimeException("authentication failed").isContextOverflow())
        assertFalse(RuntimeException("rate limit exceeded").isContextOverflow())
    }

    @Test
    fun `isContextOverflow returns false for empty message`() {
        assertFalse(RuntimeException().isContextOverflow())
    }

    @Test
    fun `isContextOverflow considers cause message`() {
        val cause = RuntimeException("context length exceeded")
        val wrapper = RuntimeException("something else", cause)
        assertTrue(wrapper.isContextOverflow())
    }
}
