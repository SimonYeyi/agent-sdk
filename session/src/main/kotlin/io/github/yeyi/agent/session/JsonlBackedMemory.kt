package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.memory.Memory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File
import java.io.FileOutputStream

public class JsonlBackedMemory(
    private val file: File,
    private val scope: CoroutineScope = CoroutineScope(Dispatchers.IO)
) : Memory {

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
            messages.add(message)
        }
        scope.launch {
            FileOutputStream(file, true).use { it.write((json.encodeToString(message) + "\n").toByteArray()) }
        }
    }

    override suspend fun history(): List<ChatMessage> {
        return loadToCache().toList()
    }

    override suspend fun clear() {
        synchronized(this) {
            cachedMessages?.clear()
        }
        if (file.exists()) {
            file.writeText("")
        }
    }
}