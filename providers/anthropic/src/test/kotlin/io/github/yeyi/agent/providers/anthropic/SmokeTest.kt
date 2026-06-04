package io.github.yeyi.agent.providers.anthropic

import kotlin.test.Test
import kotlin.test.assertEquals

class SmokeTest {
    @Test
    fun `anthropic client has correct providerName`() {
        val client = AnthropicClient(apiKey = "k", model = "claude-sonnet-4-6")
        assertEquals("anthropic", client.providerName)
    }
}
