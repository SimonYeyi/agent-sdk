package io.github.yeyi.agent.modality

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive

internal class ResolveAdapter(private val mediaArchive: MediaArchive) {

    /**
     * @param freshData 同轮 archive 时填入的 fileId → 原 Data 快照。
     * 命中时直接返回, 不调 [MediaArchive.resolve]。
     */
    suspend fun resolve(
        messages: ChatMessage,
        freshData: Map<String, MediaSource.Data> = emptyMap(),
    ): ChatMessage = resolveMedia(messages, freshData)

    /**
     * 把 message 中的 Local 转 Data，其余类型直接保留。
     * Local 转 Data 时前置 `[local] fileId=xxx` 文本 part，
     * 让模型既看得到图（Data），也拿到 fileId 想用工具时传回。
     */
    private suspend fun resolveMedia(
        message: ChatMessage,
        freshData: Map<String, MediaSource.Data>,
    ): ChatMessage {
        return when (message) {
            is ChatMessage.User -> message.copy(parts = message.parts.flatMap {
                resolveLocal(it, freshData)
            })

            is ChatMessage.ToolResult -> message.copy(
                parts = message.parts.flatMap { resolveLocal(it, freshData) }
            )

            else -> message
        }
    }

    /**
     * Local → `[fileId 文本 part, resolve 后的 media part]`; 其余 (Text / Http /
     * Data / FileId) 原样单 part 返回。
     */
    private suspend fun resolveLocal(
        part: ContentPart,
        freshData: Map<String, MediaSource.Data>,
    ): List<ContentPart> {
        val local = when (part) {
            is ContentPart.Image -> part.source
            is ContentPart.Audio -> part.source
            is ContentPart.Video -> part.source
            is ContentPart.Text -> null
        } as? MediaSource.Local ?: return listOf(part)

        val data = freshData[local.fileId] ?: mediaArchive.resolve(local)
        @Suppress("KotlinConstantConditions")
        return listOf(
            ContentPart.Text("[local] fileId=${local.fileId}"),
            when (part) {
                is ContentPart.Image -> part.copy(source = data)
                is ContentPart.Audio -> part.copy(source = data)
                is ContentPart.Video -> part.copy(source = data)
                is ContentPart.Text -> part
            },
        )
    }
}
