package io.github.yeyi.agent.session

import io.github.yeyi.agent.hook.HookEvent

/**
 * Session 生命周期事件，扩展 [HookEvent]。
 */
public sealed interface SessionHookEvent : HookEvent {
    public data class Created(
        val session: Session
    ) : SessionHookEvent

    public data class Start(
        val session: Session
    ) : SessionHookEvent

    public data class Stop(
        val session: Session
    ) : SessionHookEvent

    public data class Deleted(
        val session: Session
    ) : SessionHookEvent
}
