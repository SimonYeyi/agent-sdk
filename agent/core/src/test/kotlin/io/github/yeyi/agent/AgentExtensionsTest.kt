package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.llm.toTextMessage
import kotlin.test.Test
import kotlin.test.assertEquals

class AgentExtensionsTest {

    @Test
    fun `toTextMessage renders Http Image as image placeholder with url slug`() {
        val message = ChatMessage.User(listOf(
            ContentPart.Image(MediaSource.Http("https://example.com/foo/bar.png"))
        ))

        val rendered = (message.toTextMessage() as ChatMessage.User).parts.single()
        val text = (rendered as ContentPart.Text).text
        assertEquals("[image] bar.png", text)
    }

    @Test
    fun `toTextMessage renders Local Image as image placeholder with truncated fileId`() {
        val message = ChatMessage.User(listOf(
            ContentPart.Image(MediaSource.Local(fileId = "abcd1234efgh5678", mimeType = "image/png"))
        ))

        val rendered = (message.toTextMessage() as ChatMessage.User).parts.single()
        val text = (rendered as ContentPart.Text).text
        assertEquals("[image] local fileId=abcd1234", text)
    }
}
