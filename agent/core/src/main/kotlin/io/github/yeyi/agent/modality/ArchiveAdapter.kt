package io.github.yeyi.agent.modality

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive

internal class ArchiveAdapter(private val mediaArchive: MediaArchive) {

    /**
     * Write 边:把 message 内"大的" [MediaSource.Data] 转 [MediaSource.Local]。
     * 只处理 [ChatMessage.User] 和 [ChatMessage.ToolResult],其余原样返回。
     * store 成功后把 fileId → 原始 Data 写入 [freshDataState],供同轮 resolve 命中。
     *
     * @param freshDataState 同轮 archive→resolve 共享缓冲,由 [ModalityAdapter] 持有并传入
     */
    suspend fun archive(message: ChatMessage, freshDataState: FreshDataState): ChatMessage =
        archiveLargeMedia(message, freshDataState)

    private suspend fun archiveLargeMedia(
        message: ChatMessage,
        freshDataState: FreshDataState
    ): ChatMessage = when (message) {
        is ChatMessage.User -> message.copy(
            parts = message.parts.map { archiveIfLarge(it, freshDataState) },
        )

        is ChatMessage.ToolResult -> message.copy(
            parts = message.parts.map { archiveIfLarge(it, freshDataState) },
        )

        else -> message
    }

    private suspend fun archiveIfLarge(
        part: ContentPart,
        freshDataState: FreshDataState
    ): ContentPart {
        val src: MediaSource? = when (part) {
            is ContentPart.Image -> part.source
            is ContentPart.Audio -> part.source
            is ContentPart.Video -> part.source
            is ContentPart.Text -> null
        }
        return if (src is MediaSource.Data && src.base64.length > ARCHIVE_THRESHOLD) {
            val local = mediaArchive.store(src)
            freshDataState.record(local.fileId, src)
            @Suppress("KotlinConstantConditions")
            when (part) {
                is ContentPart.Image -> part.copy(source = local)
                is ContentPart.Audio -> part.copy(source = local)
                is ContentPart.Video -> part.copy(source = local)
                is ContentPart.Text -> part
            }
        } else part
    }

    private companion object {
        /** base64 长度阈值 (≈ 768B 原始字节)。 */
        const val ARCHIVE_THRESHOLD: Int = 1024
    }
}
