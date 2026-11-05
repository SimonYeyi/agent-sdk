package io.github.yeyi.agent.session

import io.github.yeyi.agent.memory.Memory
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
public data class Session(
    val id: String,
    val accountId: String,
    val name: String,
    val agent: AgentReference,
    val createdAt: Instant,
    val lastActiveAt: Instant,
    @Transient private val _memory: Memory? = null,
    @Transient private val _conversation: Conversation? = null
) {
    public val uniqueKey: String = accountId + "_" + id
    val memory: Memory get() = _memory ?: throw IllegalStateException("Memory not found")
    val conversation: Conversation
        get() = _conversation ?: throw IllegalStateException("Conversation not found")
}
