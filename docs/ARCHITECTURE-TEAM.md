# 多 Agent 编排架构

## 1. 概述

Team 模块把"多 Agent 协作"抽象为一个**任务看板 + 双层 Agent** 模型：

- **Boss** — 对用户，负责对话/决策/任务拆解/委派
- **Worker / Beast** — 不对用户，只对 Boss，执行具体任务
- **BulletinBoard** — Boss 与 Worker 之间的唯一信道
- **Pasture** — BulletinBoard 的执行侧，任务分发与汇报

## 2. 架构

```
                       ┌───────────────────────┐
                       │        用户            │
                       └───────────┬───────────┘
                                   │ run(input)
                                   ▼
                       ┌───────────────────────┐
                       │     BossAgent         │
                       │   innerAgent + 发布/取消 │
                       │   Tools: publish_task  │
                       │          cancel_task   │
                       └───────────┬───────────┘
                                   │ publishEvent
                                   ▼
       ┌────────────────────────────────────────────────────┐
       │                  BulletinBoard                     │
       │  SharedFlow<BulletinEvent>                         │
       │  ├── TaskAssignments (发布)                        │
       │  ├── Cancellation (取消)                           │
       │  └── TaskUpdate (进度汇报)                         │
       └─────────────────┬────────────────────┬─────────────┘
                         │ subscribe          │ progress
                         ▼                    ▼
              ┌──────────────────┐    ┌──────────────────┐
              │     Pasture      │    │      Boss        │
              │  DAG 调度 + 发布  │    │  任务状态追踪    │
              │  任务分发/级联   │    │  formatResult    │
              └────────┬─────────┘    └──────────┬───────┘
                       │                         │
                       ▼                         ▼
              ┌──────────────────┐    ┌──────────────────┐
              │   Beast/Horse    │    │   Beast/Ox       │
              │   单一专精        │    │   完整 Agent     │
              │   轻量级         │    │   全量工具       │
              └──────────────────┘    └──────────────────┘
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
| `cancel_task` | 取消指定任务 |

### 3.3 任务发布流程

**PublishTaskTool** 接受 LLM 的 `{tasks: [...]}` 调用，三遍解析：

1. **Pass 1 — 解析**：提取每个 task 的 ref/selection/task/context/depends_on
2. **Pass 2 — 解析依赖**：intra-call ref → UUID，cross-call task_id → 校验
3. **Pass 3 — 环检测 + 发布**：DFS 检测依赖环，然后 publish

### 3.4 任务状态追踪

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

### 5.3 级联机制

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
  ├─ Selection.Subagent    → Horse (Subagent.tools)
  │
  └─ 以上都失败 → Ox (完整 Agent)
```

**extractTools()**：通过正则匹配从 Skill 文本中提取工具名，自动绑定到 Horse。

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
    quickTools(quickToolRegistry)       // Boss 同步执行的轻量工具
    tools(delegatedToolRegistry)        // 委派给 Worker 的异步任务工具
    subagents(subagentRegistry)
    hook(HookPipeline(logging = true))
}
```

**构建时校验**：
- `Persona.role` 必须为 blank（框架保留字段，用于注入系统角色）
- 两次 `attach()` 抛 `IllegalStateException`