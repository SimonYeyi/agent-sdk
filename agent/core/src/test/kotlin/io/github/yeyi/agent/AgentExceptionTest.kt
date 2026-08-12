package io.github.yeyi.agent

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AgentExceptionTest {
    @Test
    fun `UnsupportedContent is an AgentException`() {
        val e = AgentException.UnsupportedContent("video base64 not supported")
        assertTrue(e is AgentException)
    }

    @Test
    fun `UnsupportedContent carries message`() {
        val e = AgentException.UnsupportedContent("OpenAI video not supported")
        assertEquals("OpenAI video not supported", e.message)
    }
}
