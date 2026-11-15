package io.github.yeyi.agent.memory

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.text
import io.github.yeyi.agent.llm.toTextMessage
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

internal class RoundsBoundedMemory(
    private val underlying: Memory,
    private val maxRounds: Int = 20,
    llmProvider: LlmProvider,
) : Memory {
    private val retainRatio: Double = 0.3
    private val compressor = MemoryCompressor(maxRounds, retainRatio, llmProvider)
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

        hook?.beforeMemoryCompress(agentContext, summaries.toList())

        // 3. 原文压缩生成摘要；随后按需归并（触发判据与定价都在压缩器内）
        val compressedMessages = history.filterIndexed { index, _ -> index in compressedIndices }
        val summary = compressor.compressOriginal(compressedMessages.map { it.message })

        summaries.add(summary)
        compressor.compressSummaries(summaries)

        // 4. 重建 underlying —— 基于 MemoryEntry 操作，保留窗口消息的元数据跨重建存活
        rebuildUnderlying(history, retainedIndices, summaries)

        this.summaries = summaries

        hook?.afterMemoryCompress(agentContext, summaries.toList())
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

    internal suspend fun crop() {
        hook?.beforeMemoryCompress(agentContext, summaries!!)
        if (cropToolMessages().not()) truncate()
        hook?.afterMemoryCompress(agentContext, summaries!!)
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

/**
 * LLM 摘要压缩器。核心压缩方法的共性参数 = 内容 + 定价公式（字数 → 预算），
 * 原文压缩与摘要归并的差异全部收敛在各自的定价 lambda 里；不碰记忆存储。
 */
internal class MemoryCompressor(
    private val maxRounds: Int,
    private val retainRatio: Double,
    private val llmProvider: LlmProvider,
) {
    /**
     * 核心压缩：渲染输入、测字数、按 pricing 得出预算字数、调 LLM 产出 Summary。
     * maxTokens = 预算 ×2，不设全局上限：它是保险丝不是输出考核线——头寸贴换算率
     * （中文 1 字 ≈ 1~1.5 token）会让正常波动误触发截断；全局封顶既救不了小输出限
     * provider（请求照样超限），反而把能写满额的大输出限 provider 砍成静默截断。
     * 预算超模型输出硬限时由 provider 明确拒绝，失败可见。
     */
    private suspend fun compress(
        messages: List<ChatMessage>,
        pricing: (chars: Int) -> Int
    ): Summary {
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
        val length = pricing(chars)
        val request = ChatRequest(
            messages = buildList {
                add(
                    ChatMessage.System(
                        "请将以下对话内容压缩为一份${length}字以内的摘要。要求：先归纳话题，再将每个话题压缩为：\n" +
                            "用户话题：<用户话题>\n" +
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
     * 辅助·原文压缩：窗口原文 → 单条摘要，留 8%。
     * 下限 800 防极小窗口算出几十字预算、退化成无意义摘要；
     * 上限 = 压缩窗口轮数 × 250 字/轮保真度。
     */
    suspend fun compressOriginal(messages: List<ChatMessage>): Summary {
        val minimum = 800
        val maximum = (maxRounds * (1 - retainRatio) * 250).toInt().coerceAtLeast(minimum)
        return compress(messages) { (it * 0.08).toInt().coerceIn(minimum, maximum) }
    }

    /**
     * 辅助·摘要归并：条数封顶或总文本超上限时归并，比例 0.8、不设下限，产物就地写回。
     * 摘要总上限 = maxSummaries × original 单条 cap = 1750×maxRounds
     * （= 0.175 × 预估上下文窗口，1 万字/轮）——恰好装下 10 条满格 original，
     * 字数触发不抢跑条数触发，归并节奏统一为每 10 个压缩事件一次，0.8 如实兑现。
     * 已知取舍：
     * - "总量恒不超上限"靠 LLM 遵守字数预算（maxTokens 给 2× 头寸）；
     *   keep 超标致 remaining 转负时钳至 800，待新 original 轮换出 keep 后自愈；
     * - 归并产物钉死值（密集稳态 ≈1225×maxRounds 字）随 maxRounds 线性增长，
     *   档位约 26+ 时 maxTokens 突破单次调用输出包络（64k），分片归并挂账后续。
     */
    suspend fun compressSummaries(summaries: MutableList<Summary>) {
        val maxSummaries = 10
        // 摘要总上限 = 10 × original 单条 cap（175×maxRounds），即 0.175 × 预估上下文窗口
        val summaryBudget = maxRounds * 1750

        val needsMerge = summaries.size >= maxSummaries ||
                summaries.sumOf { it.content.length } > summaryBudget
        if (!needsMerge) return

        val keepCount = (maxSummaries * retainRatio).toInt().coerceAtLeast(1)
        val toMerge = summaries.dropLast(keepCount)
        val keep = summaries.takeLast(keepCount)
        if (toMerge.isEmpty()) return

        val summaryMessages = toMerge.map { ChatMessage.System(it.content) }
        // 字数上限是提示词软约束，keep 超标可致 remaining 转负；钳至正下限保证请求恒合法
        val remaining = (summaryBudget - keep.sumOf { it.content.length }).coerceAtLeast(800)
        val merged = compress(summaryMessages) { (it * 0.8).toInt().coerceAtMost(remaining) }

        summaries.clear()
        summaries.add(merged)
        summaries.addAll(keep)
    }
}

/** 摘要条目统一打上的 tag，便于记忆层/UI 识别摘要来源。 */
private const val SUMMARY_LABEL: String = "summary"

@Serializable
public data class Summary(val content: String)

@Serializable
private data class SummaryContainer(val summaries: List<Summary>)
