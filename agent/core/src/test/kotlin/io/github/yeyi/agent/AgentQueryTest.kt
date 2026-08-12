package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class AgentQueryTest {
    private val json = Json

    @Test
    fun `text factory wraps string in Text part`() {
        val q = AgentQuery.text("hi")
        assertEquals(listOf(ContentPart.Text("hi")), q.parts)
    }

    @Test
    fun `rejects empty parts`() {
        assertFailsWith<IllegalArgumentException> {
            AgentQuery(emptyList())
        }
    }

    @Test
    fun `accepts multi-modal parts in order`() {
        val q = AgentQuery(listOf(
            ContentPart.Text("look"),
            ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
        ))
        assertEquals(2, q.parts.size)
        assertEquals(ContentPart.Kind.Text, q.parts[0].kind)
        assertEquals(ContentPart.Kind.Image, q.parts[1].kind)
    }

    @Test
    fun `round-trips through JSON`() {
        val q = AgentQuery(listOf(
            ContentPart.Text("look"),
            ContentPart.Image(MediaSource.Http("https://x.com/a.jpg"))
        ))
        val s = json.encodeToString(q)
        val back = json.decodeFromString<AgentQuery>(s)
        assertEquals(q, back)
    }
}
