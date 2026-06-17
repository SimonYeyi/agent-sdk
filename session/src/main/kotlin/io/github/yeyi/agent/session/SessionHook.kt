package io.github.yeyi.agent.session

import io.github.yeyi.agent.AgentHook
import io.github.yeyi.agent.log.Logging

/**
 * Session 生命周期回调。所有方法默认 no-op。
 *
 * 契约:
 * 1. Hook 抛异常不影响主流程,会被 SDK 吞掉并 log
 * 2. Hook 不应阻塞/sleep,可能影响 SessionManager 延迟
 *
 * 调用顺序:
 * - create  → onSessionCreated
 * - delete  → onSessionDeleted
 */
public interface SessionHook {
    public suspend fun onSessionCreated(session: Session) {}
    public suspend fun onSessionDeleted(accountId: String, sessionId: String) {}
}

internal object NoOpAgentHook : SessionHook

internal suspend inline fun <T> SessionHook.safeInvoke(
    crossinline action: suspend SessionHook.() -> T,
): T? {
    return try {
        action()
    } catch (t: kotlinx.coroutines.CancellationException) {
        throw t
    } catch (t: Throwable) {
        Logging.session()
            .warn("${this::class.simpleName} threw ${t::class.simpleName}: ${t.message}")
        null
    }
}
