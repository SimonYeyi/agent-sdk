package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.memory.Memory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.modules.SerializersModule
import kotlinx.serialization.modules.polymorphic
import kotlinx.serialization.modules.subclass
import java.io.File

public class JsonlConversation(
    private val conversationDir: File,
    private val innerMemory: Memory,
    private val pageSizeThreshold: Long = 10 * 1024  // 10KB
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

    private var maxPage: Int = 0
    private var startPage: Int = 0
    private var initialized: Boolean = false

    private fun ensureInitialized() {
        if (initialized) return
        conversationDir.mkdirs()
        val files = conversationDir.listFiles()
            ?.filter { it.name.startsWith("page") && it.name.endsWith(".jsonl") }
            ?.mapNotNull {
                Regex("page(\\d+)\\.jsonl").find(it.name)?.groupValues?.get(1)?.toIntOrNull()
            }
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

    override suspend fun add(message: ChatMessage) {
        ensureInitialized()

        withContext(Dispatchers.IO) {
            val file = currentFile()
            if (file.length() >= pageSizeThreshold) {
                maxPage++
                val newFile = File(conversationDir, "page$maxPage.jsonl")
                newFile.createNewFile()
            }
            File(conversationDir, "page$maxPage.jsonl").appendText(json.encodeToString(message) + "\n")
        }

        innerMemory.add(message)
    }

    /**
     * 获取对话消息
     *
     * ## 分页存储
     *
     * 消息按页存储，每页达到阈值（默认10KB）后创建新页。
     * 文件命名：page1.jsonl, page2.jsonl, ...
     *
     * ## 翻页锚点机制
     *
     * 为解决翻页过程中新增消息导致页码错位问题，采用锚点算法：
     * - 调用 [messages](1) 时记录当前最大页码为锚点（[startPage]）
     * - 此后 [messages](N) 基于锚点计算：`实际页 = 锚点 - (N - 1)`
     * - 再次调用 [messages](1) 重置锚点到最新页
     *
     * **首次调用必须传入 1**，否则返回空列表。
     *
     * ## 示例
     *
     * ```
     * // 正确用法
     * val latest = messages(1)      // 建立锚点，获取最新页
     * val older = messages(2)       // 基于锚点翻页
     *
     * // 错误用法（返回空列表）
     * val older = messages(2)      // 锚点未建立
     * ```
     */
    override suspend fun messages(page: Int?): List<ChatMessage> {
        return withContext(Dispatchers.IO) {
            if (page == null) {
                return@withContext conversationDir.listFiles()
                    ?.filter { it.name.startsWith("page") && it.name.endsWith(".jsonl") }
                    ?.sortedBy { it.name }
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

    private fun readMessages(file: File): List<ChatMessage> {
        return file.readLines()
            .filter { it.isNotBlank() }
            .map { json.decodeFromString<ChatMessage>(it) }
    }
}
