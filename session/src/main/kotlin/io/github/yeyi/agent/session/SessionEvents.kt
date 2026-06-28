package io.github.yeyi.agent.session

import io.github.yeyi.agent.AgentContext
import io.github.yeyi.agent.hook.Event
import kotlin.reflect.KClass

/**
 * Session 生命周期事件，扩展 [Event]。
 */
public data class OnSessionCreated(
    val session: Session
) : Event

/**
 * Session 删除事件，扩展 [Event]。
 */
public data class OnSessionDeleted(
    val accountId: String,
    val sessionId: String
) : Event

/**
 * Session 生命周期所有事件的集合。
 */
public object SessionEvents {
    public val ALL: Set<KClass<out Event>> = setOf(
        OnSessionCreated::class,
        OnSessionDeleted::class
    )
}