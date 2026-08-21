package io.github.yeyi.agent.modality

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive

/**
 * 多模态适配器 — 在 LLM 请求边界做 Local ↔ Data 转换。
 *
 * ## 抽象契约(本类只定方向,不定策略)
 *
 * - [archive]: write 边,把消息内的 [MediaSource.Data] 转 [MediaSource.Local]
 * - [resolve]: read 边,把消息内的 [MediaSource.Local] 还原成可被 LLM 消费的形态
 *
 * 哪些消息要 resolve、按什么规则 resolve、占位怎么写 — 都是 [resolve] 实现的策略选择,
 * 本抽象层**不做限制**。caller 注入自定义 adapter 时可以自由实现
 * (例如还原所有 User、按其他规则挑消息、用不同的占位格式)。
 *
 * ## 与 ToolResult 的关系
 *
 * [ChatMessage.ToolResult] 含 media 时, [ToolResultModalityAdapter.adapt] 在
 * [io.github.yeyi.agent.ReActAgent] 的 `buildRequest()` 边界已经把它拆成
 * "text-only ToolResult + 合成 User",合成 User 内的 media 自然走 User 路径
 * 走 [resolve]。**ToolResult 的 media 不在本适配器职责内**。
 *
 * ## archive / resolve 共用 MediaArchive
 *
 * 两者共用构造器注入的同一个 [MediaArchive](典型配置 write/read 对称同一实例),
 * 也可由 caller 决定是否换不同实现。
 *
 * ## 为什么是 abstract class 而非 interface
 *
 * [archive] 是通用逻辑(Data→Local 阈值规则共享),放基类避免每个实现重写;
 * [resolve] 因策略差异大,留给子类。abstract class 而非 `fun interface`
 * 是为了 (1) 复用 archive 默认实现, (2) 未来加方法不破坏 SAM 契约。
 */
public abstract class ModalityAdapter(protected val mediaArchive: MediaArchive) {

    /**
     * Write 边:把 message 内"大的" [MediaSource.Data] 转 [MediaSource.Local] 后返回。
     * 只处理 [ChatMessage.User] 和 [ChatMessage.ToolResult](后者由
     * [ToolResultModalityAdapter] 在请求边界进一步拆出 media;此处只保证落盘形态)。
     *
     * 在 [io.github.yeyi.agent.ReActAgent] 的 `memory.add(...)` 前调用,让 memory 始终持有 Local 引用,
     *
     * @return 原 message(若不含大 Data)或替换过大 Data 为 Local 的 message
     */
    internal suspend fun archive(message: ChatMessage): ChatMessage = archiveLargeMedia(message)

    /**
     * Read 边:把 messages 渲染成"可直接喂 LLM"的形态 — 把 [MediaSource.Local]
     * 还原成 LLM 能消费的形式(Data / 占位文本等),具体策略由实现选择。
     *
     * 调用前置条件:[ChatMessage.ToolResult] 的 media 已由
     * [ToolResultModalityAdapter.adapt] 在请求边界拆出(media 通过合成 User 形式
     * 进入 messages),本方法不需要重复处理 ToolResult。
     */
    public abstract suspend fun resolve(messages: List<ChatMessage>): List<ChatMessage>

    private suspend fun archiveLargeMedia(message: ChatMessage): ChatMessage = when (message) {
        is ChatMessage.User -> message.copy(
            parts = message.parts.map { archiveIfLarge(it) },
        )

        is ChatMessage.ToolResult -> message.copy(
            parts = message.parts.map { archiveIfLarge(it) },
        )

        else -> message
    }

    private suspend fun archiveIfLarge(part: ContentPart): ContentPart {
        val src: MediaSource? = when (part) {
            is ContentPart.Image -> part.source
            is ContentPart.Audio -> part.source
            is ContentPart.Video -> part.source
            is ContentPart.Text -> null
        }
        return if (src is MediaSource.Data && src.base64.length > ARCHIVE_THRESHOLD) {
            @Suppress("KotlinConstantConditions")
            when (part) {
                is ContentPart.Image -> part.copy(source = mediaArchive.store(src))
                is ContentPart.Audio -> part.copy(source = mediaArchive.store(src))
                is ContentPart.Video -> part.copy(source = mediaArchive.store(src))
                is ContentPart.Text -> part
            }
        } else part
    }

    private companion object {
        /** base64 长度阈值 (≈ 768B 原始字节)。 */
        const val ARCHIVE_THRESHOLD: Int = 1024
    }
}
