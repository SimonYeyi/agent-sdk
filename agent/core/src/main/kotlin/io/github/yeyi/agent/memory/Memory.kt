package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.MediaSource

/**
 * 对话历史存储接口，Agent 在多轮对话中通过它读写历史消息。
 *
 * 实现者需保证线程安全：ReActAgent 可能并发调用多个 suspend 方法。
 *
 * SDK 内部使用 [RoundsBoundedMemory] 装饰此接口，实现历史轮次上限和摘要压缩。
 *
 * 记忆层统一操作 [MemoryEntry]（消息 + 元数据），与 LLM 线格式 [io.github.yeyi.agent.llm.ChatMessage]
 * 保持领域边界：调用方在构造 [io.github.yeyi.agent.llm.ChatRequest] 前自行
 * `map { it.message }` 剥离元数据。
 */
public interface Memory {
    /**
     * 添加一条记忆条目到历史。
     *
     * @param entry 承载 [io.github.yeyi.agent.llm.ChatMessage.User]、
     *   [io.github.yeyi.agent.llm.ChatMessage.Assistant]、
     *   [io.github.yeyi.agent.llm.ChatMessage.ToolResult] 等的记忆单元
     */
    public suspend fun add(entry: MemoryEntry)

    /**
     * 返回完整对话历史，按时间顺序排列。
     *
     * 返回的记忆条目需在拼入 [io.github.yeyi.agent.llm.ChatRequest.messages] 前
     * 剥离元数据（`map { it.message }`）。
     */
    public suspend fun history(): List<MemoryEntry>

    /**
     * 用给定记忆条目列表整体替换当前历史。
     *
     * 用于 Memory 实现内部的压缩/摘要重建场景；调用方不应随意调用。
     */
    public suspend fun rebuild(entries: List<MemoryEntry>)
}

/**
 * 媒体归档能力接口 — **可选能力**,与核心 [Memory] 契约正交。
 *
 * 实现本接口的 [Memory] 子类声明自己持有 [MediaArchive],供
 * [io.github.yeyi.agent.AgentBuilder] 在构造多模态适配器时做能力检测
 * (`memory as? MediaArchivable`)。
 *
 * 不需要归档的 Memory 子类**不实现**本接口,避免被核心契约强制提供虚假的归档实例。
 */
public interface MediaArchivable {
    public val mediaArchive: MediaArchive
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