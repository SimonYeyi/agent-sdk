package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.memory.Memory
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File

public class JsonlConversation(
    private val file: File,
    private val innerMemory: Memory
) : Conversation, Memory by innerMemory {

    private val json = Json {
        ignoreUnknownKeys = true
        serializersModule = SerializersModule {
            polymorphic(ChatMessage::class) {
                subclass(ChatMessage.System::class)
                subclass(ChatMessage.User::class)
                subclass(ChatMessage.Assistant::class)
                subclass(ChatMessage.ToolResult::class)
            }
        }
    }

    override suspend fun add(message: ChatMessage) {
        file.appendText(json.encodeToString(message) + "\n")
        innerMemory.add(message)
    }

    override fun messages(): List<ChatMessage> {
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<ChatMessage>(it) }
    }
}
