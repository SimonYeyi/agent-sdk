package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.memory.MemoryEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant

/**
 * 锁定 ChatMessage 多态持久化契约：
 * 1. sealed 子类使用 [ChatMessage] 上声明的短 [kotlinx.serialization.SerialName] 作为 type 判别值；
 * 2. 无需显式 SerializersModule 注册，裸 Json（与 [JsonlMemory]/[JsonlConversation] 生产配置一致）即可编解码。
 */
class ChatMessageSerializationTest {

    // 与 JsonlMemory/JsonlConversation 的生产配置保持一致：无 SerializersModule，sealed 子类由编译器自动注册
    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `type discriminator uses short serial names`() {
        val system = encode(ChatMessage.System("sys"))
        assertEquals("system", system.messageType())

        val user = encode(ChatMessage.User(listOf(ContentPart.Text("u"))))
        assertEquals("user", user.messageType())

        val assistant = encode(ChatMessage.Assistant(content = "a"))
        assertEquals("assistant", assistant.messageType())

        val toolResult = encode(
            ChatMessage.ToolResult(toolCallId = "tc", toolName = "echo", parts = listOf(ContentPart.Text("r")))
        )
        assertEquals("tool_result", toolResult.messageType())
    }

    @Test
    fun `roundtrip preserves variants and metadata without explicit registration`() {
        val created = Instant.parse("2026-09-17T10:00:00Z")
        val entries = listOf(
            MemoryEntry(ChatMessage.System("sys")),
            MemoryEntry(
                ChatMessage.User(listOf(ContentPart.Text("u"))),
                createAt = created,
                tags = setOf("steering"),
            ),
            MemoryEntry(ChatMessage.Assistant(content = "a")),
            MemoryEntry(
                ChatMessage.ToolResult(toolCallId = "tc", toolName = "echo", parts = listOf(ContentPart.Text("r")))
            ),
        )

        val decoded = entries.map { json.decodeFromString<MemoryEntry>(json.encodeToString(it)) }

        assertEquals(4, decoded.size)
        assertEquals("sys", (decoded[0].message as ChatMessage.System).content)
        assertEquals("u", (decoded[1].message as ChatMessage.User).parts[0].let { (it as ContentPart.Text).text })
        assertEquals(created, decoded[1].createAt)
        assertEquals(setOf("steering"), decoded[1].tags)
        assertEquals("a", (decoded[2].message as ChatMessage.Assistant).content)
        assertEquals("tc", (decoded[3].message as ChatMessage.ToolResult).toolCallId)
        assertEquals("echo", (decoded[3].message as ChatMessage.ToolResult).toolName)
        assertTrue(decoded[3].message is ChatMessage.ToolResult)
    }

    private fun encode(message: ChatMessage): String =
        json.encodeToString(MemoryEntry.serializer(), MemoryEntry(message))

    private fun String.messageType(): String =
        json.parseToJsonElement(this).jsonObject["message"]!!.jsonObject["type"]!!.jsonPrimitive.content
}
