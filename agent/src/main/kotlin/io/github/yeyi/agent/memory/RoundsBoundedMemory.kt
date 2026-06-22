package io.github.yeyi.agent.memory

import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.NoOpAgentHook
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.LlmProvider
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val SUMMARY_PREFIX = "[SUMMARY]"
private const val SUMMARY_SUFFIX = "[/SUMMARY]"

internal class RoundsBoundedMemory(
    private val underlying: Memory,
    private val maxRounds: Int = 20,
    private val llmProvider: LlmProvider,
    private val hook: AgentHook = NoOpAgentHook
) : Memory {
    private val retainRatio: Double = 0.3
    private val maxSummaries: Int = 10

    private var summaries: MutableList<Summary>? = null

    override suspend fun add(message: ChatMessage) {
        underlying.add(message)
        ensureInitialized()

        if (message is ChatMessage.User) {
            val currentRounds = countRounds(underlying.history())
            val retainWindow = (maxRounds * retainRatio).toInt()
            if (currentRounds > maxRounds) {
                compressRounds(retainWindow)
            }
        }
    }

    override suspend fun history(): List<ChatMessage> {
        ensureInitialized()
        return underlying.history()
    }

    override suspend fun rebuild(messages: List<ChatMessage>) {
        underlying.rebuild(messages)
    }

    private suspend fun ensureInitialized() {
        if (summaries != null) return
        summaries = restoreSummaries(underlying.history())
    }

    private fun restoreSummaries(history: List<ChatMessage>): MutableList<Summary> {
        val summaryMsg = history.firstOrNull()
            ?.takeIf { it.isSummary() }
            ?.let { it as ChatMessage.User }
            ?: return mutableListOf()

        val json = summaryMsg.content
            .removePrefix(SUMMARY_PREFIX)
            .removeSuffix(SUMMARY_SUFFIX)

        return Json.decodeFromString<SummaryContainer>(json).summaries.toMutableList()
    }

    private fun ChatMessage.isSummary(): Boolean =
        this is ChatMessage.User && content.startsWith(SUMMARY_PREFIX)

    private fun summariesToJson(summaries: List<Summary>): String {
        val container = SummaryContainer(summaries)
        return "$SUMMARY_PREFIX${Json.encodeToString(container)}$SUMMARY_SUFFIX"
    }

    private suspend fun compressRounds(retainWindow: Int) {
        val summaries = this.summaries!!.toMutableList()
        val history = underlying.history()

        // 1. 从后往前找到保留窗口索引
        val retainedIndices = extractRetainedIndices(history, retainWindow)

        // 2. 计算压缩窗口（全部 - 保留）
        val compressedIndices = history.indices.toMutableSet().also {
            it.removeAll(retainedIndices.toSet())
            if (summaries.isNotEmpty() && it.isNotEmpty()) {
                it.remove(history.indices.first())
            }
        }

        if (compressedIndices.isEmpty()) return

        // 3. 提取压缩窗口内容并生成摘要
        val compressedContent = history.filterIndexed { index, _ -> index in compressedIndices }
            .joinToString("\n") { msg ->
                when (msg) {
                    is ChatMessage.User -> msg.content
                    is ChatMessage.Assistant -> msg.content ?: ""
                    is ChatMessage.ToolResult -> msg.content
                    is ChatMessage.System -> msg.content
                }
            }
        val summary = generateSummary(compressedContent)

        summaries.add(summary)

        if (summaries.size >= maxSummaries) {
            compressSummaries(summaries)
        }

        // 4. 重建 underlying
        rebuildUnderlying(history, retainedIndices, summaries)

        this.summaries = summaries
    }

    private suspend fun compressSummaries(summaries: MutableList<Summary>) {
        val keepCount = (maxSummaries * retainRatio).toInt().coerceAtLeast(1)
        val toMerge = summaries.dropLast(keepCount)
        val keep = summaries.takeLast(keepCount)

        if (toMerge.isEmpty()) return

        val mergedContent = toMerge.joinToString("\n") { it.content }
        val newSummary = generateSummary(mergedContent)

        summaries.clear()
        summaries.add(newSummary)
        summaries.addAll(keep)
    }

    private suspend fun generateSummary(content: String): Summary {
        val request = ChatRequest(
            messages = listOf(
                ChatMessage.System(
                    "请将以下对话内容压缩为一段${maxRounds * 2}字以内的摘要，保留关键结论和信息。压缩时保持以下格式：\n问：用户问题\n答：助手回答\n（每轮对话的问答）"
                ),
                ChatMessage.User(content)
            ),
            temperature = 0.3,
        )

        val response = llmProvider.chat(request)
        val summaryText = response.message.content
            ?: throw IllegalStateException("LLM summary generation failed: empty response")

        return Summary(summaryText)
    }

    private suspend fun rebuildUnderlying(
        history: List<ChatMessage>,
        retainedIndices: List<Int>,
        summaries: MutableList<Summary>
    ) {
        val roundsMessages = history.filterIndexed { index, _ -> index in retainedIndices }

        val summaryMessage = summaries.let {
            if (it.isNotEmpty()) ChatMessage.User(summariesToJson(it)) else null
        }

        val toRebuild = buildList {
            summaryMessage?.let { add(it) }
            addAll(roundsMessages)
        }

        rebuild(toRebuild)
    }

    private fun extractRetainedIndices(
        history: List<ChatMessage>,
        retainWindow: Int
    ): List<Int> {
        val result = mutableListOf<Int>()
        var roundsFound = 0
        var skippingTrailingUsers = true

        for (index in history.indices.reversed()) {
            val msg = history[index]
            result.add(index)
            if (msg is ChatMessage.User && !msg.isSummary()) {
                if (skippingTrailingUsers) continue
                if (++roundsFound >= retainWindow) break
            } else {
                skippingTrailingUsers = false
            }
        }

        return result
    }

    private fun countRounds(history: List<ChatMessage>): Int =
        history.count { it is ChatMessage.User && !it.isSummary() }
}

@Serializable
private data class Summary(val content: String)

@Serializable
private data class SummaryContainer(val summaries: List<Summary>)
