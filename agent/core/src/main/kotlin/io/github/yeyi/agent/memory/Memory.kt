package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.MediaSource

/**
 * 对话历史存储接口，Agent 在多轮对话中通过它读写历史消息。
 *
 * 实现者需保证线程安全：ReActAgent 可能并发调用多个 suspend 方法。
 *
 * SDK 内部使用 [RoundsBoundedMemory] 装饰此接口，实现历史轮次上限和摘要压缩。
 */
public interface Memory {
    /**
     * 本实例持有的 [MediaArchive],用于在请求边界把 [io.github.yeyi.agent.llm.MediaSource.Local]
     * 解析为 [io.github.yeyi.agent.llm.MediaSource.Data]。
     *
     * 必须由实现类持有实例(而非给默认值):装饰链需要逐层透传到最下层那一档,
     * 默认值会被重复注入。
     */
    public val mediaArchive: MediaArchive

    /**
     * 添加一条消息到历史。
     *
     * @param message 支持 [ChatMessage.User]、[ChatMessage.Assistant]、[ChatMessage.ToolResult] 等
     */
    public suspend fun add(message: ChatMessage)

    /**
     * 返回完整对话历史，按时间顺序排列。
     *
     * 返回的消息列表会被拼入 [io.github.yeyi.agent.llm.ChatRequest.messages] 传给 LLM。
     */
    public suspend fun history(): List<ChatMessage>

    /**
     * 用给定消息列表整体替换当前历史。
     *
     * 用于 Memory 实现内部的压缩/摘要重建场景；调用方不应随意调用。
     */
    public suspend fun rebuild(messages: List<ChatMessage>)
}

/**
 * 媒体字节 ↔ [MediaSource.Local] 引用的双向 IO 抽象。
 *
 * - [store]  : 把 [MediaSource.Data] 的字节存起来,返回一个 opaque [MediaSource.Local]
 *              引用(实现负责生成 ID 并保证后续 [resolve] 能找到)。
 * - [resolve]: 解析 [MediaSource.Local] 引用,还原为 [MediaSource.Data]。
 *
 * **只**承担 IO 能力,不决定"什么 Data 值得单独存文件"——归档阈值由持久化
 * [Memory] 实现在 `add()` 内决定。
 *
 * 方法声明为 `suspend` 是为了让实现内部用 `Mutex.withLock` 序列化并发 IO(与
 * [Memory] 的线程安全契约保持一致);同步实现可以直接 `return` 不挂起。
 *
 * 实现由 caller 注入(持久化场景)或 [Memory] 自己实例化(单 session 场景)。
 */
public interface MediaArchive {
    public suspend fun store(data: MediaSource.Data): MediaSource.Local
    public suspend fun resolve(local: MediaSource.Local): MediaSource.Data
}