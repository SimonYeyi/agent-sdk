package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance

// ===== 事件层级 =====

internal sealed interface BulletinEvent

internal sealed interface PublishEvent : BulletinEvent

internal sealed interface ProgressEvent : BulletinEvent

internal data class TaskAssignment(
    internal val taskId: String,
    internal val selection: Selection,
    internal val task: String,
    internal val context: String? = null,
    internal val dependsOn: List<String> = emptyList(),
)

internal data class TaskAssignments(
    internal val tasks: List<TaskAssignment>,
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

    internal val publishEvents: Flow<PublishEvent> = _events.filterIsInstance()

    internal val progressEvents: Flow<ProgressEvent> = _events.filterIsInstance()

    internal suspend fun publishEvent(event: PublishEvent) {
        _events.emit(event)
    }

    internal suspend fun progressEvent(event: ProgressEvent) {
        _events.emit(event)
    }
}
