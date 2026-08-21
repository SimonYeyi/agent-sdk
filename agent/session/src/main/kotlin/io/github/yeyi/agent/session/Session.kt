package io.github.yeyi.agent.session

import io.github.yeyi.agent.memory.Memory
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
public data class Session(
    val id: String,
    val accountId: String,
    val name: String,
    val createdAt: kotlinx.datetime.Instant,
    val lastActiveAt: kotlinx.datetime.Instant,
    @Transient private val _memory: Memory? = null,
    @Transient private val _conversation: Conversation? = null
) {
    val memory: Memory get() = _memory ?: throw IllegalStateException("Memory not found")
    val conversation: Conversation
        get() = _conversation ?: throw IllegalStateException("Conversation not found")
}
