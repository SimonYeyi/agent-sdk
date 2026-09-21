package io.github.yeyi.agent.session

import io.github.yeyi.agent.memory.MemoryEntry

/**
 * 对话记录只读接口，通过 [Session.conversation] 获取。
 */
public interface Conversation {
    public companion object {
        /**
         * 不分页常量：传入 [history] 的 'page' 参数表示返回所有消息（从旧到新）。
         */
        public const val PAGE_ALL: Int = 0
    }

    /**
     * 获取对话历史
     *
     * @param page 分页页码，从1开始（1 为最新一页）。传 [PAGE_ALL] 则不分页，返回所有消息（从旧到新）。
     */
    public suspend fun history(page: Int): List<MemoryEntry>
}
