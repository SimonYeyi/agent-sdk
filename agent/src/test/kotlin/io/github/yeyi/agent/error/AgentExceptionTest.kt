package io.github.yeyi.agent.error

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.toAgentException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
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
    fun `toAgentException returns same instance when cause is already AgentException`() {
        val original = AgentException.MaxIterations(5)
        val wrapped = original.toAgentException()
        assertSame(original, wrapped)
    }

    @Test
    fun `toAgentException lifts non-AgentException throwable into AgentException family`() {
        val cause = IllegalStateException("boom")
        val wrapped = cause.toAgentException()
        assertEquals(cause, wrapped.cause)
        assertTrue(wrapped.message!!.contains("boom"))
    }
}
