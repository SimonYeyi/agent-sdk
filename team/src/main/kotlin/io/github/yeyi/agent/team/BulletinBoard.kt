package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance

// ===== 事件层级 =====

internal sealed interface BulletinEvent

internal sealed interface PublishEvent : BulletinEvent

internal sealed interface ProgressEvent : BulletinEvent

internal data class TaskAssignment(
    internal val taskId: String,
    internal val selections: List<Selection>,
    internal val task: String,
    internal val context: String? = null,
) : PublishEvent

internal data class Cancellation(
    internal val taskId: String,
) : PublishEvent

internal data class TaskUpdate(
    internal val taskId: String,
    internal val event: AgentEvent,
) : ProgressEvent

// ===== BulletinBoard =====

internal class BulletinBoard {
    private val _events = MutableSharedFlow<BulletinEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.SUSPEND
    )

    internal val events: SharedFlow<BulletinEvent> = _events.asSharedFlow()

    /**
     * 已注册的 collector 数量 — 测试用 `subscriptionCount.first {}` 同步等到自己的 collector 注册.
     * 生产代码 (BossAgent.attach / Pasture.observe) 改用 [kotlinx.coroutines.flow.onSubscription]
     * 回调 + CompletableDeferred 精确绑定"自己的 collector", 避免被第三方订阅满足.
     *
     * 直接转发 `_events.subscriptionCount`, 不持额外状态.
     */
    internal val subscriptionCount: StateFlow<Int> = _events.subscriptionCount

    internal val publishEvents: Flow<PublishEvent> = _events.filterIsInstance()

    internal val progressEvents: Flow<ProgressEvent> = _events.filterIsInstance()

    internal suspend fun publishEvent(event: PublishEvent) {
        _events.emit(event)
    }

    internal suspend fun progressEvent(event: ProgressEvent) {
        _events.emit(event)
    }
}
