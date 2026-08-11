# 多 Agent 编排架构

## 1. 概述

Team 模块把"多 Agent 协作"抽象为一个**任务看板 + 双层 Agent** 模型：

- **Boss** — 对用户，负责对话/决策/任务拆解/委派
- **Worker / Beast** — 不对用户，只对 Boss，执行具体任务
- **BulletinBoard** — Boss 与 Worker 之间的唯一信道
- **Pasture** — BulletinBoard 的执行侧，任务分发与汇报

## 2. 架构

```
                             ┌───────────────┐
                             │     用户       │
                             └───────┬───────┘
                                     │ run(input)
                                     ▼
                      ┌──────────────────────────────┐
                      │         BossAgent            │
                      │   innerAgent (ReActAgent)     │
                      │   Tools: publish_task         │
                      │          cancel_task          │
                      │          quickTools           │
                      │                              │
                      │   事件流 (输出):               │
                      │   run() → Flow<AgentEvent>    │
                      │   report → Flow<AgentEvent>   │
                      │   tasksState → Flow<TasksState│
                      └──────────────┬───────────────┘
                           publish  │  ▲ subscribe
                    (TaskAssignments,│  │ (TaskUpdate)
                     Cancellation)  │  │
                                     ▼  │
                      ┌──────────────────────────────┐
                      │        BulletinBoard          │
                      │  SharedFlow<BulletinEvent>    │
                      │  ├── TaskAssignments          │
                      │  ├── Cancellation             │
                      │  └── TaskUpdate               │
                      └──────────────┬───────────────┘
                           subscribe │  ▲ progress
                          (Publish)  │  │ (TaskUpdate)
                                     ▼  │
                      ┌──────────────────────────────┐
                      │         Pasture               │
                      │  DAG 调度 + 任务分发 + 级联    │
                      └──────────────┬───────────────┘
                                     │ dispatch
                                     ▼
                      ┌──────────────────────────────┐
                      │         Beast                 │
                      │  ├── Horse (单一专精/轻量级)   │
                      │  └── Ox    (完整 Agent/多轮)  │
                      └──────────────────────────────┘
```

## 3. BossAgent

### 3.1 内部状态机

```
              ┌──────────┐
              │  WAITING │◄──────────────────┐
              └────┬─────┘                   │
                   │ run()                   │
                   ▼                         │
              ┌──────────┐                   │
              │  RUNNING │─── final ────────►│
              └────┬─────┘                   │
                   │ report                  │
                   ▼                         │
              ┌──────────┐                   │
              │  RUNNING │─── final ────────►│
              └──────────┘                   │
```

BossAgent 通过 `Mutex` 控制并发安全，`handlePending` 是唯一决策点：
- `run()` 投递 user round 到 `pendingUserRounds`
- 终态 TaskUpdate 投递 result 到 `pendingResultEvents`
- 锁内原子决策：读 state + scope.launch

### 3.2 内置工具

| 工具 | 职责 |
|------|------|
| `publish_task` | 发布一批任务到 BulletinBoard，支持 DAG 依赖声明 |
| `cancel_task` | 取消指定任务，级联传播到下游依赖 |

**CancelTaskTool** 取消流程：
- 只取消指定任务本身，其上游依赖不受影响
- 级联传播：Pasture 自动将已取消任务的下游 PENDING 节点标记为 FAILED
- 幂等：已 DONE/FAILED/CANCELED 的任务不会被重复取消

### 3.3 系统汇报机制

BossAgent 的 innerAgent 使用一个特殊的 `[系统汇报]` 标记（`SYSTEM_REPORT_MARKER`）来区分"用户输入"和"Worker 完成汇报"：

- 当所有 Worker 任务进入终态时，BossAgent 通过 `formatTasksResult()` 将其聚合为带有 `[系统汇报]` 前缀的系统消息
- 该消息通过 `pendingResultEvents` 通道排队，由 `handlePending` 决策后注入 `innerAgent.run()` 作为续轮输入
- innerAgent 的 baseRole 中明确指示：看到 `[系统汇报]` 前缀时，视为内部状态更新，而非用户真实输入

**时态约束**：baseRole 强制要求 Boss 在汇报任务进度时使用**进行时**（如"正在为您调暗客厅灯"），不可使用完成时（如"已为您调暗"），因为任务实际是异步执行中。

### 3.4 任务发布流程

**PublishTaskTool** 接受 LLM 的 `{tasks: [...]}` 调用，三遍解析：

1. **Pass 1 — 解析**：提取每个 task 的 ref/selection/task/context/depends_on，校验 ref 唯一性
2. **Pass 2 — 解析依赖**：intra-call ref → UUID，cross-call task_id → 校验已知 task_id 池
3. **Pass 3 — 环检测 + 发布**：DFS 检测依赖环，然后 publish 到 BulletinBoard

**knownTaskIds 池**：PublishTaskTool 内部维护一个 `MutableSet<String>` 记录所有历史 task_id（UUID），用于跨轮次依赖校验。受 `Mutex` 保护。

### 3.5 任务状态追踪

```kotlin
data class TaskState(
    val taskId: String,
    val task: String,
    val events: MutableList<AgentEvent>,  // 事件历史
) {
    val terminal: Boolean  // Final 或 Failed 时 true
}

data class TasksState(
    val roundId: String,
    val userInput: String,
    val tasks: List<TaskState>,
    val terminal: Boolean,
    val latestChangedTask: TaskState,
)
```

BossAgent 通过 `tasksState` Flow 推送实时任务看板状态，调用方订阅即可获得 UI 更新。

### 3.6 其他接口

| 方法 | 类型 | 说明 |
|------|------|------|
| `report` | `Flow<AgentEvent>` | Hot SharedFlow，Worker 完成触发的续轮事件流，与 `run()` 互补 |
| `shutdown()` | `suspend` | 取消 `scope`，停止所有 boss/pasture 的后台任务 |
| `getAllTasks()` | `List<TaskState>` | 获取当前所有非终态任务快照 |

## 4. BulletinBoard

BulletinBoard 是 Boss 与 Worker 之间的**唯一信道**，基于 `SharedFlow` 实现：

```kotlin
internal class BulletinBoard {
    private val _events = MutableSharedFlow<BulletinEvent>()

    val events: SharedFlow<BulletinEvent>       // 统一流
    val publishEvents: Flow<PublishEvent>       // 发布事件
    val progressEvents: Flow<ProgressEvent>     // 进度事件
}
```

**事件类型**：

```
BulletinEvent (sealed interface)
├── PublishEvent
│   ├── TaskAssignments(tasks)  — 任务发布（含 DAG 依赖）
│   └── Cancellation(taskId)    — 任务取消
└── ProgressEvent
    └── TaskUpdate(taskId, event)  — 任务进度更新
```

## 5. Pasture (DAG 调度)

Pasture 是 BulletinBoard 的执行侧，负责任务分发、依赖解析、级联调度。

### 5.0 启动同步

Pasture 和 BossAgent 通过 `CompletableDeferred` + `onSubscription` 模式确保订阅就绪：

```kotlin
// BossAgentBuilder.build()
runBlocking {
    pasture.observe(bulletinBoard)  // 等待 Pasture collector 注册完成
    boss.attach(bulletinBoard)      // 等待 Boss collector 注册完成
}
```

`observe()` / `attach()` 内部用 `onSubscription { subscribed.complete(Unit) }` 配合 `CompletableDeferred` 同步等待 collector 注册，几 ms 完成，确保返回的 BossAgent 开箱即用。

### 5.1 DAG 节点状态

```kotlin
private enum class Status { PENDING, READY, RUNNING, DONE, FAILED, CANCELED }

private class DagNode(
    val assignment: TaskAssignment,
    var status: Status,
    var job: Job?,
    val upstreamResults: MutableMap<String, String>,
    var result: AgentEvent.Final?,
    var error: Throwable?,
)
```

### 5.2 调度流程

```
TaskAssignments 到达
  │
  ├─ 注册到 DAG (dag map)
  │
  ├─ 无依赖 (depends_on 为空) → Status.READY → dispatch
  │
  └─ 有依赖 → Status.PENDING → 等待上游完成
                                   │
                                   ▼
                            上游 Task DONE
                                   │
                                   ├─ 合并上游结果到 context
                                   ├─ Status.READY → dispatch
                                   └─ cascade → 推进下游 PENDING
```

### 5.3 Dispatch 上下文合并

`dispatch()` 在派发任务前合并上游结果到 context：

```kotlin
val mergedContext = buildString {
    node.upstreamResults.forEach { (id, result) ->
        append("[$id]\n$result\n\n")
    }
    node.assignment.context?.let { append(it) }
}.takeIf { it.isNotEmpty() }

val userInput = if (mergedContext == null) node.assignment.task
else "$mergedContext\n\n${node.assignment.task}"
```

Beast 在 `Dispatchers.IO` 上装配（`assemble()` 可能涉及文件 IO），然后在 `scope` 上启动协程执行。

### 5.4 终态处理

`handleTerminal()` 是幂等的——检查节点状态，若已 DONE/FAILED/CANCELED 则直接返回：

```kotlin
if (node.status == Status.DONE || node.status == Status.FAILED || node.status == Status.CANCELED) return
```

终态转换规则：
- `throwable == null` → `DONE`，使用缓存的 `AgentEvent.Final`
- `throwable is CancellationException` → `CANCELED`
- 其他异常 → `FAILED`

### 5.5 级联机制

每次 Task 进入终态时，`cascade()` 扫描所有依赖该 Task 的 PENDING 节点，尝试推进：

```
上游 Task DONE / FAILED / CANCELED
  │
  └─ cascade(taskId)
       │
       ├─ 找出所有 dependsOn 包含 taskId 的 PENDING 节点
       │
       └─ 对每个节点调用 tryAdvancePending()
             │
             ├─ 上游有 FAILED/CANCELED → 自身标 FAILED
             ├─ 所有上游 DONE → 合并结果 → 标 READY → dispatch
             └─ 还有上游未完成 → 继续等待
```

## 6. Beast (Worker Agent)

### 6.1 Beast 接口

```kotlin
internal interface Beast {
    suspend fun run(task: String, onEvent: suspend (AgentEvent) -> Unit)
}
```

### 6.2 两种实现

| 类型 | 适用场景 | 特点 |
|------|----------|------|
| **Horse** | 轻量任务 | 只装配选定的 Tool/Toolset/Skill/Subagent，无额外 LLM 开销 |
| **Ox** | 复杂任务 | 完整 Agent 副本，全量工具，适合需要多轮推理的复杂任务 |

### 6.3 Beast 装配 (BeastAssembler)

```
BeastAssembler.assemble(selection)
  │
  ├─ Selection.Tool        → Horse (单工具)
  ├─ Selection.Toolset     → Horse (工具集内所有工具)
  ├─ Selection.Skill       → Horse (Skill.load() + extractTools 提取工具)
  │                           └─ 要求 skill.standalone == true
  ├─ Selection.Subagent    → Horse (Subagent.tools)
  │                           └─ 要求 subagent.tools != null
  │
  └─ 以上都 catch IllegalStateException → Ox (完整 Agent)
```

**Horse → Ox 降级**：`assemble()` 通过 `try { assembleHorse(selection) } catch (_: IllegalStateException) { buildOx() }` 实现降级。当 Horse 装配因工具未找到、Selecion 非法等抛出 `IllegalStateException` 时，自动降级为 Ox（完整 Agent 副本）。这意味着即使某个 Selection 配置有误，Boss 的任务仍能执行，只是代价更高。

**extractTools()**：通过正则匹配从 Skill 文本中提取工具名，自动绑定到 Horse。匹配规则为 `\b<name>\b` 全词匹配，防止"fetcher"命中"fetch"。工具来源按优先级：toolRegistry → toolsetRegistry → skillRegistry.allTools()。

### 6.4 Selection 模型

```kotlin
internal sealed interface Selection {
    val type: String
    val name: String

    data class Skill(override val name: String) : Selection
    data class Toolset(override val name: String) : Selection
    data class Subagent(override val name: String) : Selection
    data class Tool(override val name: String) : Selection
}
```

## 7. BossAgentBuilder DSL

```kotlin
val boss = bossAgent {
    persona(Persona(role = "").personality("友好").domain("智能家居"))
    llmProvider(llmProvider)
    memory(memory, maxRounds = 40)
    maxIterations(20)
    quickTools(quickToolRegistry)       // Boss 同步执行的轻量工具
    tools(delegatedToolRegistry)        // 委派给 Worker 的异步任务工具
    skills(skillRegistry)               // 供 Ox 降级使用
    toolsets(toolsetRegistry)           // 供 Ox 降级使用
    subagents(subagentRegistry)         // 供 Ox 降级 + 静态委派
    hook(HookPipeline(logging = true))
    mcps(mcpRegistry)                   // 当前为 stub（仅编译验证）
}
```

**DSL 方法说明**：

| 方法 | 注入目标 | 说明 |
|------|----------|------|
| `quickTools()` | Boss 的 innerAgent 的 ToolRegistry | Boss 同步执行，耗时短，阻塞 ReAct 循环 |
| `tools()` | BeastAssembler 的 toolRegistry | 委派给 Worker 的异步任务，Worker 通过 Selection 选用 |
| `skills()` | BeastAssembler 的 skillRegistry | 供 Ox 降级时使用，Boss 本身不直接调用 |
| `toolsets()` | BeastAssembler 的 toolsetRegistry | 同上 |
| `subagents()` | BeastAssembler 的 subagentRegistry | 同上，也可通过 Selection.Subagent 静态委派 |
| `mcps()` | 当前 stub，仅 `@Suppress("UNUSED_PARAMETER")` | 编译验证通过，运行时无实际行为 |

**构建时校验**：
- `Persona.role` 必须为 blank（框架保留字段，用于注入系统角色）
- `llmProvider` 必须设置
- `memory` 必须设置
- 两次 `attach()` 抛 `IllegalStateException`
- 两次 `observe()` 抛 `IllegalStateException`