package io.github.yeyi.agent.core.tool

import java.util.UUID

public data class ToolContext(
    public val invocationId: String = UUID.randomUUID().toString(),
    public val metadata: Map<String, String> = emptyMap()
)
