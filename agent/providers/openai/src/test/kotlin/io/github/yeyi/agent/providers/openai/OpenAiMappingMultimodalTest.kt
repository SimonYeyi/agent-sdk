package io.github.yeyi.agent.providers.openai

import io.github.yeyi.agent.AgentException
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class OpenAiMappingMultimodalTest {
    @Test
    fun `text-only User uses StringValue`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Text("hi")))
        ))
        val mapped = mapToOpenAi("gpt-4o", req, stream = false)
        val msg = mapped.messages.last()
        assertTrue(msg.content is OpenAiContent.StringValue)
        assertEquals("hi", (msg.content as OpenAiContent.StringValue).value)
    }

    @Test
    fun `Image with Http source becomes image_url`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(
                ContentPart.Text("see"),
                ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
            ))
        ))
        val mapped = mapToOpenAi("gpt-4o", req, stream = false)
        val content = mapped.messages.last().content as OpenAiContent.PartsValue
        assertEquals(2, content.value.size)
        val imagePart = content.value[1] as OpenAiContentPart.ImageUrl
        assertEquals("https://x.com/a.jpg", imagePart.imageUrl.url)
    }

    @Test
    fun `Image with Data source becomes data URI`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.Data("image/png", "XYZ"))))
        ))
        val mapped = mapToOpenAi("gpt-4o", req, stream = false)
        val imagePart = (mapped.messages.last().content as OpenAiContent.PartsValue)
            .value[0] as OpenAiContentPart.ImageUrl
        assertEquals("data:image/png;base64,XYZ", imagePart.imageUrl.url)
    }

    @Test
    fun `Audio with Data source becomes input_audio with parsed format`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Audio(MediaSource.Data("audio/wav", "AAAA"))))
        ))
        val mapped = mapToOpenAi("gpt-4o", req, stream = false)
        val audio = (mapped.messages.last().content as OpenAiContent.PartsValue)
            .value[0] as OpenAiContentPart.InputAudio
        assertEquals("AAAA", audio.inputAudio.data)
        assertEquals("wav", audio.inputAudio.format)
    }

    @Test
    fun `Video throws UnsupportedContent`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Video(MediaSource.Http("https://x.com/v.mp4"))))
        ))
        assertFailsWith<AgentException.UnsupportedContent> {
            mapToOpenAi("gpt-4o", req, stream = false)
        }
    }

    @Test
    fun `Image with FileId throws UnsupportedContent`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Image(MediaSource.FileId("file-1"))))
        ))
        assertFailsWith<AgentException.UnsupportedContent> {
            mapToOpenAi("gpt-4o", req, stream = false)
        }
    }

    @Test
    fun `Audio with Http throws UnsupportedContent`() {
        val req = ChatRequest(messages = listOf(
            ChatMessage.User(listOf(ContentPart.Audio(MediaSource.Http("https://x.com/a.mp3"))))
        ))
        assertFailsWith<AgentException.UnsupportedContent> {
            mapToOpenAi("gpt-4o", req, stream = false)
        }
    }
}
