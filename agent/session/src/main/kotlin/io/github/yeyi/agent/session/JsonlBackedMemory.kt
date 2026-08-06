package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.memory.Memory
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

public class JsonlBackedMemory(private val file: File) : Memory {

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

    @Volatile
    private var cachedMessages: MutableList<ChatMessage>? = null

    private fun loadToCache(): MutableList<ChatMessage> {
        if (cachedMessages != null) return cachedMessages!!
        val messages = if (file.exists()) {
            file.readLines()
                .filter { it.isNotBlank() }
                .map { json.decodeFromString<ChatMessage>(it) }
                .toMutableList()
        } else {
            mutableListOf()
        }
        cachedMessages = messages
        return messages
    }

    override suspend fun add(message: ChatMessage) {
        val messages = loadToCache()
        synchronized(this) {
            file.appendText(json.encodeToString(message) + "\n")
            messages.add(message)
        }
    }

    override suspend fun history(): List<ChatMessage> {
        return loadToCache().toList()
    }

    override suspend fun rebuild(messages: List<ChatMessage>) {
        synchronized(this) {
            val tmpFile = File(file.parentFile, file.name + ".tmp")
            try {
                tmpFile.writeText("")
                messages.forEach { message ->
                    tmpFile.appendText(json.encodeToString(message) + "\n")
                }
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                cachedMessages?.clear()
                cachedMessages = messages.toMutableList()
            } catch (e: Exception) {
                tmpFile.delete()
                throw e
            }
        }
    }
}