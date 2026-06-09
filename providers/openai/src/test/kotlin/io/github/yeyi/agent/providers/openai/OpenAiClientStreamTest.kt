package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.StreamEvent
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class OpenAiClientStreamTest {

    @Test
    fun `chatStream emits TextDelta and Done`() = runTest {
        val sseBody = """
            data: {"choices":[{"index":0,"delta":{"content":"hi"}}]}

            data: [DONE]

        """.trimIndent()
        val client = OpenAiClient(
            apiKey = "k",
            httpClient = mockOpenAiHttpClient { respond(sseBody, HttpStatusCode.OK, sseHeaders) },
        )
        val events = client.chatStream(
            ChatRequest(messages = listOf(ChatMessage.User("hi")))
        ).toList()
        val deltas = events.filterIsInstance<StreamEvent.ContentDelta>()
        assertEquals(1, deltas.size)
        assertEquals("hi", deltas[0].text)
    }
}
