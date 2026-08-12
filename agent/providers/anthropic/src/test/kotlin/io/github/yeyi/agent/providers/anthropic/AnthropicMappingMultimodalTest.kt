package io.github.yeyi.agent.providers.anthropic

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class AnthropicMappingMultimodalTest {
    @Test
    fun `Image with Http source becomes image url block`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))))
        ))
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        val blocks = mapped.messages.last().content
        assertEquals(1, blocks.size)
        val img = blocks[0] as AnthropicContentBlock.Image
        assertTrue(img.source is AnthropicContentBlock.Image.UrlSource)
        assertEquals("https://x.com/a.jpg", (img.source as AnthropicContentBlock.Image.UrlSource).url)
    }

    @Test
    fun `Image with Data source becomes image base64 block`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/png", "XYZ"))))
        ))
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        val src = (mapped.messages.last().content[0] as AnthropicContentBlock.Image).source
                as AnthropicContentBlock.Image.Base64Source
        assertEquals("image/png", src.mediaType)
        assertEquals("XYZ", src.data)
    }

    @Test
    fun `Video with Http becomes video url block`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Video(MediaSource.Http("https://x.com/v.mp4"))))
        ))
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        assertTrue(mapped.messages.last().content[0] is AnthropicContentBlock.Video)
    }

    @Test
    fun `Video with Data throws UnsupportedContent`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Video(MediaSource.Data("video/mp4", "BIN"))))
        ))
        assertFailsWith<AgentException.UnsupportedContent> {
            mapToAnthropic("claude-sonnet-4-6", req)
        }
    }

    @Test
    fun `Audio with Data becomes audio base64 block`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Audio(MediaSource.Data("audio/mp3", "MP3DATA"))))
        ))
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        assertTrue(mapped.messages.last().content[0] is AnthropicContentBlock.Audio)
    }

    @Test
    fun `text + image parts preserve order`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(
                ContentPart.Text("see"),
                ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
            ))
        ))
        val mapped = mapToAnthropic("claude-sonnet-4-6", req)
        val blocks = mapped.messages.last().content
        assertEquals(2, blocks.size)
        assertTrue(blocks[0] is AnthropicContentBlock.Text)
        assertTrue(blocks[1] is AnthropicContentBlock.Image)
    }
}
