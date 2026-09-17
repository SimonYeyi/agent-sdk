package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.memory.MemoryEntry
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

internal class JsonlMemory(
    private val file: File,
    override val mediaArchive: MediaArchive,
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
    private var cachedEntries: MutableList<MemoryEntry>? = null

    private fun loadToCache(): MutableList<MemoryEntry> {
        if (cachedEntries != null) return cachedEntries!!
        val entries = if (file.exists()) {
            file.readLines()
                .filter { it.isNotBlank() }
                .mapNotNull { runCatching { json.decodeFromString<MemoryEntry>(it) }.getOrNull() }
                .toMutableList()
        } else {
            mutableListOf()
        }
        cachedEntries = entries
        return entries
    }

    override suspend fun add(entry: MemoryEntry) {
        val entries = loadToCache()
        synchronized(this) {
            file.appendText(json.encodeToString(entry) + "\n")
            entries.add(entry)
        }
    }

    override suspend fun history(): List<MemoryEntry> {
        return loadToCache().toList()
    }

    override suspend fun rebuild(entries: List<MemoryEntry>) {
        synchronized(this) {
            val tmpFile = File(file.parentFile, file.name + ".tmp")
            try {
                tmpFile.writeText("")
                entries.forEach { entry ->
                    tmpFile.appendText(json.encodeToString(entry) + "\n")
                }
                Files.move(tmpFile.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                cachedEntries?.clear()
                cachedEntries = entries.toMutableList()
            } catch (e: Exception) {
                tmpFile.delete()
                throw e
            }
        }
    }
}
