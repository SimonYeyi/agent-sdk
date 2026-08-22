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
internal class DefaultModalityAdapter(mediaArchive: MediaArchive) : ModalityAdapter(mediaArchive) {

    /**
     * 渲染 messages: 保留最后一轮消息（图/音/视频），其余转文本占位。
     *
     * "最后一轮" = 末条 User 及其之后所有消息（含 ToolResult）。
     * Local 类型需要转 Data 才被支持，其余类型（Data/Http/FileId/Text）直接保留。
     */
    override suspend fun resolve(
        messages: List<ChatMessage>,
        resolver: Resolver
    ): List<ChatMessage> {
        val lastUserIdx = messages.indexOfLast { it is ChatMessage.User }
        return messages.mapIndexed { i, message ->
            if (i >= lastUserIdx) {
                resolver.resolve(message, true)
            } else {
                resolver.resolve(message, false)
            }
        }
    }
}
