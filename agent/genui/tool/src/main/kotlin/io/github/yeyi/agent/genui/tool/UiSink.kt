package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.genui.ir.CardIr
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * 卡片旁路发射通道。
 *
 * 卡片 IR 完全绕过 agent 事件主干与记忆，只经此通道对外广播；消费方（demo）订阅 [cards] 渲染。
 *
 * 按实例注入（非全局单例）：作用域由持有者掌控——demo 为每个 agent 创建实例，登记进
 * [UiSinkRegistry] 并订阅 [cards]，多 agent 卡片流天然隔离，测试可各自持有实例互不污染。
 */
public class UiSink {

    /**
     * replay=0（默认）不保留历史，无订阅者时直接丢弃；
     * 缓冲 1 张兜住活动订阅者暂未就绪的短暂窗口；积压超 1 张时丢最旧保最新（[BufferOverflow.DROP_OLDEST]）。
     * 卡片产生频率极低（每回合产一张），该容量已充分覆盖正常调度窗口；[emit] 恒不挂起，绝不阻塞工具执行与 agent 回合。
     */
    private val _cards = MutableSharedFlow<CardIr>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    /**
     * 外部监听入口：demo 直接 collect 渲染。
     *
     * 实时广播：仅订阅后发射的卡片可见，不保留历史；会话恢复依赖 demo 本地自持卡片记录，无需 sink 回放。
     */
    public val cards: SharedFlow<CardIr> = _cards.asSharedFlow()

    /** 非挂起发射：仅 genui 模块内部收口调用（UiGenTool.execute 接线）；外部只有订阅权，无发卡权，杜绝伪造卡片流。立即返回，无订阅者直接丢弃，订阅者消费滞后时溢出丢弃旧卡，绝不阻塞 agent 回合。 */
    internal suspend fun emit(card: CardIr) {
        _cards.emit(card)
    }
}
