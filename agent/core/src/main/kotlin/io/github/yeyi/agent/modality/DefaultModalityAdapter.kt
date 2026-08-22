package io.github.yeyi.agent.modality

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.llm.toTextMessage

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
internal class DefaultModalityAdapter(mediaArchive: MediaArchive) :
    ModalityAdapter(mediaArchive) {

    /**
     * 渲染 messages: 保留最后一轮消息（图/音/视频），其余转文本占位。
     *
     * "最后一轮" = 末条 User 及其之后所有消息（含 ToolResult）。
     * Local 类型需要转 Data 才被支持，其余类型（Data/Http/FileId/Text）直接保留。
     */
    override suspend fun resolve(messages: List<ChatMessage>): List<ChatMessage> {
        val lastUserIdx = messages.indexOfLast { it is ChatMessage.User }
        return messages.mapIndexed { i, message ->
            if (i >= lastUserIdx) {
                resolveMedia(message)
            } else {
                message.toTextMessage()
            }
        }
    }

    /**
     * 把 message 中的 Local 转 Data，其余类型直接保留。
     * Local 转 Data 时前置 `[local] fileId=xxx` 文本 part，
     * 让模型既看得到图（Data），也拿到 fileId 想用工具时传回。
     */
    private suspend fun resolveMedia(message: ChatMessage): ChatMessage {
        return when (message) {
            is ChatMessage.User -> message.copy(parts = message.parts.flatMap { resolveLocal(it) })
            is ChatMessage.ToolResult -> message.copy(
                parts = message.parts.flatMap { resolveLocal(it) }
            )

            else -> message
        }
    }

    /**
     * Local → `[fileId 文本 part, resolve 后的 media part]`; 其余 (Text / Http /
     * Data / FileId) 原样单 part 返回。
     */
    private suspend fun resolveLocal(part: ContentPart): List<ContentPart> {
        val local = when (part) {
            is ContentPart.Image -> part.source
            is ContentPart.Audio -> part.source
            is ContentPart.Video -> part.source
            is ContentPart.Text -> null
        } as? MediaSource.Local ?: return listOf(part)

        val data = mediaArchive.resolve(local)
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
