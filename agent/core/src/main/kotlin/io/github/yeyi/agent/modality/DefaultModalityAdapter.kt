package io.github.yeyi.agent.modality

import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ContentPart
import io.github.yeyi.agent.llm.MediaSource
import io.github.yeyi.agent.memory.MediaArchive
import io.github.yeyi.agent.llm.toTextMessage

internal class DefaultModalityAdapter : ModalityAdapter {

    override suspend fun adapt(
        messages: List<ChatMessage>,
        archive: MediaArchive,
    ): List<ChatMessage> {
        // 1. 末条 ToolResult 拆出 media (只对末条做, 跨 round 历史在 mapIndexed 阶段占位)
        val history = adaptToolResult(messages)
        // 2. 当前 round 是最后一条 User —— 可能是原始 User, 也可能是拆出来的合成 User。
        //    整个 round 内所有 iter 都保留原图, 跨 round 的历史 User 才占位。
        //    这样 iter #2+ 仍可重看图, 但旧 round 的图不再每轮重传, 避免 token 膨胀。
        val lastUserIdx = history.indexOfLast { it is ChatMessage.User }
        return history.mapIndexed { i, message ->
            if (i == lastUserIdx && message is ChatMessage.User) {
                resolveUserMedia(message, archive)
            } else {
                message.toTextMessage()
            }
        }
    }

    /**
     * 若末条是 [ChatMessage.ToolResult], 拆成 text-only ToolResult + 合成 User;
     * 否则原样返回 — 避免无谓的 toMutableList 拷贝。
     *
     * 只在请求边界做这个拆分, memory 始终保留原始多模态信息。
     */
    private fun adaptToolResult(messages: List<ChatMessage>): List<ChatMessage> {
        if (messages.lastOrNull() !is ChatMessage.ToolResult) return messages
        val mutable = messages.toMutableList()
        val lastIdx = mutable.lastIndex
        val modalityMessages = (mutable[lastIdx] as ChatMessage.ToolResult).adaptModality()
        mutable.removeAt(lastIdx)
        mutable.addAll(modalityMessages)
        return mutable
    }

    /**
     * 末条 User 的 [MediaSource.Local] 经 [MediaArchive.resolve] 转 [MediaSource.Data],
     * 同时前置一条 `[local] fileId=xxx` 文本 part — 模型既看得到图 (Data), 也拿到
     * 完整 fileId, 想用工具读/操作该文件时把整串传回即可。
     *
     * "末条 User" 的判断由 [adapt] 负责, 本方法只做 resolve + 引用注入。
     */
    private suspend fun resolveUserMedia(
        user: ChatMessage.User,
        archive: MediaArchive,
    ): ChatMessage.User =
        user.copy(parts = user.parts.flatMap { part -> resolveLocal(part, archive) })

    /**
     * Local → `[fileId 文本 part, resolve 后的 media part]`; 其余 (Text / Http /
     * Data / FileId) 原样单 part 返回。
     */
    @Suppress("KotlinConstantConditions")
    private suspend fun resolveLocal(part: ContentPart, archive: MediaArchive): List<ContentPart> {
        val local = when (part) {
            is ContentPart.Image -> part.source
            is ContentPart.Audio -> part.source
            is ContentPart.Video -> part.source
            is ContentPart.Text -> null
        } as? MediaSource.Local ?: return listOf(part)

        val data = archive.resolve(local)
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

/**
 * 把含 media 的 [ChatMessage.ToolResult] 拆成 text-only ToolResult + 合成的 User。
 * 从 `AgentExtensions.kt` 的 internal 扩展下沉为本文件内的 file-private extension
 * —— 只被 [DefaultModalityAdapter] 使用, 不再对外暴露。
 */
private fun ChatMessage.ToolResult.adaptModality(): List<ChatMessage> {
    val mediaParts = parts.filter { it !is ContentPart.Text }
    if (mediaParts.isEmpty()) return listOf(this)
    val textParts = parts.filterIsInstance<ContentPart.Text>()
    val textOnly = copy(parts = textParts)
    return listOf(
        textOnly,
        ChatMessage.User(parts = listOf(ContentPart.Text("[from $toolName]")) + mediaParts),
    )
}