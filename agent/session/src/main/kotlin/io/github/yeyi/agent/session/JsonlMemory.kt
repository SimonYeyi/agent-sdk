package io.github.yeyi.agent.session

import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.memory.MediaArchivable
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.memory.MemoryEntry
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * JSONL 持久化 Memory —— session 场景 agent 侧的完整实现。
 *
 * 直接实现 [MediaArchivable] 声明持有归档,供 [io.github.yeyi.agent.AgentBuilder]
 * 能力检测;可选注入 [JsonlConversation] 作为分页副作用:add() 在落盘
 * memory.jsonl 的同时把同一 entry 追加到分页文件,保证
 * [Session.memory] 与 [Session.conversation] 两视图一致。
 */
internal class JsonlMemory(
    private val file: File,
    override val mediaArchive: MediaArchive,
    private val conversation: JsonlConversation? = null,
) : Memory, MediaArchivable {

    private val json = Json { ignoreUnknownKeys = true }

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
        conversation?.append(entry)
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
