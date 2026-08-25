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
 * [resolve] 因策略差异大,留给子类。
 */
public abstract class ModalityAdapter(protected val mediaArchive: MediaArchive) {
    private val freshDataState = FreshDataState()
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
        archiveAdapter.archive(message, freshDataState)

    /**
     * Read 边:把 messages 渲染成"可直接喂 LLM"的形态。
     *
     * 流程:先 [FreshDataState.consume] 拿到同轮 archive 写入的 fileId → Data 快照并清空,
     * 再调用子类 [resolve](messages, resolver) 处理每条消息(Local→Data 或占位),
     * 最后由 [ToolResultAdapter] 把含 media 的 [ChatMessage.ToolResult] 拆成
     * text-only ToolResult + 合成的 [ChatMessage.User]。
     *
     * @param messages memory 中的消息(按时间顺序)
     * @return 处理后的消息列表
     */
    internal suspend fun resolve(messages: List<ChatMessage>): List<ChatMessage> {
        val snapshot = freshDataState.consume()
        return resolve(messages) { message, visible ->
            if (visible) resolveAdapter.resolve(message, snapshot) else message.toTextMessage()
        }.run {
            toolResultAdapter.adapt(this)
        }
    }

    /**
     * 把 [messages] 渲染成"可直接喂 LLM"的形态。
     *
     * **契约**:实现必须对 [messages] 中**每一个**元素调用一次 [resolver],
     * 并按原顺序组成新列表返回 — 不允许跳过、合并或重排。
     *
     * @param messages memory 中的消息(按时间顺序)
     * @param resolver 每条消息的处理入口,封装了基于 `visible` 解码(Local→Data)/占位的处理
     * @return 处理后的消息列表,长度等于 [messages].size
     */
    protected abstract suspend fun resolve(
        messages: List<ChatMessage>,
        resolver: Resolver
    ): List<ChatMessage>

    public fun interface Resolver {
        /**
         * 对单条消息应用 resolve 策略。
         *
         * @param message 待处理的消息
         * @param visible 是否"可见":
         *  - `true`: 解码 Local→Data,供 LLM 直接消费 media
         *  - `false`: 不解码,把消息转为占位文本(让模型仍知道历史上有 media)
         * @return 处理后的消息
         */
        public suspend fun resolve(message: ChatMessage, visible: Boolean): ChatMessage
    }
}

/**
 * [ModalityAdapter] 的默认实现 — **策略选择**:"只保留最后一轮的图,
 * 其余全部转文本占位"。caller 可注入自定义 adapter 实现不同策略。
 *
 * "最后一轮" = 末条 [ChatMessage.User] 及其之后所有消息（含 ToolResult）。
 * - 当前 round 内 iter #2+ 都共享同一份末条 User（图/音/视频都保留）
 * - 跨 round 的历史消息全部转占位文本，不再每轮 base64 重传，避免 token 膨胀
 *
 * @param mediaArchive 由父类 [ModalityAdapter] 持有（本类不再加 `val` 重复声明）
 */
internal class DefaultModalityAdapter(mediaArchive: MediaArchive) : ModalityAdapter(mediaArchive) {

    /**
     * 渲染 messages: 保留最后一轮消息（图/音/视频），其余转文本占位。
     *
     * "最后一轮" = 末条 User 及其之后所有消息（含 ToolResult）。
     * Local 类型需要转 Data 才被支持，其余类型（Data/Http/FileId/Text）直接保留。
     */
    override suspend fun resolve(
        messages: List<ChatMessage>,
        resolver: Resolver
    ): List<ChatMessage> {
        val lastUserIdx = messages.indexOfLast { it is ChatMessage.User }
        return messages.mapIndexed { i, message ->
            if (i >= lastUserIdx) {
                resolver.resolve(message, true)
            } else {
                resolver.resolve(message, false)
            }
        }
    }
}

/**
 * archive→resolve 流水线上的 [MediaSource.Data] 缓冲。
 *
 * - [record]: archive 时 store 成功后写入 (fileId → 原始 Data)
 * - [consume]: resolve 周期开始时调用一次, snapshot + clear,
 *   保证 freshData 只在 archive 之后的下一个 resolve 命中
 *
 * 由 [ArchiveAdapter] 写入, [ModalityAdapter] 消费后传给 [ResolveAdapter] —
 * ResolveAdapter 不直接持有本类, 因为 consume 必须在周期开始时由 ModalityAdapter
 * 调一次, 内部多次 resolve 调用共享同一份 snapshot。
 */
internal class FreshDataState {
    private val map: MutableMap<String, MediaSource.Data> = mutableMapOf()

    fun record(fileId: String, data: MediaSource.Data) {
        map[fileId] = data
    }

    fun consume(): Map<String, MediaSource.Data> {
        val snapshot = map.toMap()
        map.clear()
        return snapshot
    }
}
