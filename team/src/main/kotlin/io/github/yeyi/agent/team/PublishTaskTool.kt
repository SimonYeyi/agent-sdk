package io.github.yeyi.agent.team

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

internal data class NamedCapability(val name: String, val description: String)

internal class PublishTaskTool(
    private val bulletinBoard: BulletinBoard,
    private val capabilitiesByType: Map<String, List<NamedCapability>>,
) : Tool {

    override val name: String = "publish_task"

    override val description: String = buildString {
        val typeList = Selection.FACTORIES.keys.joinToString(" | ") { "'$it'" }
        append("""
            Use this to delegate one or more tasks that need external execution to workers (beasts).
            Pass an array of independent tasks to run them concurrently.
            For dependent tasks (one needs the result of another), make multiple calls — the second call will happen
            after the first task's result is available in the next turn.
            For chitchat or simple questions, just respond directly without calling this tool.

            Each task must specify a non-empty 'selections' array. Each selection is {type, name} where type is
            $typeList. A task can carry multiple selections to combine resources
            (e.g. one toolset + one standalone tool). At most one 'subagent' per task — multiple subagents in one
            task are rejected.

            Available workers (grouped by type):
        """.trimIndent())
        capabilitiesByType.forEach { (type, caps) ->
            append("\n  [").append(type).append("]")
            caps.forEach { cap -> append("\n    - ").append(cap.name).append(": ").append(cap.description) }
        }
    }

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        SCHEMA_JSON.replace("\$ENUM", Selection.FACTORIES.keys.joinToString("\", \"", "\"", "\""))
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext,
    ): ToolExecutionResult {
        val tasksArray = arguments.jsonObject["tasks"] as? JsonArray
            ?: return ToolExecutionResult.error("Missing 'tasks' array")
        if (tasksArray.isEmpty()) return ToolExecutionResult.error("'tasks' must not be empty")

        val summary = tasksArray.mapIndexed { idx, taskElement ->
            val obj = taskElement.jsonObject
            val task = obj["task"]?.jsonPrimitive?.content
                ?: return ToolExecutionResult.error("Missing 'task' in task #$idx")
            val context = obj["context"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val selectionsArray = obj["selections"] as? JsonArray
                ?: return ToolExecutionResult.error("Missing 'selections' array in task #$idx")
            if (selectionsArray.isEmpty()) return ToolExecutionResult.error("'selections' must not be empty in task #$idx")

            val selections = selectionsArray.mapIndexed { sIdx, selElement ->
                val selObj = selElement.jsonObject
                val type = selObj["type"]?.jsonPrimitive?.content
                    ?: return ToolExecutionResult.error("Missing 'type' in task #$idx selection #$sIdx")
                val name = selObj["name"]?.jsonPrimitive?.content
                    ?: return ToolExecutionResult.error("Missing 'name' in task #$idx selection #$sIdx")
                Selection.FACTORIES[type]?.invoke(name)
                    ?: return ToolExecutionResult.error("Unknown selection type '$type' in task #$idx selection #$sIdx — must be one of ${Selection.FACTORIES.keys}")
            }

            val taskId = UUID.randomUUID().toString()
            bulletinBoard.publishEvent(TaskAssignment(taskId, selections, task, context))

            val selStr = selections.joinToString("+") { sel ->
                val name = when (sel) {
                    is Selection.Skill -> sel.name
                    is Selection.Toolset -> sel.name
                    is Selection.Tool -> sel.name
                    is Selection.Subagent -> sel.name
                }
                "${sel.type}($name)"
            }
            "- $taskId → $selStr"
        }
        return ToolExecutionResult("Assigned ${summary.size} task(s):\n${summary.joinToString("\n")}")
    }

    private companion object {
        private val SCHEMA_JSON: String = """
            {
              "type": "object",
              "properties": {
                "tasks": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "selections": {
                        "type": "array",
                        "minItems": 1,
                        "items": {
                          "type": "object",
                          "properties": {
                            "type": {
                              "type": "string",
                              "enum": [${'$'}ENUM],
                              "description": "Type of the resource to load"
                            },
                            "name": {
                              "type": "string",
                              "description": "Name of the resource"
                            }
                          },
                          "required": ["type", "name"]
                        }
                      },
                      "task": {
                        "type": "string",
                        "description": "Core instruction for the worker"
                      },
                      "context": {
                        "type": "string",
                        "description": "Optional background info"
                      }
                    },
                    "required": ["selections", "task"]
                  }
                }
              },
              "required": ["tasks"]
            }
        """.trimIndent()
    }
}