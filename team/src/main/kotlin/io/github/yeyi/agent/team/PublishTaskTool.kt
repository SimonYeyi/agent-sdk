package io.github.yeyi.agent.team

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
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
            Pass an array of tasks. Each task declares a `ref` (your short symbolic name, unique within this call)
            and optionally lists references in `depends_on` to form a DAG. References in `depends_on` rules:
              - Same publish_task call: use `ref` (your symbolic name from another task in this same call).
                This is the ONLY option within one call because task_id is assigned AFTER publish completes.
              - Different publish_task call (cross-round): MUST use `task_id` (UUID). Refs are NOT stable
                across calls — the same ref name may be reused, mapped to different tasks, or absent.
                Always read the prior round's summary to get task_ids, then list them in `depends_on`.
              - Mixing `ref` (same call) and `task_id` (cross-call) in the same `depends_on` array is allowed.
            Tasks without dependencies run concurrently; a task with `depends_on` waits for all referenced
            tasks to finish, then runs with their final results prepended to its context. For a chain A→B,
            put both in one call:
              tasks=[{ref:"lookup",...}, {ref:"summary", depends_on:["lookup"],...}]
            Each publish_task call belongs to the current round — extend a chain across rounds by listing
            earlier task_ids in depends_on. The boss only sees a round summary when all tasks in the round
            complete — intermediate task results are not individually reported.
            For chitchat or simple questions, just respond directly without calling this tool.

            Each task must specify a non-empty 'selections' array. Each selection is {type, name} where type is
            $typeList. A task can carry multiple selections to combine resources
            (e.g. one toolset + one standalone tool). At most one 'subagent' per task — multiple subagents in one
            task are rejected.

            Capabilities available to worker (grouped by type):
        """.trimIndent())
        capabilitiesByType.forEach { (type, caps) ->
            append("\n  [").append(type).append("]")
            caps.forEach { cap -> append("\n    - ").append(cap.name).append(": ").append(cap.description) }
        }
    }

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        SCHEMA_JSON.replace($$"$ENUM", Selection.FACTORIES.keys.joinToString("\", \"", "\"", "\""))
    )

    // knownTaskIds: 历史 task_id (UUID, 程序生成) —— 校验跨批 task_id 引用 + 登记新 task_id.
    // 不让 BulletinBoard 背负业务关注 (BulletinBoard 是事件总线, 不该知道 TaskAssignments 的字段语义).
    // 单实例 (一个 BossAgent 一个 PublishTaskTool) + 锁保护即可.
    private val knownTaskIds: MutableSet<String> = mutableSetOf()
    private val knownTaskIdsLock: Mutex = Mutex()

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext,
    ): ToolExecutionResult {
        val tasksArray = arguments.jsonObject["tasks"] as? JsonArray
            ?: return ToolExecutionResult.error("Missing 'tasks' array")
        if (tasksArray.isEmpty()) return ToolExecutionResult.error("'tasks' must not be empty")

        // === Pass 1: 解析每个 task 到 TaskAssignment (taskId 暂存 ref, dependsOn 暂存原始字符串) ===
        val placeholder = tasksArray.mapIndexed { idx, el ->
            val obj = el.jsonObject
            val ref = obj.str("ref") ?: return ToolExecutionResult.error("Missing 'ref' in task #$idx")
            if (ref.isBlank()) return ToolExecutionResult.error("'ref' must not be empty in task #$idx")
            val task = obj.str("task") ?: return ToolExecutionResult.error("Missing 'task' in task '$ref'")
            val context = obj["context"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val selsArr = obj["selections"] as? JsonArray
                ?: return ToolExecutionResult.error("Missing 'selections' in task '$ref'")
            if (selsArr.isEmpty()) return ToolExecutionResult.error("'selections' must not be empty in task '$ref'")
            val selections = selsArr.map { selEl ->
                val selObj = selEl.jsonObject
                val type = selObj.str("type") ?: return ToolExecutionResult.error("Missing 'type' in selection of task '$ref'")
                val name = selObj.str("name") ?: return ToolExecutionResult.error("Missing 'name' in selection of task '$ref'")
                Selection.FACTORIES[type]?.invoke(name)
                    ?: return ToolExecutionResult.error("Unknown selection type '$type' in task '$ref'")
            }
            val deps = (obj["depends_on"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
            // 占位: taskId = ref (Pass 2 会 copy 成 UUID), dependsOn 暂存原始字符串列表 (Pass 2 会 copy 成 task_id 列表)
            TaskAssignment(taskId = ref, selections = selections, task = task, context = context, dependsOn = deps)
        }

        // intra-call ref 唯一性 (LLM 在同批内不能用相同 ref; 此时 taskId 还是 ref,直接 groupBy)
        val dupRefs = placeholder.groupBy { it.taskId }.filterValues { it.size > 1 }.keys
        if (dupRefs.isNotEmpty()) return ToolExecutionResult.error("Duplicate ref in this call: $dupRefs")

        // === Pass 2: 分配 UUID + 解析 depends_on (ref 本批 → UUID, task_id 跨批 → 直接复用 knownTaskIds) ===
        val refToUuid = placeholder.associate { it.taskId to UUID.randomUUID().toString() }
        val existing = knownTaskIdsLock.withLock { knownTaskIds.toSet() }
        val resolved = placeholder.map { t ->
            val resolvedDeps = t.dependsOn.map { dep ->
                refToUuid[dep] ?: dep.takeIf { it in existing }
                    ?: return ToolExecutionResult.error(
                        "Unknown depends_on reference '$dep' in task '${t.taskId}'. " +
                        "Within one publish_task call use `ref`; across calls use `task_id` (UUID) " +
                        "from the previous round's summary. Registered task_ids: ${existing.sorted()}"
                    )
            }
            // copy 终结: taskId 换 UUID, dependsOn 换解析后的 task_id 列表
            t.copy(taskId = refToUuid[t.taskId]!!, dependsOn = resolvedDeps)
        }

        // === Pass 3: intra-call 环检测 + publish + 登记 knownTaskIds ===
        detectIntraCycle(resolved)?.let { return ToolExecutionResult.error("Cycle detected involving task '$it'") }
        bulletinBoard.publishEvent(TaskAssignments(resolved))
        knownTaskIdsLock.withLock { resolved.forEach { knownTaskIds.add(it.taskId) } }

        // === Summary: 返回 task_id 给 LLM 后续轮次引用 ===
        val summary = resolved.map { task ->
            val selStr = task.selections.joinToString("+") { sel ->
                val name = when (sel) {
                    is Selection.Skill -> sel.name
                    is Selection.Toolset -> sel.name
                    is Selection.Tool -> sel.name
                    is Selection.Subagent -> sel.name
                }
                "${sel.type}($name)"
            }
            "- ${task.taskId} → $selStr"
        }
        return ToolExecutionResult("Assigned ${resolved.size} task(s):\n${summary.joinToString("\n")}")
    }

    private fun JsonObject.str(field: String): String? =
        this[field]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content

    // DFS 环检测,基于 task_id 依赖图(Pass 2 解析后的 dependsOn 全是 task_id).
    private fun detectIntraCycle(tasks: List<TaskAssignment>): String? {
        val graph: Map<String, List<String>> = tasks.associate { it.taskId to it.dependsOn }
        val visited = mutableSetOf<String>()
        val stack = mutableSetOf<String>()

        fun dfs(nodeId: String): String? {
            if (nodeId in stack) return nodeId
            if (nodeId in visited) return null
            visited += nodeId
            stack += nodeId
            for (dep in graph[nodeId] ?: emptyList()) {
                dfs(dep)?.let { return it }
            }
            stack -= nodeId
            return null
        }

        return tasks.asSequence().map { dfs(it.taskId) }.firstOrNull { it != null }
    }

    private companion object {
        private val SCHEMA_JSON: String = $$"""
            {
              "type": "object",
              "properties": {
                "tasks": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "ref": {
                        "type": "string",
                        "description": "Symbolic name you provide, unique within this publish_task call. Use it in depends_on to reference another task in the same call. Cross-call references use the task_id you receive in the previous round's summary."
                      },
                      "selections": {
                        "type": "array",
                        "minItems": 1,
                        "items": {
                          "type": "object",
                          "properties": {
                            "type": {
                              "type": "string",
                              "enum": [$ENUM],
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
                      },
                      "depends_on": {
                        "type": "array",
                        "items": { "type": "string" },
                        "description": "Optional list of references. Each entry is either a `ref` from this same call or a `task_id` from a previously published task. Empty/missing = no dependencies, dispatched immediately."
                      }
                    },
                    "required": ["ref", "selections", "task"]
                  }
                }
              },
              "required": ["tasks"]
            }
        """.trimIndent()
    }
}
