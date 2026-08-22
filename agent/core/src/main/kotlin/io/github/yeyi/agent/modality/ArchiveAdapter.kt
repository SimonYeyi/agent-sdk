package io.github.yeyi.agent.modality

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive

internal class ArchiveAdapter(private val mediaArchive: MediaArchive) {

    suspend fun archive(message: ChatMessage): ChatMessage = archiveLargeMedia(message)

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
