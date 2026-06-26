package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.capability.CapabilityArguments
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable

/**
 * Subagent 的 arguments 定义。
 * Schema 固定为 `{ "task": { "type": "string", "description": "..." } }`。
 */
internal class SubagentArguments : CapabilityArguments<SubagentTask> {
    override val schema: String = """
        {
            "type": "object",
            "properties": {
                "task": {
                    "type": "string",
                    "description": "Task description to delegate"
                }
            },
            "required": ["task"]
        }
    """.trimIndent()
    override val serializer: KSerializer<SubagentTask> = SubagentTask.serializer()
}

/**
 * Task 输入类型，由 [SubagentArguments] 提供 schema 和 serializer。
 */
@Serializable
public data class SubagentTask(val task: String)
