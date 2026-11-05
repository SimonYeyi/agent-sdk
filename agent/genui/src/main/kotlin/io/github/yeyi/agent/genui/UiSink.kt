package io.github.yeyi.agent.genui

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 卡片旁路发射通道。
 *
 * 卡片 IR 完全绕过 agent 事件主干与记忆，只经此通道对外广播；消费方（demo）订阅 [cards] 渲染。
 *
 * 按实例注入（非全局单例）：作用域由持有者掌控——demo 为整个应用或单个会话创建实例，注入给工具
 * 并订阅 [cards]，多会话卡片流天然隔离，测试可各自持有实例互不污染。
 */
public class UiSink {

    /** replay=0（默认）不保留历史；小缓冲兜住活动订阅者暂未就绪的短暂窗口；缓冲积压满（64 张未消费）时 [emit] 挂起等待而非丢弃，无订阅者时直接丢弃。 */
    private val _cards = MutableSharedFlow<CardIr>(extraBufferCapacity = 64)

    /**
     * 外部监听入口：demo 直接 collect 渲染。
     *
     * 实时广播：仅订阅后发射的卡片可见，不保留历史；会话恢复依赖 demo 本地自持卡片记录，无需 sink 回放。
     */
    public val cards: SharedFlow<CardIr> = _cards.asSharedFlow()

    /** 挂起发射：仅 genui 模块内部收口调用（UiGenTool.execute 接线）；外部只有订阅权，无发卡权，杜绝伪造卡片流。正常场景立即返回，仅当缓冲积压满时挂起等待，保证活动订阅者卡片不丢。 */
    internal suspend fun emit(card: CardIr) {
        _cards.emit(card)
    }
}
