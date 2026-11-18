package io.github.yeyi.agent.memory

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.isContextOverflow
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.llm.toTextMessage
import io.github.yeyi.agent.log.log
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class RoundsBoundedMemory(
    private val underlying: Memory,
    private val maxRounds: Int = 20,
    private val llmProvider: LlmProvider,
) : Memory {
    private val retainRatio: Double = 0.3
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
            if (currentRounds > maxRounds) compress()
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
            label = SUMMARY_LABEL,
        )
    }

    private fun getSummaries(history: List<MemoryEntry>): List<Summary> {
        return history
            .filter { it.label == SUMMARY_LABEL }
            .firstNotNullOfOrNull { entry ->
                (entry.message as ChatMessage.System)
                    .let { Json.decodeFromString<SummaryContainer>(it.content).summaries }
            } ?: emptyList()
    }

    private suspend fun compress(retainWindow: Int = (maxRounds * retainRatio).toInt()) {
        val summaries = this.summaries!!.toMutableList()
        val history = underlying.history()

        // 1. 从后往前找到保留窗口索引
        val retainedIndices = extractRetainedIndices(history, retainWindow)

        // 2. 计算压缩窗口
        val compressedIndices = getCompressWindowIndices(history, retainedIndices)

        if (compressedIndices.isEmpty()) return

        invokeWithHook {
            // 3. 原文压缩生成摘要；超容量/超字数则滑出最旧，不做二次压缩。
            //    压缩 LLM 超限时降级裁剪工具消息腾出上下文，让本轮流程继续，下一轮 add 重新触发压缩；
            //    删不动工具消息时 crop 内部再退到硬裁（truncate）。
            val compressedMessages =
                history.filterIndexed { index, _ -> index in compressedIndices }
            val summary = try {
                compressOriginal(compressedMessages.map { it.message })
            } catch (e: Throwable) {
                if (e.isContextOverflow().not()) throw e
                log.warn("memory overflow during compression, performing crop", e)
                crop(withHook = false); return@invokeWithHook
            }

            summaries.add(summary)
            evictOldestSummary(summaries)

            // 4. 重建 underlying —— 基于 MemoryEntry 操作，保留窗口消息的元数据跨重建存活
            rebuildUnderlying(history, retainedIndices, summaries)

            this.summaries = summaries
        }
    }

    /**
     * 原文压缩：窗口原文 → 单条摘要，留 8%，全系统唯一的压缩调用点。
     * 下限 800 防极小窗口算出几十字预算、退化成无意义摘要；
     * 上限 = 压缩窗口轮数 × 250 字/轮保真度，产出 ≤175×maxRounds 字，
     * maxTokens=350×maxRounds，现实档位（≤100）距任何模型输出硬限都有数倍余量，
     * 无需知道模型输出上限。
     * maxTokens = 预算 ×2，不设全局上限：它是保险丝不是输出考核线——头寸贴换算率
     * （中文 1 字 ≈ 1~1.5 token）会让正常波动误触发截断；全局封顶既救不了小输出限
     * provider（请求照样超限），反而把能写满额的大输出限 provider 砍成静默截断。
     */
    private suspend fun compressOriginal(messages: List<ChatMessage>): Summary {
        val rendered = messages.map { it.toTextMessage() }
        val chars = rendered.sumOf { message ->
            when (message) {
                is ChatMessage.User -> message.parts.text.length
                is ChatMessage.Assistant -> (message.content?.length ?: 0) +
                        message.toolCalls.sumOf { it.name.length + it.arguments.toString().length }

                is ChatMessage.ToolResult -> message.parts.text.length
                is ChatMessage.System -> message.content.length
            }
        }
        val minimum = 800
        val maximum = (maxRounds * (1 - retainRatio) * 250).toInt().coerceAtLeast(minimum)
        val length = (chars * 0.08).toInt().coerceIn(minimum, maximum)
        val request = ChatRequest(
            messages = buildList {
                add(
                    ChatMessage.System(
                        "请将以下对话内容压缩为一份${length}字以内的摘要。要求：先跨回合归纳话题，再将每个话题压缩为：\n" +
                                "用户话题：<归一化的用户话题>\n" +
                                "推理过程：<关键执行与决策步骤，含文件路径与代码位置、配置与数值变更、失败方案及原因>\n" +
                                "助手回复：<最终结论>\n"
                    )
                )
                addAll(rendered)
            },
            temperature = 0.3,
            maxTokens = length * 2
        )

        val summaryText = llmProvider.chat(request).message.content
            ?: throw IllegalStateException("LLM summary generation failed: empty response")
        return Summary(summaryText)
    }

    /**
     * 摘要滑出：条数超 10 或总字数超 1750×maxRounds（= 10 × original 单条 cap
     * = 0.175 × 预估窗口，1 万字/轮）时移出最旧，零 LLM 调用。
     * 取消归并的理由：归并是唯一要求模型单次写 remaining=1225×maxRounds 字的调用点，
     * 删掉它即删掉对模型输出上限的依赖——剩余的原文压缩产出 ≤175×maxRounds 字，
     * maxTokens=350×maxRounds，现实档位（≤100）距任何输出硬限都有数倍余量。
     * 代价与好处：历史地平线有限 ≈ 0.3×maxRounds（保留窗口）+ 10×0.7×maxRounds = 7.3×maxRounds 轮，
     * 第 11 条进来时第 1 条被真实遗忘；换来滑出前每条摘要只经过一次压缩、无归并链式损耗。
     */
    private fun evictOldestSummary(summaries: MutableList<Summary>) {
        val maxSummaries = 10
        val summaryBudget = maxRounds * 1750
        while (summaries.size > maxSummaries) summaries.removeAt(0)
        // 字数护栏：累积漂移靠移出最旧收口
        while (summaries.sumOf { it.content.length } > summaryBudget) summaries.removeAt(0)
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
            it.removeAll { index -> history[index].label == SUMMARY_LABEL }
        }
    }

    private suspend fun invokeWithHook(action: suspend () -> Unit) {
        hook?.beforeMemoryCompress(agentContext, summaries!!.toList())
        action.invoke()
        hook?.afterMemoryCompress(agentContext, summaries!!.toList())
    }

    internal suspend fun crop(withHook: Boolean) {
        val action = suspend { if (cropToolMessages().not()) truncate() }
        if (withHook) invokeWithHook(action) else action.invoke()
    }

    /**
     * 移除压缩窗口内的工具消息（Assistant.toolCalls + ToolResult），
     * 保留窗口内的消息不动。用于 context 超限时的第一层兜底。
     * @return 是否有工具消息被移除；false 时调用方应退到第二层 truncate
     */
    private suspend fun cropToolMessages(retainWindow: Int = (maxRounds * retainRatio).toInt()): Boolean {
        val history = underlying.history()
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
     * 按轮次裁剪旧消息，系数默认 0.3，硬裁剪不调 LLM。
     * 裁剪完整的一轮（从 User 到下一个 User 之前的所有消息）。
     * 用于 context 超限时的第二层兜底。
     */
    private suspend fun truncate(coefficient: Double = 0.3) {
        val history = underlying.history()
        if (history.count { it.message !is ChatMessage.System } <= 1) {
            throw IllegalStateException("Cannot truncate by coefficient: only one user message.")
        }
        val currentRounds = history.count { it.message is ChatMessage.User }
        val toRemove = (currentRounds * coefficient).toInt().coerceAtLeast(1)
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
private const val SUMMARY_LABEL: String = "summary"

@Serializable
public data class Summary(val content: String)

@Serializable
private data class SummaryContainer(val summaries: List<Summary>)
