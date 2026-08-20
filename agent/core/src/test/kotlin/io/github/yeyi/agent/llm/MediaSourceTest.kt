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

    @Test
    fun `serializes Local variant with fileId and mimeType`() {
        val src: MediaSource = MediaSource.Local(
            fileId = "550e8400-e29b-41d4-a716-446655440000",
            mimeType = "image/jpeg",
        )
        assertEquals(
            """{"type":"local","fileId":"550e8400-e29b-41d4-a716-446655440000","mimeType":"image/jpeg"}""",
            json.encodeToString(src),
        )
    }

    @Test
    fun `deserializes Local variant from JSON`() {
        val text = """{"type":"local","fileId":"abc-123","mimeType":"image/png"}"""
        val src: MediaSource = json.decodeFromString(text)
        assertEquals(MediaSource.Local("abc-123", "image/png"), src)
    }

    @Test
    fun `Local equals by data class equality`() {
        val a = MediaSource.Local("id1", "image/jpeg")
        val b = MediaSource.Local("id1", "image/jpeg")
        val c = MediaSource.Local("id2", "image/jpeg")
        assertEquals(a, b)
        assertEquals(a.hashCode(), b.hashCode())
        assertEquals(false, a == c)
    }

    @Test
    fun `Local is not equal to FileId even with same id field`() {
        val local = MediaSource.Local("id1", "image/jpeg")
        val fileId = MediaSource.FileId("id1")
        assertEquals(false, local == fileId)
    }
}
