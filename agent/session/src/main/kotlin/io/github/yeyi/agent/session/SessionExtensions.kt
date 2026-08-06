package io.github.yeyi.agent.session

import io.github.yeyi.agent.AgentBuilder

/**
 * 将 [Session] 的记忆配置到 Agent。
 *
 * 等价于：
 * ```kotlin
 * agent {
 *     memory(mySession.memory)
 * }
 * ```
 *
 * @param session 会话实例，从中提取 [Session.memory]
 */
public fun AgentBuilder.memory(session: Session) {
    memory(session.memory)
}
