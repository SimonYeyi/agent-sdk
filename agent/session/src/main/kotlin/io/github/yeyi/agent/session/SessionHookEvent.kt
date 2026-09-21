package io.github.yeyi.agent.session

import io.github.yeyi.agent.hook.HookEvent

/**
 * Session 生命周期事件，扩展 [HookEvent]。
 */
public sealed interface SessionHookEvent : HookEvent {
    /** Session 被创建时触发。 */
    public data class Created(val session: Session) : SessionHookEvent

    /** Session 被标记为活跃时触发。 */
    public data class Start(val session: Session) : SessionHookEvent

    /** Session 被标记为非活跃时触发。 */
    public data class Stop(val session: Session) : SessionHookEvent

    /** Session 被删除时触发。只携带身份信息,不暴露 memory/conversation(已删除)。 */
    public data class Deleted(
        val accountId: String,
        val sessionId: String,
        val sessionName: String,
    ) : SessionHookEvent
}
