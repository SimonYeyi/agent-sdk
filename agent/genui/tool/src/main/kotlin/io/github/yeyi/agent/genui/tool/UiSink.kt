package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.genui.ir.CardIr
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

    /** replay=0（默认）不保留历史；小缓冲兜住活动订阅者暂未就绪的短暂窗口；缓冲积压满（64 张未消费）时 [emit] 挂起等待而非丢弃。 */
    private val _cards = MutableSharedFlow<CardIr>(extraBufferCapacity = 64)

    /**
     * 外部监听入口：demo 直接 collect 渲染。
     *
     * 实时广播：仅订阅后发射的卡片可见，不保留历史；会话恢复依赖 demo 本地自持卡片记录，无需 sink 回放。
     */
    public val cards: SharedFlow<CardIr> = _cards.asSharedFlow()

    /**
     * 挂起发射：仅 genui 模块内部收口调用（UiGenTool.execute 接线）；外部只有订阅权，无发卡权，杜绝伪造卡片流。
     *
     * 发射前校验订阅数：没有订阅者说明消费端没接上（装配缺失或页面已销毁），此时抛 [IllegalStateException]
     * fail-fast 暴露，绝不静默丢弃卡片；异常向上传播由 registry 统一转错误回执，[UiGenTool.finalizable]
     * 收到错误回执后拒绝终结，模型下一轮感知失败并重试。
     */
    internal suspend fun emit(card: CardIr) {
        check(_cards.subscriptionCount.value > 0) {
            "UiSink has no subscriber, cannot emit card [${card.instanceId}]. " +
                "Card-emitting agent must keep a live collector on UiSink.cards."
        }
        _cards.emit(card)
    }
}
