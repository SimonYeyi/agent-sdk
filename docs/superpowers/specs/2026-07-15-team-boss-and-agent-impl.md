# Team 模块 — BossAgent 实现

> 日期: 2026-07-15 · 状态: **Draft** (待用户审阅)
> 来源: 主 spec [`2026-07-13-team-module-design.md`](./2026-07-13-team-module-design.md) § 4.5 + § 4.7
> 范围: BossAgent 自身完整实现（BossAgentBuilder DSL 留在主 spec § 4.7）

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 关联组件 | BulletinBoard / Pasture / Beast / Selection / TaskAssignment / TaskUpdate / PublishTaskTool / CancelTaskTool(均见主 spec § 4.1-4.6) |
| 状态机定义 | 主 spec § 7 — BossAgent 的状态表、转换规则、ProgressEvent 合并策略 |
| 公开 API | `BossAgent` / `BossState` |
| 内部 API | `BossAgent` / `UserRound` / `TaskState` / `handlePending` / `runPendingRound` |

---

## 1. BossAgent 内部实现

Boss = 包装 `ReActAgent` + 状态机 + 异步 ProgressEvent 合并 + 双事件流分流。

**核心设计 — 两条事件流**:
| 路径 | 触发源 | 流向 | 调用方拿到 |
|---|---|---|---|
| User round | `run(input)` (state 闲时启动, 忙时挂起) | `UserRound.channel` (per-round, UNLIMITED) | `run()` 返回的 Flow |
| Continuation | TaskUpdate 终态 (state WAITING/INPUTTING 时由 `handlePending` 启动) | `continuationsEmitter` (hot SharedFlow) | `continuations` property |

`run()` 返回的 Flow 内容就是 `innerAgent.run(merged)` — boss 不 wrap 事件,直接转发。`emitEvents` 开关**彻底删除**,每条路径只 emit 一次到自己的 sink。

**挂起语义**: state 忙 (RUNNING/COLLECTING) 时调 `run(input)`, input 暂存到 `pendingUserRound` 字段, Flow 仍然立即返回 (绑在 UserRound.channel 上,等合并 round 启动时才有事件喂进来)。Channel 关闭时 Flow 自然结束 — 调用方 collect 完即知道 round 结束。

**并发安全**: `decisionLock: Mutex` 保护"决定+启动"序列;`tasksLock: Mutex` 保护 `tasks` map;`pendingResultEvents` 用 `Channel.isEmpty` 而非 tryReceive+trySend 的 peek,避免 race。

**状态机的输入/输出与转换规则见主 spec § 7**。本节只列实现。

```kotlin
package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Boss state machine. 详见主 spec § 7.
 *
 * - [WAITING]: idle, 等待外部输入 (用户或终态 TaskUpdate)
 * - [RUNNING]: round 正在跑 (user-driven 或 task-driven 续轮)
 * - [INPUTTING]: user 在 WAITING 状态下开始打字 (UI 信号)
 * - [COLLECTING]: 任务触发的续轮等 1s 合并窗口
 *
 * 公开原因: TeamAgent.state: StateFlow<BossState> 需对外暴露,UI 才能订阅 boss 当前状态.
 */
public enum class BossState { WAITING, RUNNING, INPUTTING, COLLECTING }

internal class UserRound(
    val input: String,
    val channel: Channel<AgentEvent>,  // UNLIMITED capacity
)

internal class TaskState(
    val selections: List<Selection>,
    val task: String,
    val events: MutableList<AgentEvent> = mutableListOf(),
) {
    val terminal: Boolean
        get() = events.lastOrNull() is AgentEvent.Final || events.lastOrNull() is AgentEvent.Failed
}

/**
 * Boss = 包装 [Agent] (innerAgent) + 状态机 + 异步 ProgressEvent 合并 + 双事件流分流.
 *
 * 不重写 ReAct 循环. innerAgent 跑 ReAct, BossAgent 在外层协调:
 * - 注册 [PublishTaskTool] / [CancelTaskTool] 到 innerAgent 的 toolRegistry
 * - 订阅 [BulletinBoard] 的 [TaskUpdate] 合并到 input
 * - 维护 [tasks] 追踪所有派出的任务
 * - 状态机决定什么时候触发新的 innerAgent.run(), 走 user 流 (合并 round) 或 continuations 流 (续轮)
 *
 * teamScope 由 [BossAgentBuilder] 创建并注入 — boss 和 pasture 共享同一个 [CoroutineScope].
 * 详见主 spec § 4.5.
 */
public class BossAgent internal constructor(
    private val innerAgent: Agent,
    private val bulletinBoard: BulletinBoard,
    private val scope: CoroutineScope
) : Agent {

    private val _state = MutableStateFlow(BossState.WAITING)
    val state: StateFlow<BossState> = _state.asStateFlow()

    // ===== 用户轮次 =====
    // pendingUserRound: state 忙时由 run() 写入,handlePending() 取出并清空
    // 字段生命周期: 唯一写入点 run(), 唯一消费点 handlePending()
    private var pendingUserRound: UserRound? = null

    // ===== 续轮事件流 (hot SharedFlow) =====
    private val continuationsEmitter = MutableSharedFlow<AgentEvent>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /**
     * 续轮事件流 (hot SharedFlow) — 任务结果触发的 round 事件都流到这里.
     * 与 [run] 互补: `run` 是用户驱动的单次 round 流, `continuations` 是任务驱动的多 round 流.
     * 调用方订阅一次即可收所有续轮 (UI + logger 多消费者支持).
     */
    public val continuations: Flow<AgentEvent> = continuationsEmitter.asSharedFlow()

    // ===== 任务追踪 =====
    private val tasks: MutableMap<String, TaskState> = mutableMapOf()
    private val tasksLock: Mutex = Mutex()

    // ===== 待合并的终态 TaskUpdate (Final / Failed) =====
    private val pendingResultEvents: Channel<TaskUpdate> = Channel(capacity = Channel.UNLIMITED)

    /** COLLECTING 窗口长度 — 让一次 collect 期间到达的新终态合并到下一轮 input. */
    private companion object {
        private const val COLLECTING_WINDOW_MS: Long = 1000
    }

    // ===== 并发控制 =====
    // 序列化"决定 + 启动"序列: run() / handleTaskUpdate() / round finally 都会调 handlePending,
    // 必须互斥,否则 state 检查和 scope.launch 之间会有 TOCTOU race, 触发两个 round.
    private val decisionLock: Mutex = Mutex()

    init {
        // 订阅 BulletinBoard: 1 boss 1 BossAgent, 自己的 publishEvents / progressEvents 全是本 boss 的,
        // 直接订阅即可, 不需按名称过滤.
        scope.launch {
            bulletinBoard.publishEvents
                .filterIsInstance<TaskAssignment>()
                .collect { assignment ->
                    tasksLock.withLock {
                        tasks[assignment.taskId] = TaskState(assignment.selections, assignment.task)
                    }
                }
        }
        scope.launch {
            bulletinBoard.progressEvents
                .collect { handleTaskUpdate(it as TaskUpdate) }
        }
    }

    // ========== Public API ==========

    override fun run(input: String): Flow<AgentEvent> {
        val round = UserRound(input, Channel(Channel.UNLIMITED))

        if (_state.value in setOf(BossState.RUNNING, BossState.COLLECTING)) {
            // 状态忙: 挂起到 pendingUserRound, 等 handlePending() 取出清空 + 启动
            pendingUserRound = round
        } else {
            // 状态闲 (WAITING/INPUTTING): 直接启动
            pendingUserRound = round
            scope.launch { runPendingRound() }
        }

        // 返回的 Flow 内容就是该轮的事件; channel 关闭时 Flow 自然结束
        return kotlinx.coroutines.flow.flow { for (e in round.channel) emit(e) }
    }

    override fun runStream(input: String): Flow<AgentEvent> = run(input)

    /**
     * UI 通知: 用户开始/结束打字.
     * 状态机感知: 只在合理的状态下转换, 不打断正在跑的 round.
     */
    public fun inputting(active: Boolean) {
        when {
            active && _state.value == BossState.WAITING -> _state.value = BossState.INPUTTING
            !active && _state.value == BossState.INPUTTING -> _state.value = BossState.WAITING
            // 其他 state: no-op (不打断 RUNNING/COLLECTING)
        }
    }

    /**
     * 关闭 BossAgent — 取消 [scope], 停止所有 boss/pasture 的后台任务.
     * 之后 boss LLM 不会再被新事件触发; pasture 的 running jobs 也会被取消.
     * 调用方负责在不再使用 boss 时调用本方法 (e.g., 在应用关闭时).
     */
    public fun shutdown() {
        scope.cancel()
    }

    // ========== 内部: 任务事件处理 ==========

    private suspend fun handleTaskUpdate(update: TaskUpdate) {
        tasksLock.withLock {
            val task = tasks[update.taskId] ?: return
            task.events += update.event
            if (!task.terminal) return  // 非终态, 只更新状态
        }
        // 终态事件: 缓存到 channel + 触发决策
        pendingResultEvents.trySend(update)
        handlePending()
    }

    // ========== 内部: 决定 + 启动 (并发安全) ==========

    /**
     * 唯一决策点:在锁内根据 pending 状态决定下一步.
     *
     * @param postRound true 表示当前 round/collect 已经跑完 (handlePending 是来接班的),
     *   state 此时是 RUNNING 或 COLLECTING — 负责把它转到下一态
     *   (RUNNING→RUNNING by launch userRound / RUNNING→COLLECTING by launch collect /
     *   RUNNING→WAITING by go idle). false 表示外部触发 (handleTaskUpdate) — 此场景
     *   状态只在 WAITING/INPUTTING,race 中 state 错位变 RUNNING/COLLECTING 则 bail.
     *
     * 不论哪种调用,跑什么分支都走 [scope.launch],分支内自己设 RUNNING/COLLECTING.
     * 没活时显式 state→WAITING (已经 WAITING 时 no-op).
     */
    private fun handlePending(postRound: Boolean = false) {
        scope.launch {
            decisionLock.withLock {
                // 外部撞忙就退出,round 撞忙就接着干.
                if (!postRound && _state.value in setOf(
                        BossState.RUNNING,
                        BossState.COLLECTING
                    )
                ) return@withLock
                val pendingRound = pendingUserRound
                val hasActive = hasActiveTasks()
                val hasResults = !pendingResultEvents.isEmpty

                when {
                    hasResults && hasActive -> {
                        // COLLECTING 续轮: 等 1s 后跑续轮
                        scope.launch { runPendingRoundWithCollecting() }
                    }
                    pendingRound != null || hasResults -> {
                        // 合并用户输入或处理终态结果
                        scope.launch { runPendingRound() }
                    }
                    // 真 idle: 切回 WAITING. 已经 WAITING 时 no-op.
                    else -> if (_state.value != BossState.WAITING) {
                        _state.value = BossState.WAITING
                    }
                }
            }
        }
    }

    // ========== 内部: 跑轮次 ==========

    private suspend fun runPendingRound() {
        _state.value = BossState.RUNNING
        val userRound = pendingUserRound
        try {
            val merged = drainPendingWith(userRound?.input)
            if (merged != null) {
                innerAgent.run(merged).collect { e ->
                    if (userRound == null) {
                        continuationsEmitter.emit(e)
                    } else {
                        userRound.channel.send(e)
                    }
                }
            }
        } finally {
            pendingUserRound = null
            userRound?.channel.close()
            handlePending(postRound = true)
        }
    }

    private suspend fun runPendingRoundWithCollecting() {
        _state.value = BossState.COLLECTING
        val deadline = System.currentTimeMillis() + COLLECTING_WINDOW_MS
        while (System.currentTimeMillis() < deadline) {
            if (!hasActiveTasks()) break
            delay(50)
        }
        runPendingRound()
    }

    // ========== 内部: 状态查询与合并 ==========

    private suspend fun hasActiveTasks(): Boolean = tasksLock.withLock {
        tasks.values.any { !it.terminal }
    }

    /** 取出所有终态 TaskUpdate, 与可选 input 合并, 返回 null 表示无内容 */
    private fun drainPendingWith(input: String?): String? {
        val updates = mutableListOf<TaskUpdate>()
        while (true) {
            val next = pendingResultEvents.tryReceive().getOrNull() ?: break
            updates.add(next)
        }
        if (updates.isEmpty() && input == null) return null
        return buildString {
            input?.let { append(it); if (updates.isNotEmpty()) append("\n") }
            if (updates.isNotEmpty()) append(formatTaskResults(updates))
        }
    }

    private fun formatTaskResults(updates: List<TaskUpdate>): String = buildString {
        append("以下是之前派出的后台任务的结果:")
        updates.forEach { append("\n${it.taskId}: ${it.event}") }
    }
}
```

**关键设计点**:

1. **`run()` Flow 是 per-round Channel** (UNLIMITED) — 用户晚 collect 也能拿到整轮事件,单消费者语义
2. **`continuations` 是 hot SharedFlow** — 多次续轮事件串到同一流,多消费者 (UI + logger),buffer overflow 丢最早事件
3. **boss 不 wrap 事件** — `innerAgent.run(merged).collect { round.channel.send(e) }` 或 `continuationsEmitter.emit(e)`,无中转流,无 `emitEvents` 开关
4. **`pendingUserRound` 单字段** — 唯一写入点 `run()` (state 忙时),唯一消费点 `handlePending()` (决定+启动序列内)
5. **`decisionLock` 序列化** — 防止 `run()` / `handleTaskUpdate()` / round finally 三处并发触发决策;lock 内重检 state,避免 TOCTOU
6. **`tasks` map 全程 `tasksLock.withLock`** — `hasActiveTasks` 也是 suspending
7. **`pendingResultEvents` 用 `isEmpty` peek** — 不用 tryReceive+trySend 的非原子 peek
8. **`inputting(active)` 状态机感知** — 不打断 RUNNING/COLLECTING;只在 WAITING ↔ INPUTTING 之间切换
9. **`state` 转换唯一收敛于 `handlePending`** — round/collect finally 不再显式 `state = WAITING`,handlePending(`postRound = true`) 在锁内决定下一态 (launch 走 `scope.launch` 自然过渡到 RUNNING/COLLECTING;真 idle 才 `state → WAITING`). 避免 RUNNING→WAITING→RUNNING / COLLECTING→WAITING→COLLECTING 闪烁
