package io.github.yeyi.agent.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaSourceTest {
    private val json = Json

    @Test
    fun `serializes Http variant with @SerialName url`() {
        val src: MediaSource = MediaSource.Http("https://example.com/a.jpg")
        assertEquals("""{"type":"url","url":"https://example.com/a.jpg"}""", json.encodeToString(src))
    }

    @Test
    fun `serializes Data variant with mimeType and base64`() {
        val src: MediaSource = MediaSource.Data("image/jpeg", "BASE64DATA")
        assertEquals(
            """{"type":"data","mimeType":"image/jpeg","base64":"BASE64DATA"}""",
            json.encodeToString(src)
        )
    }

    @Test
    fun `serializes FileId variant with id`() {
        val src: MediaSource = MediaSource.FileId("file-abc")
        assertEquals("""{"type":"fileId","id":"file-abc"}""", json.encodeToString(src))
    }

    @Test
    fun `round-trips Http through JSON`() {
        val src: MediaSource = MediaSource.Http("https://x.com/y")
        val text = json.encodeToString(src)
        val back: MediaSource = json.decodeFromString(text)
        assertEquals(src, back)
    }
}