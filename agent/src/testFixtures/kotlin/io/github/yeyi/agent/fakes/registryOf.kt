package io.github.yeyi.agent.fakes

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry

/**
 * Build a [ToolRegistry] pre-populated with the given [tools], in declaration order.
 * Test-only convenience that preserves the same "tools as positional vararg" feel.
 */
fun registryOf(vararg tools: Tool): ToolRegistry =
    ToolRegistry().apply { register(tools.toList()) }
