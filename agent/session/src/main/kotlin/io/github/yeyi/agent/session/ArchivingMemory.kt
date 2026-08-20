package io.github.yeyi.agent.session

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.Memory

/**
 * 归档外层装饰器 —— 写侧职责:把超过 1KB 的 [MediaSource.Data] 通过
 * [decorated] 的 [io.github.yeyi.agent.memory.MediaArchive] 转 [MediaSource.Local],
 * 减少 storage 体积。
 *
 * **不**持有 archive —— 通过 `Memory by decorated` delegate 拿到 `decorated.mediaArchive`。
 * archive 实体只注入最下层 (典型为持久化场景的 [JsonlBackedMemory] 或单 session 的
 * [io.github.yeyi.agent.memory.InMemoryMemory]),所有上层透明转发,避免重复注入。
 *
 * `history()` / `rebuild()` 透传 —— 下游返回的 message 已经是 archived 状态
 * (即:本装饰器写入时已转 Local, history() 读到一致)。
 *
 * 阈值 1024 = base64 长度 (≈ 768B 原始字节),设计依据:
 * [JsonlConversation.pageSizeThreshold] 默认 10KB 的 1/10,避免单图占满整 page。
 * 下沉到 [archiveIfLarge] 私有方法不暴露 caller —— 如未来真出现反馈需要调整,
 * 再考虑提升为构造参数。
 *
 * 仅 [ChatMessage.User] / [ChatMessage.ToolResult] 内的 Image/Audio/Video
 * parts 会被检查;System / Assistant 不含 media 透传。
 */
public class ArchivingMemory(
    private val decorated: Memory,
) : Memory by decorated {

    override suspend fun add(message: ChatMessage) {
        decorated.add(archiveLargeMedia(message))
    }

    private fun archiveLargeMedia(message: ChatMessage): ChatMessage = when (message) {
        is ChatMessage.User -> message.copy(
            parts = message.parts.map { archiveIfLarge(it) },
        )
        is ChatMessage.ToolResult -> message.copy(
            parts = message.parts.map { archiveIfLarge(it) },
        )
        is ChatMessage.System, is ChatMessage.Assistant -> message
    }

    private fun archiveIfLarge(part: ContentPart): ContentPart {
        val src: MediaSource? = when (part) {
            is ContentPart.Image -> part.source
            is ContentPart.Audio -> part.source
            is ContentPart.Video -> part.source
            is ContentPart.Text -> null
        }
        return if (src is MediaSource.Data && src.base64.length > 1024) {
            when (part) {
                is ContentPart.Image -> part.copy(source = decorated.mediaArchive.store(src))
                is ContentPart.Audio -> part.copy(source = decorated.mediaArchive.store(src))
                is ContentPart.Video -> part.copy(source = decorated.mediaArchive.store(src))
                is ContentPart.Text -> part
            }
        } else part
    }
}
