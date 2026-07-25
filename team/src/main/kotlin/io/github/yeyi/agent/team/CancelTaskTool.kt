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
    override val description: String = """
        Cancels a running task. Cancellation propagates to all tasks that depends_on it
        (directly or transitively). Upstream tasks (the cancelled task's own dependencies)
        continue running.

        - You only need to cancel the ROOT of a dependency chain. Cascade handles the rest.
        - Cancelling an already-completed task is a safe no-op for that task, but cascade
          will still run for any downstream dependents.
        - Cascade propagates DOWNSTREAM ONLY. Cancelling B in A→B→C stops B and C; A continues.

        Call: cancel_task(task_id: str)
    """.trimIndent()
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
        return ToolExecutionResult("Cancellation request for task $taskId sent")
    }
}