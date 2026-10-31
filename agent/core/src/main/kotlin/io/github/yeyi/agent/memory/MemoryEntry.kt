package io.github.yeyi.agent.memory

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
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
 * 只在构造 [io.github.yeyi.agent.llm.ChatRequest] 前 map 成可直接发给模型的
 * [ChatMessage] —— 来源标签不是被剥离，而是以 `"label: content"` 前缀显式编码进
 * 上行消息正文（见 [toChatMessage]），使模型能感知消息来源。
 *
 * @param message 纯 LLM 线格式消息
 * @param createAt 消息被记忆的时间（默认取创建时刻）
 * @param label 消息来源标签（单值，如 `"report"`、`"summary"`、`"steering"`）：
 *   随条目持久化并在压缩重建时保留；UI 可据此分类渲染，
 *   构建请求时对带 label 的上行消息（User / System）拼 `"label: content"` 前缀使模型可见。
 */
@Serializable
public data class MemoryEntry(
    public val message: ChatMessage,
    @Serializable(with = InstantEpochMillisSerializer::class)
    public val createAt: Instant = Instant.now(),
    public val label: String? = null,
)

/**
 * 便捷扩展：把消息加入记忆。
 *
 * 等价于 `memory.add(MemoryEntry(message, label))`，用于避免各处手写 [MemoryEntry] 构造。
 *
 * @param memory 目标记忆
 * @param label 随条目携带的消息来源标签（如 `"steering"`），见 [MemoryEntry.label]
 */
internal suspend fun ChatMessage.addToMemory(memory: Memory, label: String? = null) {
    memory.add(MemoryEntry(this, label = label))
}

/**
 * 把记忆条目渲染成发给模型的线格式消息 —— 带 label 的**上行**消息拼 `"label: content"` 前缀，
 * 使模型能感知这条消息的来源（见 [MemoryEntry.label]）。
 *
 * 与 [addToMemory] 互为镜像：写进去时带 label，渲染出来时把 label 显给模型。
 * 持久化的 content 保持原样，前缀只在渲染时叠加。
 *
 * 下行消息（Assistant / ToolResult）不带 label，原样返回。
 */
internal fun MemoryEntry.toChatMessage(): ChatMessage {
    val label = this.label ?: return this.message
    return when (val message = this.message) {
        // User 是 parts 列表，只给首个 Text 块拼，媒体块不被污染
        is ChatMessage.User -> message.copy(parts = message.parts.mapIndexed { i, part ->
            if (i == 0 && part is ContentPart.Text) ContentPart.Text("$label: ${part.text}") else part
        })

        is ChatMessage.System -> message.copy(content = "$label: ${message.content}")
        else -> message
    }
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
