package io.github.yeyi.agent.memory

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.toTextMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class RoundsBoundedMemory(
    private val underlying: Memory,
    private val maxRounds: Int = 20,
    private val llmProvider: LlmProvider,
) : Memory {

    private val retainRatio: Double = 0.3
    private val maxSummaries: Int = 10
    private var summaries: MutableList<Summary>? = null

    private var hook: AgentHook? = null
    private lateinit var agentContext: AgentContext

    fun attachHook(hook: AgentHook, agentContext: AgentContext) {
        this.hook = hook
        this.agentContext = agentContext
    }

    override suspend fun add(entry: MemoryEntry) {
        underlying.add(entry)
        ensureInitialized()

        if (entry.message is ChatMessage.User) {
            val currentRounds = underlying.history().count { it.message is ChatMessage.User }
            val retainWindow = (maxRounds * retainRatio).toInt()
            if (currentRounds > maxRounds) {
                compressRounds(retainWindow)
            }
        }
    }

    override suspend fun history(): List<MemoryEntry> {
        ensureInitialized()
        return underlying.history()
    }

    override suspend fun rebuild(entries: List<MemoryEntry>) {
        underlying.rebuild(entries)
    }

    private suspend fun ensureInitialized() {
        if (summaries != null) return
        summaries = getSummaries(underlying.history()).toMutableList()
    }

    private fun createSummaryEntry(summaries: List<Summary>): MemoryEntry {
        val container = SummaryContainer(summaries)
        return MemoryEntry(
            message = ChatMessage.System(Json.encodeToString(container)),
            tags = setOf(SUMMARY_TAG),
        )
    }

    private fun getSummaries(history: List<MemoryEntry>): List<Summary> {
        return history
            .filter { SUMMARY_TAG in it.tags }
            .firstNotNullOfOrNull { entry ->
                (entry.message as ChatMessage.System)
                    .let { Json.decodeFromString<SummaryContainer>(it.content).summaries }
            } ?: emptyList()
    }

    private suspend fun compressRounds(retainWindow: Int) {
        val summaries = this.summaries!!.toMutableList()
        val history = underlying.history()

        // 1. 从后往前找到保留窗口索引
        val retainedIndices = extractRetainedIndices(history, retainWindow)

        // 2. 计算压缩窗口
        val compressedIndices = getCompressWindowIndices(history, retainedIndices)

        if (compressedIndices.isEmpty()) return

        hook?.beforeMemoryCompress(agentContext, summaries.toList())

        // 3. 提取压缩窗口内容并生成摘要
        val compressedMessages = history.filterIndexed { index, _ -> index in compressedIndices }
        val summary = generateSummary(compressedMessages.map { it.message })

        summaries.add(summary)

        if (summaries.size >= maxSummaries) {
            compressSummaries(summaries)
        }

        // 4. 重建 underlying —— 基于 MemoryEntry 操作，保留窗口消息的元数据跨重建存活
        rebuildUnderlying(history, retainedIndices, summaries)

        this.summaries = summaries

        hook?.afterMemoryCompress(agentContext, summaries.toList())
    }

    private suspend fun compressSummaries(summaries: MutableList<Summary>) {
        val keepCount = (maxSummaries * retainRatio).toInt().coerceAtLeast(1)
        val toMerge = summaries.dropLast(keepCount)
        val keep = summaries.takeLast(keepCount)

        if (toMerge.isEmpty()) return

        val summaryMessages = toMerge.map { ChatMessage.System(it.content) }
        val newSummary = generateSummary(summaryMessages)

        summaries.clear()
        summaries.add(newSummary)
        summaries.addAll(keep)
    }

    private suspend fun generateSummary(messages: List<ChatMessage>): Summary {
        val length = (maxRounds * (1 - retainRatio) * 2 * 30).toInt()
        val rendered = messages.map { it.toTextMessage() }
        val request = ChatRequest(
            messages = buildList {
                add(ChatMessage.System("请将以下对话内容压缩为一段${length}字以内的摘要，保留关键结论和信息。压缩时保持以下格式：\n问：用户问题\n答：你的回答"))
                addAll(rendered)
            },
            temperature = 0.3,
            maxTokens = length * 2
        )

        val summaryText = llmProvider.chat(request).message.content
            ?: throw IllegalStateException("LLM summary generation failed: empty response")
        return Summary(summaryText)
    }

    private suspend fun rebuildUnderlying(
        history: List<MemoryEntry>,
        retainedIndices: List<Int>,
        summaries: List<Summary>
    ) {
        val roundsEntries = history.filterIndexed { index, _ -> index in retainedIndices }

        val summaryEntry = summaries.let {
            if (it.isNotEmpty()) createSummaryEntry(it) else null
        }

        val toRebuild = buildList {
            summaryEntry?.let { add(it) }
            addAll(roundsEntries)
        }

        rebuild(toRebuild)
    }

    private fun extractRetainedIndices(
        history: List<MemoryEntry>,
        retainWindow: Int
    ): List<Int> {
        val result = mutableListOf<Int>()
        var roundsFound = 0
        var skippingTrailingUsers = true

        for (index in history.indices.reversed()) {
            val msg = history[index].message
            result.add(index)
            if (msg is ChatMessage.User) {
                if (skippingTrailingUsers && retainWindow > 1) continue
                if (++roundsFound >= retainWindow) break
            } else {
                skippingTrailingUsers = false
            }
        }

        return result
    }

    /**
     * 获取压缩窗口的索引（排除保留窗口的部分）。
     */
    private fun getCompressWindowIndices(
        history: List<MemoryEntry>,
        retainedIndices: List<Int>
    ): Set<Int> {
        return history.indices.toMutableSet().also {
            it.removeAll(retainedIndices.toSet())
            it.removeAll { index -> SUMMARY_TAG in history[index].tags }
        }
    }

    internal suspend fun handleContextOverflow() {
        hook?.beforeMemoryCompress(agentContext, summaries!!)
        if (removeCompressWindowToolMessages().not()) {
            truncateByCoefficient()
        }
        hook?.afterMemoryCompress(agentContext, summaries!!)
    }

    /**
     * 移除压缩窗口内的工具消息（Assistant.toolCalls + ToolResult），
     * 保留窗口内的消息不动。用于 context 超限时的第一层兜底。
     * @return 被移除的消息数量
     */
    private suspend fun removeCompressWindowToolMessages(): Boolean {
        val history = underlying.history()
        val retainWindow = (maxRounds * retainRatio).toInt()
        val retainedIndices = extractRetainedIndices(history, retainWindow)
        val compressIndices = getCompressWindowIndices(history, retainedIndices)

        if (compressIndices.isEmpty()) return false

        val filtered = history.filterIndexed { index, entry ->
            if (index !in compressIndices) return@filterIndexed true
            // 保留：User、Assistant（无toolCalls）、System
            // 移除：Assistant（有toolCalls）、ToolResult
            when (entry.message) {
                is ChatMessage.User -> true
                is ChatMessage.Assistant -> entry.message.toolCalls.isEmpty()
                is ChatMessage.ToolResult -> false
                is ChatMessage.System -> true
            }
        }

        val removed = history.size - filtered.size
        if (removed > 0) rebuild(filtered)
        return removed > 0
    }

    /**
     * 按轮次裁剪旧消息，用 0.3 系数，硬裁剪不调 LLM。
     * 裁剪完整的一轮（从 User 到下一个 User 之前的所有消息）。
     * 用于 context 超限时的第二层兜底。
     */
    private suspend fun truncateByCoefficient() {
        val history = underlying.history()
        if (history.count { it.message !is ChatMessage.System } <= 1) {
            throw IllegalStateException("Cannot truncate by coefficient: only one user message.")
        }
        val currentRounds = history.count { it.message is ChatMessage.User }
        val toRemove = (currentRounds * 0.3).toInt().coerceAtLeast(1)
        val toRetain = currentRounds - toRemove

        // 只剩一轮对话的情况
        if (toRetain < 1) {
            history
                .filter { it.message is ChatMessage.System || it.message is ChatMessage.User }
                .let { rebuild(it) }
            return
        }

        // 用 extractRetainedIndices 从后往前保留 toRetain 轮
        val retainedIndices = extractRetainedIndices(history, toRetain)
        rebuildUnderlying(history, retainedIndices, summaries!!)
    }
}

/** 摘要条目统一打上的 tag，便于记忆层/UI 识别摘要来源。 */
private const val SUMMARY_TAG: String = "summary"

@Serializable
public data class Summary(val content: String)

@Serializable
private data class SummaryContainer(val summaries: List<Summary>)
