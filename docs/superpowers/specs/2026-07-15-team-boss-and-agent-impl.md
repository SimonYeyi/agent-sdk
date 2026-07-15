# Team 模块 — BossAgent + TeamAgent 实现

> 日期: 2026-07-15 · 状态: **Draft** (待用户审阅)
> 来源: 主 spec [`2026-07-13-team-module-design.md`](./2026-07-13-team-module-design.md) § 4.5 + § 4.7
> 范围: BossAgent 内部实现 + TeamAgent 公开容器(TeamAgentBuilder DSL 留在主 spec § 4.7)

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 关联组件 | BulletinBoard / Pasture / Beast / Selection / TaskAssignment / TaskUpdate / PublishTaskTool / CancelTaskTool(均见主 spec § 4.1-4.6) |
| 状态机定义 | 主 spec § 7 — BossAgent 的状态表、转换规则、ProgressEvent 合并策略 |
| 公开 API | `TeamAgent` (主 spec § 4.7 含 TeamAgentBuilder DSL) |
| 内部 API | `BossAgent` / `BossState` / `TaskState` |

---

## 1. BossAgent 内部实现

Boss = 包装 `ReActAgent` + 状态机 + 异步 ProgressEvent 合并。
不重写 ReAct 循环 — innerAgent 跑 ReAct, BossAgent 在外层协调:
- 注册 `PublishTaskTool` / `CancelTaskTool` 到 innerAgent 的 toolRegistry
- 订阅 `BulletinBoard` 的 `TaskUpdate` 合并到 input
- 维护 `tasks` 追踪所有派出的任务
- 状态机决定什么时候触发新的 `innerAgent.run()`

**状态机的输入/输出与转换规则见主 spec § 7**。本节只列实现。

```kotlin
package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Boss state machine. 详见主 spec § 7.
 *
 * - [WAITING]: idle, 等待外部输入 (用户或终态 TaskUpdate)
 * - [RUNNING]: innerAgent.run() 正在执行
 * - [INPUTTING]: 用户有未提交的输入在 pending (UI 状态)
 * - [COLLECTING]: 等待其他任务完成（等 1s 合并期间），用户输入会缓存
 *
 * 终态事件 (Final/Failed) 处理:
 * - WAITING → 有其他活跃任务则进入 COLLECTING 等 1s 合并，否则立即触发
 * - RUNNING → 缓存，run() 结束后检查是否需要进入 COLLECTING
 * - INPUTTING → 缓存，等用户调用 run() 时一起处理
 * - COLLECTING → 等待 1s 期间收集更多终态事件
 *
 * 非终态事件: 只更新内部 TaskState，不触发模型
 *
 * 公开原因: TeamAgent.state: StateFlow<BossState> 需对外暴露,UI 才能订阅 boss 当前状态.
 */
public enum class BossState { WAITING, RUNNING, INPUTTING, COLLECTING }

internal class TaskState(
    internal val selections: List<Selection>,
    internal val task: String,
    internal val events: MutableList<AgentEvent> = mutableListOf(),
) {
    internal val terminal: Boolean
        get() = events.lastOrNull() is AgentEvent.Final || events.lastOrNull() is AgentEvent.Failed
}

/**
 * Boss = 包装 [ReActAgent] + 状态机 + 异步 ProgressEvent 合并.
 *
 * 不重写 ReAct 循环. innerAgent 跑 ReAct, BossAgent 在外层协调:
 * - 注册 [PublishTaskTool] / [CancelTaskTool] 到 innerAgent 的 toolRegistry
 * - 订阅 [BulletinBoard] 的 [TaskUpdate] 合并到 input
 * - 维护 [tasks] 追踪所有派出的任务
 * - 状态机决定什么时候触发新的 innerAgent.run()
 *
 * 详见主 spec § 4.5.
 */
internal class BossAgent internal constructor(
    private val innerAgent: Agent,
    private val bulletinBoard: BulletinBoard,
    private val scope: CoroutineScope,
) : Agent {

    private val _state = MutableStateFlow(BossState.WAITING)
    internal val state: StateFlow<BossState> = _state.asStateFlow()

    // 任务追踪 — BossAgent 派活后通过订阅 [BulletinBoard.publishEvents] 写入,
    // 收 [TaskUpdate] 时反查 selections / 拼进度文本.
    private val tasks: MutableMap<String, TaskState> = mutableMapOf()
    private val tasksLock: Mutex = Mutex()

    // 待合并的终态 TaskUpdate (Final / Failed)
    private val pendingTerminalUpdates: Channel<TaskUpdate> = Channel(capacity = Channel.UNLIMITED)
    // 待合并的用户输入（RUNNING 或 COLLECTING 状态时设置）
    private var pendingUserInput: String? = null

    init {
        // 订阅 BulletinBoard: 1 team 1 boss, 自己的 publishEvents / progressEvents 全是本 boss 的,
        // 直接订阅即可, 不需按名称过滤.
        scope.launch {
            bulletinBoard.publishEvents
                .filterIsInstance<TaskAssignment>()
                .collect { assignment ->
                    tasksLock.withLock {
                        tasks[assignment.taskId] = TaskState(
                            selections = assignment.selections,
                            task = assignment.task,
                        )
                    }
                }
        }
        scope.launch {
            bulletinBoard.progressEvents
                .collect { handleTaskUpdate(it as TaskUpdate) }
        }
    }

    private fun run(input: String, emitEvents: Boolean = true): kotlinx.coroutines.flow.Flow<AgentEvent> =
        kotlinx.coroutines.flow.flow {
            _state.value = BossState.RUNNING
            try {
                val merged = drainPendingWith(input)
                merged?.let { innerAgent.run(it).collect { e -> if (emitEvents) emit(e) } }
            } finally {
                _state.value = BossState.WAITING
                handlePendingTerminals()
            }
        }

    override fun run(input: String): Flow<AgentEvent> {
        // RUNNING 或 COLLECTING 状态时，用户输入缓存
        if (_state.value == BossState.RUNNING || _state.value == BossState.COLLECTING) {
            pendingUserInput = input
            return emptyFlow()
        }
        return run(input, emitEvents = true)
    }
    override fun runStream(input: String): Flow<AgentEvent> = run(input)

    /** 处理 pending 的终态事件和用户输入 */
    private fun handlePendingTerminals() {
        val update = pendingTerminalUpdates.tryReceive().getOrNull()
        if (update != null) {
            pendingTerminalUpdates.trySend(update) // 放回去
        }

        // 有终态事件或有 pendingUserInput，都需要处理
        val hasPendingInput = pendingUserInput != null
        if (update == null && !hasPendingInput) return

        scope.launch {
            if (update != null && hasActiveTasks()) {
                // 有终态事件且有活跃任务，进入 COLLECTING 状态，等 1s 后合并
                waitForOtherTasks()
            }
            val userInput = pendingUserInput
            pendingUserInput = null
            drainPendingWith(userInput)?.let { run(it, emitEvents = false) }
        }
    }

    private suspend fun handleTaskUpdate(update: TaskUpdate) {
        tasksLock.withLock {
            val task = tasks[update.taskId] ?: return
            task.events += update.event
            if (!task.terminal) return  // 非终态，只更新状态
        }

        // 终态事件：根据状态决定处理时机
        when (_state.value) {
            BossState.WAITING -> {
                pendingTerminalUpdates.trySend(update)
                handlePendingTerminals()
            }
            BossState.RUNNING, BossState.INPUTTING, BossState.COLLECTING -> {
                // RUNNING: 缓存，run() 结束后检查
                // INPUTTING: 缓存，等用户输入时一起处理
                // COLLECTING: 缓存，继续收集更多终态事件
                pendingTerminalUpdates.trySend(update)
            }
        }
    }

    /** 检查是否还有非终态的活跃任务 */
    private suspend fun hasActiveTasks(): Boolean = tasksLock.withLock {
        tasks.values.any { !it.terminal }
    }

    /** 等待其他任务变为终态，超时 1s */
    private suspend fun waitForOtherTasks() {
        _state.value = BossState.COLLECTING
        val deadline = System.currentTimeMillis() + 1000
        while (System.currentTimeMillis() < deadline) {
            if (!hasActiveTasks()) break
            delay(50)
        }
    }

    /** 取出所有终态 TaskUpdate，与可选 input 合并，返回 null 表示无内容 */
    private fun drainPendingWith(input: String?): String? {
        val updates = mutableListOf<TaskUpdate>()
        while (true) {
            val next = pendingTerminalUpdates.tryReceive().getOrNull() ?: break
            updates.add(next)
        }
        if (updates.isEmpty() && input == null) return null

        return buildString {
            input?.let {
                append(it)
                if (updates.isNotEmpty()) append("\n")
            }
            if (updates.isNotEmpty()) {
                append(formatTaskResults(updates))
            }
        }
    }

    /** 格式化任务结果列表 */
    private fun formatTaskResults(updates: List<TaskUpdate>): String = buildString {
        append("[Task Result]")
        updates.forEach { update ->
            append("\n${update.taskId}: ${update.event}")
        }
    }

    // ===== UI 层调用 — 进入/退出 INPUTTING 状态 =====

    /** UI 通知 boss: 用户开始/结束打字 */
    internal fun inputting(active: Boolean) { _state.value = if (active) BossState.INPUTTING else BossState.WAITING }
}
```

**说明**:
- `formatTaskResults` 把终态 TaskUpdate 列表格式化为统一文本，label + 任务列表。
- `run(input, emitEvents)` 是统一的运行方法：`emitEvents=true` 用于外部调用（返回 Flow 给 UI），`emitEvents=false` 用于内部触发（不返回 Flow）。
- `INPUTTING` 状态由 UI 层通过 `inputting(true/false)` 控制，boss 框架不感知 UI 细节 — **预留状态**

---

## 2. TeamAgent 公开容器

TeamAgent = 唯一对外 API 表面。内部 boss / pasture / bulletinBoard 全部私有, 外部仅通过 `team.run` / `team.runStream` / `team.state` / `team.shutdown` 与之交互。

构造由 `TeamAgentBuilder` 完成 — DSL 与配置项见主 spec § 4.7, 此处只列 [TeamAgent] 自身实现。

```kotlin
package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * TeamAgent 容器 — 持有 [BossAgent], 拥有 team 级协程作用域.
 * BulletinBoard 和 Pasture 都由 [TeamAgentBuilder] 内部创建, 直接注入给 boss 做事件桥接 ——
 * team 自身不持有引用.
 *
 * 自身实现 [Agent] 接口, 把 `run` / `runStream` 转交给内部 boss — 调用方拿到 [TeamAgent]
 * 后直接 `team.run(input)` / `team.runStream(input)` 即可, 不必穿透到内部实现.
 *
 * team 是**唯一**对外 API 表面 — 内部 boss 私有, 外部不能引用.
 * 需要观察事件流请经 [run] / [runStream] 订阅; 状态经 [state] 读取;
 * 关闭经 [shutdown].
 *
 * team 是一个整体, 外部只配置一次. DSL 里设置的 4 个 capability registry +
 * 1 个 MCP registry + 2 类 tool registry 由 TeamAgent 内部分配给 boss (菜单) 和 pasture (路由),
 * boss 看到的和 pasture 路由的是同一份.
 *
 * 详见主 spec § 4.7 (含构造 DSL `teamAgent { }`).
 */
public class TeamAgent internal constructor(
    private val boss: BossAgent,
    /**
     * 团队统一协程作用域 — 由 [TeamAgentBuilder.build] 创建, 团队持有的唯一一个 scope.
     * 一个 [SupervisorJob] 下, boss 和 pasture 的子任务互不影响 (一个失败不会级联取消另一个).
     * 调用 [shutdown] 取消整个 scope — 所有 boss/pasture 的后台任务一并停止.
     */
    private val teamScope: CoroutineScope,
) : Agent {
    /**
     * Primary 使用入口 — 把整个 team 当作 [Agent] 用, 直接转交给内部 boss.
     * 调用方拿到 [TeamAgent] 后只需 `team.run(input)` / `team.runStream(input)`,
     * 不必关心内部 boss / pasture 这些实现细节.
     */
    override fun run(input: String): Flow<AgentEvent> = boss.run(input)

    override fun runStream(input: String): Flow<AgentEvent> = boss.runStream(input)

    /** 当前 boss 状态 — 内部 boss.state 的转发, 避免 UI 层穿透到实现细节. */
    public val state: StateFlow<BossState> get() = boss.state

    /** UI 通知: 用户开始/结束打字 — 转发给内部 boss.inputting. */
    public fun inputting(active: Boolean): Unit = boss.inputting(active)

    /**
     * 关闭 team — 取消 [teamScope], 停止所有 boss/pasture 的后台任务.
     * 之后 boss LLM 不会再被新事件触发; pasture 的 running jobs 也会被取消.
     * 调用方负责在不再使用 team 时调用本方法 (e.g., 在应用关闭时).
     */
    public fun shutdown() {
        teamScope.cancel()
    }
}
```

**说明**:
- TeamAgent 只是 BossAgent 的 thin wrapper + team 资源 (CoroutineScope) 的容器 — 不做业务逻辑, 仅负责构造时把 bulletinBoard / scope 装配好, 使用时把事件流和状态转发出去.
- `state` 是内部 boss.state 的转发, `inputting` 同理 — 避免 UI 层穿透到内部实现.
- 调用方持有 [TeamAgent] 引用, 永远不需要 (也拿不到) [BossAgent] — boss 是 `internal`, 只在 `team` 包内可见.
