package io.github.yeyi.agent.session

import io.github.yeyi.agent.memory.MemoryEntry
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 分页会话记录 —— **只读视图 + 追加副作用**。
 *
 * 由 [JsonlMemory] 作为 session.memory 的补充:JsonlMemory.add() 负责
 * memory.jsonl 持久化,并把同一 entry 通过 [append] 追加到分页文件,
 * 供 [Conversation.history] 分页读取。Conversation 本身不承载写契约。
 *
 * 语义:conversation 是**追加型全量会话记录** —— [JsonlMemory.rebuild]
 * (压缩/摘要)不回写本视图,因此它可能包含 memory 中已被压缩掉的旧消息,
 * 即 conversation 始终是 memory 的超集。
 */
internal class JsonlConversation(
    private val conversationDir: File,
    private val pageSizeThreshold: Long = 20 * 1024  // 20KB
) : Conversation {

    private val json = Json { ignoreUnknownKeys = true }

    private var maxPage: Int = 0
    private var startPage: Int = 0
    private var initialized: Boolean = false

    private fun ensureInitialized() {
        if (initialized) return
        conversationDir.mkdirs()
        val files = conversationDir.listFiles()
            ?.filter { it.name.startsWith("page") && it.name.endsWith(".jsonl") }
            ?.mapNotNull { pageNumberOf(it) }
            ?: emptyList()
        maxPage = files.maxOrNull() ?: 0
        if (maxPage == 0) {
            maxPage = 1
            File(conversationDir, "page1.jsonl").createNewFile()
        }
        initialized = true
    }

    private fun currentFile(): File {
        return File(conversationDir, "page$maxPage.jsonl")
    }

    /** 从 `pageN.jsonl` 文件名解析页码；名字不符合规则时返回 null。 */
    private fun pageNumberOf(file: File): Int? =
        Regex("page(\\d+)\\.jsonl").find(file.name)?.groupValues?.get(1)?.toIntOrNull()

    /**
     * 追加一条记忆到分页文件(超过阈值自动开新页)。
     * 由 [JsonlMemory.add] 作为副作用调用,不直接对外暴露。
     */
    internal suspend fun append(entry: MemoryEntry) {
        ensureInitialized()

        withContext(Dispatchers.IO) {
            val file = currentFile()
            if (file.length() >= pageSizeThreshold) {
                maxPage++
                val newFile = File(conversationDir, "page$maxPage.jsonl")
                newFile.createNewFile()
            }
            File(conversationDir, "page$maxPage.jsonl")
                .appendText(json.encodeToString(entry) + "\n")
        }
    }

    /**
     * 获取对话历史
     *
     * ## 分页存储
     *
     * 消息按页存储，每页达到阈值（默认20KB）后创建新页。
     * 文件命名：page1.jsonl, page2.jsonl, ...
     *
     * ## 翻页锚点机制
     *
     * 为解决翻页过程中新增消息导致页码错位问题，采用锚点算法：
     * - 调用 [history](1) 时记录当前最大页码为锚点（[startPage]）
     * - 此后 [history](N) 基于锚点计算：`实际页 = 锚点 - (N - 1)`
     * - 再次调用 [history](1) 重置锚点到最新页
     *
     * 传 [Conversation.PAGE_ALL] 则跳过锚点直接返回全部消息；
     * 否则**首次调用必须传入 1**，否则返回空列表。
     *
     * ## 示例
     *
     * ```
     * // 正确用法
     * val latest = history(1)      // 建立锚点，获取最新页
     * val older = history(2)       // 基于锚点翻页
     *
     * // 错误用法（返回空列表）
     * val older = history(2)      // 锚点未建立
     * ```
     */
    override suspend fun history(page: Int): List<MemoryEntry> {
        return withContext(Dispatchers.IO) {
            if (page == Conversation.PAGE_ALL) {
                return@withContext conversationDir.listFiles()
                    ?.filter { it.name.startsWith("page") && it.name.endsWith(".jsonl") }
                    ?.sortedBy { pageNumberOf(it) ?: 0 }
                    ?.flatMap { readMessages(it) }
                    ?: emptyList()
            }

            if (page <= 0) return@withContext emptyList()

            ensureInitialized()

            // 用户回到最新，重置锚点
            if (page == 1) {
                startPage = maxPage
            }

            val filePage = startPage - (page - 1)
            if (filePage <= 0) return@withContext emptyList()

            val file = File(conversationDir, "page$filePage.jsonl")
            if (!file.exists()) return@withContext emptyList()

            readMessages(file)
        }
    }

    private fun readMessages(file: File): List<MemoryEntry> {
        return file.readLines()
            .filter { it.isNotBlank() }
            .mapNotNull { runCatching { json.decodeFromString<MemoryEntry>(it) }.getOrNull() }
    }
}
