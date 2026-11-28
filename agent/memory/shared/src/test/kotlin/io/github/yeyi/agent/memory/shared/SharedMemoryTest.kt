package io.github.yeyi.agent.memory.shared

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class SharedMemoryTest {

    private val created: Instant = Instant.parse("2026-01-02T03:04:05Z")
    private val accessed: Instant = Instant.parse("2026-02-03T04:05:06Z")

    private fun memory(description: String = "旅行偏好"): SharedMemory = SharedMemory(
        name = "travel",
        description = description,
        createdAt = created,
        lastAccessedAt = accessed,
    )

    @Test
    fun `render then parse and bodyOf round-trips`() {
        val body = "- 喜欢海边\n- 不吃辣"
        val text = memory().render(body)

        val parsed = SharedMemory.parse("travel", text)

        assertEquals("travel", parsed.name)
        assertEquals("旅行偏好", parsed.description)
        assertEquals(created, parsed.createdAt)
        assertEquals(accessed, parsed.lastAccessedAt)
        assertEquals(body, SharedMemory.bodyOf(text))
    }

    @Test
    fun `plain text without frontmatter is all body`() {
        val before = Instant.now()
        val text = "只是一段正文\n第二行"

        val parsed = SharedMemory.parse("plain", text)

        assertEquals("", parsed.description)
        assertNotEquals(Instant.EPOCH, parsed.createdAt)
        assertFalse(parsed.createdAt.isBefore(before))
        assertEquals(text, SharedMemory.bodyOf(text))
    }

    @Test
    fun `unclosed frontmatter is treated as no frontmatter`() {
        val text = "---\nname: half\ncreatedAt: $created"

        val parsed = SharedMemory.parse("half", text)

        assertEquals("", parsed.description)
        assertEquals(text, SharedMemory.bodyOf(text))
    }

    @Test
    fun `missing lastAccessedAt falls back to createdAt`() {
        val text = "---\nname: travel\ncreatedAt: $created\n---\nbody"

        val parsed = SharedMemory.parse("travel", text)

        assertEquals(created, parsed.createdAt)
        assertEquals(created, parsed.lastAccessedAt)
        assertNotEquals(Instant.EPOCH, parsed.lastAccessedAt)
    }

    @Test
    fun `missing both timestamps falls back to now`() {
        val before = Instant.now()
        val parsed = SharedMemory.parse("travel", "---\nname: travel\n---\nbody")
        val after = Instant.now()

        assertFalse(parsed.createdAt.isBefore(before))
        assertFalse(parsed.createdAt.isAfter(after))
        assertEquals(parsed.createdAt, parsed.lastAccessedAt)
        assertNotEquals(Instant.EPOCH, parsed.lastAccessedAt)
    }

    @Test
    fun `malformed timestamp falls back instead of throwing`() {
        val text = "---\ncreatedAt: 2026-01-01\nlastAccessedAt: not-a-time\n---\nbody"

        val parsed = SharedMemory.parse("travel", text)

        assertNotEquals(Instant.EPOCH, parsed.createdAt)
        assertNotEquals(Instant.EPOCH, parsed.lastAccessedAt)
    }

    @Test
    fun `file name wins over frontmatter name`() {
        val text = "---\nname: other\ncreatedAt: $created\n---\nbody"

        assertEquals("travel", SharedMemory.parse("travel", text).name)
    }

    @Test
    fun `description newline is folded to a single line`() {
        val text = memory(description = "第一行\n第二行").render("body")

        val parsed = SharedMemory.parse("travel", text)

        assertEquals("第一行 第二行", parsed.description)
        assertEquals(1, text.lines().count { it.startsWith("description:") })
        assertTrue(text.lines().contains("description: 第一行 第二行"))
    }

    @Test
    fun `description with colon and Chinese round-trips`() {
        val description = "用户画像:偏好与背景"

        val parsed = SharedMemory.parse("user", memory(description = description).render("body"))

        assertEquals(description, parsed.description)
    }

    @Test
    fun `empty body renders without extra blank line`() {
        val text = memory().render("")

        assertEquals("", SharedMemory.bodyOf(text))
        assertTrue(text.endsWith("---\n"))
        assertEquals("", text.substringAfterLast("---\n"))
    }

    @Test
    fun `multiline body keeps order and content`() {
        val body = "- a\n- b\n- c"

        assertEquals(body, SharedMemory.bodyOf(memory().render(body)))
    }
}
