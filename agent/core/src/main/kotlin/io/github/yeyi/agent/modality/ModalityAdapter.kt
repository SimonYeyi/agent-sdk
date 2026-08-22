package io.github.yeyi.agent.modality

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.llm.toTextMessage
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
 * 调用顺序: [resolve] 先执行（把 ToolResult 中的 Local 转 Data），再由
 * [ToolResultAdapter.adapt] 在请求边界把 ToolResult 拆成
 * "text-only ToolResult + 合成 User"。
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
    private val archiveAdapter = ArchiveAdapter(mediaArchive)
    private val resolveAdapter = ResolveAdapter(mediaArchive)
    private val toolResultAdapter = ToolResultAdapter()

    /**
     * Write 边:把 message 内"大的" [MediaSource.Data] 转 [MediaSource.Local] 后返回。
     * 只处理 [ChatMessage.User] 和 [ChatMessage.ToolResult]
     * 后者由 [ToolResultAdapter] 在请求边界进一步拆出 media;此处只保证落盘形态。
     *
     * 在 [io.github.yeyi.agent.ReActAgent] 的 `memory.add(...)` 前调用,让 memory 始终持有 Local 引用,
     *
     * @return 原 message(若不含大 Data)或替换过大 Data 为 Local 的 message
     */
    internal suspend fun archive(message: ChatMessage): ChatMessage =
        archiveAdapter.archive(message)

    /**
     * Read 边:把 messages 渲染成"可直接喂 LLM"的形态 — 把 [MediaSource.Local]
     * 还原成 LLM 能消费的形式(Data / 占位文本等)，具体策略由实现选择。
     */
    internal suspend fun resolve(messages: List<ChatMessage>): List<ChatMessage> {
        return resolve(messages) { message, visible ->
            if (visible) resolveAdapter.resolve(message) else message.toTextMessage()
        }.run {
            toolResultAdapter.adapt(this)
        }
    }

    protected abstract suspend fun resolve(
        messages: List<ChatMessage>,
        resolver: Resolver
    ): List<ChatMessage>

    public fun interface Resolver {
        public suspend fun resolve(message: ChatMessage, visible: Boolean): ChatMessage
    }
}
