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
     * 当前活跃订阅者计数.
     *
     * MutableSharedFlow 在 replay=0 时,无订阅者调用 emit 会直接丢弃(replayCache 也没缓存)
     * — 新订阅者不会收到订阅前 emit 的事件.
     *
     * BossAgent 用此在 init 阻塞等待订阅就绪,确保调用 publishEvent 时值能正确派发.
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
