package io.github.yeyi.agent.team

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

internal class CancelTaskTool(
    private val bulletinBoard: BulletinBoard,
) : Tool {
    override val name: String = "cancel_task"
    override val description: String = "Cancel a previously published task by its task_id."
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema("""
        {
          "type": "object",
          "properties": {
            "task_id": { "type": "string", "description": "The task_id returned by publish_task" }
          },
          "required": ["task_id"]
        }
    """.trimIndent())

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext,
    ): ToolExecutionResult {
        val taskId = arguments.jsonObject["task_id"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error("Missing 'task_id'")
        bulletinBoard.publishEvent(Cancellation(taskId))
        return ToolExecutionResult("Task $taskId cancellation requested")
    }
}