package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.llm.StreamEvent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertTrue

class RoundsBoundedMemorySummaryTest {
    @Test
    fun `text-only User summarises to its text`() = runTest {
        val provider = EchoLlmProvider()
        val mem = RoundsBoundedMemory(InMemoryMemory(), maxRounds = 1, llmProvider = provider)
        mem.add(ChatMessage.User(listOf(ContentPart.Text("hello world"))))
        mem.add(ChatMessage.Assistant("reply"))
        mem.add(ChatMessage.User(listOf(ContentPart.Text("second turn"))))
        val summary = mem.history().firstOrNull { it is ChatMessage.System } as? ChatMessage.System
        assertTrue(summary != null, "summary should exist")
        assertTrue("hello world" in summary.content)
    }

    @Test
    fun `multimodal User summarises text parts and placeholder for media`() = runTest {
        val provider = EchoLlmProvider()
        val mem = RoundsBoundedMemory(InMemoryMemory(), maxRounds = 1, llmProvider = provider)
        mem.add(ChatMessage.User(listOf(
            ContentPart.Text("see this:"),
            ContentPart.Image(MediaSource.Http("https://x.com/cat.jpg"))
        )))
        mem.add(ChatMessage.Assistant("ok"))
        mem.add(ChatMessage.User(listOf(ContentPart.Text("next"))))
        val summary = mem.history().firstOrNull { it is ChatMessage.System } as? ChatMessage.System
        assertTrue(summary != null)
        assertTrue("see this:" in summary.content)
        assertTrue("[Image(source=Http" in summary.content)
        assertTrue("cat.jpg" in summary.content)
    }
}

/**
 * LLM provider that returns the text content of the User message in the request
 * as the assistant response. Lets summary tests assert the pipeline propagated
 * the expected text/placeholder content from User.parts through to the LLM call
 * and back into the System summary message.
 */
private class EchoLlmProvider : LlmProvider {
    override val name: String = "echo"

    override suspend fun chat(request: ChatRequest): ChatResponse {
        val echoed = request.messages
            .filterIsInstance<ChatMessage.User>()
            .joinToString("\n") { msg ->
                msg.parts.joinToString("\n") { part ->
                    when (part) {
                        is ContentPart.Text -> part.text
                        is ContentPart.Image -> "[image:${part.source}]"
                        is ContentPart.Audio -> "[audio:${part.source}]"
                        is ContentPart.Video -> "[video:${part.source}]"
                    }
                }
            }
        return ChatResponse(
            message = ChatMessage.Assistant(content = echoed),
            finishReason = FinishReason.Stop
        )
    }

    override fun chatStream(request: ChatRequest): Flow<StreamEvent> = flow { }
}
