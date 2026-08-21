package io.github.yeyi.agent.modality

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.llm.toTextMessage

/**
 * [ModalityAdapter] 的默认实现 — **策略选择**:"只保留最后一轮 User 的 media,
 * 其余全部转文本占位"。caller 可注入自定义 adapter 实现不同策略。
 *
 * "最后一轮" 指 messages 中最后一条 [ChatMessage.User](由 [resolve] 内部判定
 * `indexOfLast`):
 * - 当前 round 内 iter #2+ 都共享同一份末条 User(模型反复看图选工具),media 都保留
 * - 跨 round 的历史 User 全部转占位文本,不再每轮 base64 重传,避免 token 膨胀
 *
 * @param mediaArchive 由父类 [ModalityAdapter] 持有(本类不再加 `val` 重复声明)
 */
internal class DefaultModalityAdapter(mediaArchive: MediaArchive) :
    ModalityAdapter(mediaArchive) {

    /**
     * 渲染 messages: 末条 User 的 Local 转 Data + 前置 `[local] fileId=xxx` 引用文本;
     * 其他消息(含跨 round 历史 User / System / Assistant / ToolResult)统一
     * [io.github.yeyi.agent.llm.toTextMessage] 转文本占位。
     *
     * 调用前置条件: 末条 [ChatMessage.ToolResult] 的 media 已由
     * [ToolResultModalityAdapter.adapt] 拆出(此时末条 User 可能是合成 User)。
     */
    override suspend fun resolve(messages: List<ChatMessage>): List<ChatMessage> {
        // 只还原最后一条 User — 整个 round 内 iter #2+ 共享它,
        // 跨 round 历史 User 转占位, 避免 token 膨胀。
        val lastUserIdx = messages.indexOfLast { it is ChatMessage.User }
        return messages.mapIndexed { i, message ->
            if (i == lastUserIdx && message is ChatMessage.User) {
                resolveUserMedia(message)
            } else {
                message.toTextMessage()
            }
        }
    }

    /**
     * 末条 User 的 [MediaSource.Local] 经 [MediaArchive.resolve] 转 [MediaSource.Data],
     * 同时前置一条 `[local] fileId=xxx` 文本 part — 模型既看得到图 (Data), 也拿到
     * 完整 fileId, 想用工具读/操作该文件时把整串传回即可。
     */
    private suspend fun resolveUserMedia(user: ChatMessage.User): ChatMessage.User =
        user.copy(parts = user.parts.flatMap { part -> resolveLocal(part) })

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
