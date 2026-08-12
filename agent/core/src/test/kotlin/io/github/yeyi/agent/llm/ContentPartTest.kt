package io.github.yeyi.agent.llm

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals

class ContentPartTest {
    private val json = Json

    @Test
    fun `kind returns Text for Text variant`() {
        val p: ContentPart = ContentPart.Text("hi")
        assertEquals(ContentPart.Kind.Text, p.kind)
    }

    @Test
    fun `kind returns Image for Image variant`() {
        val p: ContentPart = ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
        assertEquals(ContentPart.Kind.Image, p.kind)
    }

    @Test
    fun `kind returns Audio for Audio variant`() {
        val p: ContentPart = ContentPart.Audio(MediaSource.FileId("af-1"))
        assertEquals(ContentPart.Kind.Audio, p.kind)
    }

    @Test
    fun `kind returns Video for Video variant`() {
        val p: ContentPart = ContentPart.Video(MediaSource.Http("https://x.com/v.mp4"))
        assertEquals(ContentPart.Kind.Video, p.kind)
    }

    @Test
    fun `serializes Text variant`() {
        val p: ContentPart = ContentPart.Text("hello")
        assertEquals(
            """{"type":"text","text":"hello"}""",
            json.encodeToString(p)
        )
    }

    @Test
    fun `serializes Image variant with embedded MediaSource`() {
        val p: ContentPart = ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
        val s = json.encodeToString(p)
        assertEquals(
            """{"type":"image","source":{"type":"url","url":"https://x.com/a.jpg"}}""",
            s
        )
    }

    @Test
    fun `round-trips Image with Data source`() {
        val p: ContentPart = ContentPart.Image(MediaSource.Data("image/png", "XYZ"))
        val back: ContentPart = json.decodeFromString(json.encodeToString(p))
        assertEquals(p, back)
    }
}
