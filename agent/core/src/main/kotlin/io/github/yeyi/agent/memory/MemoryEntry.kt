package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.time.Instant

/**
 * 记忆单元：LLM 线格式消息 [ChatMessage] + 审计/辅助元数据。
 *
 * [ChatMessage] 是 LLM 领域的线格式，承载的是"发往/来自模型的载荷"；
 * 而记忆层需要额外的审计/辅助信息（记录时间、消息 tag 等），
 * 若直接往 [ChatMessage] 上加字段会污染 LLM 领域模型。
 * [MemoryEntry] 在源头上套一层外壳：记忆层（[Memory]）统一操作 [MemoryEntry]，
 * 只在构造 [io.github.yeyi.agent.llm.ChatRequest] 前 map 剥离元数据得到纯 [ChatMessage]。
 *
 * @param message 纯 LLM 线格式消息
 * @param createAt 消息被记忆的时间（默认取创建时刻）
 * @param tags 辅助 tag（如 `"summary"`、`"steering"` 等），随条目持久化并在压缩重建时保留
 */
@Serializable
public data class MemoryEntry(
    public val message: ChatMessage,
    @Serializable(with = InstantEpochMillisSerializer::class)
    public val createAt: Instant = Instant.now(),
    public val tags: Set<String> = emptySet(),
)

/**
 * 便捷扩展：把消息加入记忆。
 *
 * 等价于 `memory.add(MemoryEntry(message, tags))`，用于避免各处手写 [MemoryEntry] 构造。
 *
 * @param memory 目标记忆
 * @param tags 随条目携带的辅助 tag（如 `"steering"`），见 [MemoryEntry.tags]
 */
internal suspend fun ChatMessage.addToMemory(memory: Memory, tags: Set<String> = emptySet()) {
    memory.add(MemoryEntry(this, tags = tags))
}

/**
 * [java.time.Instant] 的 epoch 毫秒序列化器。
 *
 * 设计为字段级 `@Serializable(with = ...)` 使用，避免为 java.time 引入额外依赖；
 * 持久化层（如 JsonlMemory）无需额外注册即可直接序列化 [MemoryEntry]。
 */
@Serializable
internal object InstantEpochMillisSerializer : KSerializer<Instant> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("java.time.Instant", PrimitiveKind.LONG)

    override fun serialize(encoder: Encoder, value: Instant) {
        encoder.encodeLong(value.toEpochMilli())
    }

    override fun deserialize(decoder: Decoder): Instant =
        Instant.ofEpochMilli(decoder.decodeLong())
}
