package io.github.yeyi.agent.session

import kotlinx.serialization.Serializable

@Serializable
public data class AgentReference(
    val name: String,
    val version: Int = 0  // 占位，默认 0
)
