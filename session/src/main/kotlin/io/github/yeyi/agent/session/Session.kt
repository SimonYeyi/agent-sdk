package io.github.yeyi.agent.session

import io.github.yeyi.agent.memory.Memory
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

@Serializable
public data class Session(
    val id: String,
    val userId: String,
    val name: String,
    val createdAt: kotlinx.datetime.Instant,
    val lastActiveAt: kotlinx.datetime.Instant,
    @Transient private val _memory: Memory? = null
) {
    val memory: Memory get() = _memory!!
}