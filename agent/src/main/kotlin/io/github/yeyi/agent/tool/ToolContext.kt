package io.github.yeyi.agent.tool

import java.util.UUID

public data class ToolContext(
    public val invocationId: String = UUID.randomUUID().toString(),
    public val metadata: Map<String, String> = emptyMap()
)
