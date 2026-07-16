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
import kotlinx.coroutines.CancellationException
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

    // ===== 用户轮次挂起 =====
    // pendingUserRound 是决策字段 — 在 [handlePending] 锁内 "读 + 清" 三步原子化.
    // 唯一写入点: handlePending 锁内, run() 投递且 state 忙时挂起到字段 (latest-wins).
    // 唯一读+清点: handlePending 锁内, 第 3 段决策时取出并清.
    // run() 闲时直接 launch (不走字段); runPendingRound 接收 round 参数 (也不沾字段).
    // finally 不清字段 — 防止覆盖并发 busy 时另一线程 run() 写入的新 round.
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
    // decisionLock 锁内 atomically: 读 state + 读/清 pendingUserRound + scope.launch.
    // 跑 round 期间 (LLM 调用) 不持锁; handlePending 之间互斥防 TOCTOU & 字段竞争.
    // 单一入口: 所有 race-free 集中在 [handlePending] 锁内的"读+清+launch"三步.
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
        // 投递到 handlePending — 锁内决策: 闲时直接 launch, 忙时挂起到字段.
        // run() 不沾字段,避免与 finally 清字段覆盖并发写入的 race.
        scope.launch { handlePending(round = round) }
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
     * 唯一决策点 — 锁内 atomically "读 state + 清 pendingUserRound + scope.launch".
     *
     * 4 种触发源 (参数互斥):
     * - [run] 投递 (`round != null`, `postRound = false`): 闲时直接 launch, 忙时挂起到字段
     * - 终态 TaskUpdate (`round = null`, `postRound = false`): 外部触发; 撞忙 bail, 撞闲决策
     * - round 跑完 (`round = null`, `postRound = true`): postRound 接班, 决策续轮或 idle
     * - collect 跑完 — 同 postRound
     *
     * 锁外都不读不写 `pendingUserRound`, 锁内 "读 + 清" 一气呵成 — 防 finally 清字段覆盖并发 write.
     *
     * @param round     [run] 投递的 user round (锁内消费: 闲启动 / 忙挂起)
     * @param postRound 当前 round/collect 已跑完 (锁内接续决策)
     */
    private fun handlePending(
        round: UserRound? = null,
        postRound: Boolean = false,
    ) {
        scope.launch {
            decisionLock.withLock {
                // 1) run() 投递: 闲时启动, 忙时挂起到字段 (latest-wins: 老挂起 superseded)
                if (round != null) {
                    when {
                        _state.value in setOf(BossState.WAITING, BossState.INPUTTING) -> {
                            _state.value = BossState.RUNNING
                            scope.launch { runPendingRound(round) }
                        }
                        else -> {
                            // 老挂起 round 被最新 run() 替代: close 前 emit Failed(CancellationException)
                            // 让 Flow 收到终止事件 (而非静默 close),符合 AgentEvent 终止语义.
                            pendingUserRound?.let { supersedeRound(it) }
                            pendingUserRound = round
                        }
                    }
                    return@withLock
                }

                // 2) 外部触发 (handleTaskUpdate): 撞忙 bail
                //    外部撞忙就退出, round/postRound 撞忙就接着干.
                if (!postRound && _state.value in setOf(
                        BossState.RUNNING,
                        BossState.COLLECTING,
                    )
                ) return@withLock

                // 3) postRound 接班 或 外部撞闲: 决策
                //    注意: postRound 路径只发续轮, 不进 COLLECTING — 防 1s collect 死循环.
                val pendingRound = pendingUserRound
                pendingUserRound = null  // 锁内清, race-free
                val hasActive = hasActiveTasks()
                val hasResults = !pendingResultEvents.isEmpty

                when {
                    // 外部触发 + 仍有 active 任务 → COLLECTING 1s 等更多
                    !postRound && hasResults && hasActive -> {
                        scope.launch { runPendingRoundWithCollecting() }
                    }
                    // 合并用户输入 / 纯续轮 / collect 后到达
                    pendingRound != null || hasResults -> {
                        _state.value = BossState.RUNNING
                        scope.launch { runPendingRound(pendingRound) }
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

    /**
     * 关闭被 superseded 的挂起 round — close 前 emit Failed(CancellationException)
     * 让其 Flow 收到终止事件, 符合 AgentEvent 流终止语义 (不静默 close).
     */
    private fun supersedeRound(round: UserRound) {
        // 按主 spec § 9.1: AgentEvent.Failed 直接接 Throwable, 不再要求包成 AgentException.
        // superseded 是控制流语义 (被更新 run() 取代, 而非业务失败), 用 CancellationException 表达.
        round.channel.trySend(
            AgentEvent.Failed(CancellationException("superseded by newer run()"))
        )
        round.channel.close()
    }

    /**
     * 跑一轮 — 接受 round 参数, 不沾字段.
     *
     * @param round user round (来自 handlePending 锁内参数); null = 纯续轮.
     */
    private suspend fun runPendingRound(round: UserRound? = null) {
        try {
            val merged = drainPendingWith(round?.input)
            if (merged != null) {
                innerAgent.run(merged).collect { e ->
                    if (round == null) continuationsEmitter.emit(e)
                    else round.channel.send(e)
                }
            }
        } finally {
            round?.channel?.close()
            // finally 不清字段 — 字段由 handlePending 锁内清, finally 清会覆盖并发 busy 时 run() 写入的新 round.
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
        // 1s 等到后调 postRound 决策 — 第 3 段不进入 COLLECTING 分支, 防 collect 死循环.
        handlePending(postRound = true)
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
4. **`pendingUserRound` 是决策字段** — `handlePending` 锁内唯一读写. `run()` 闲时直接 launch, 不沾字段;`runPendingRound` 接收 round 参数, 不沾字段. 锁内 "读+清+launch" 原子化, 消除 finally 清字段覆盖并发 busy 写入的 race.
5. **`decisionLock` 序列化** — 锁内 atomically: 读 state + 清 `pendingUserRound` + `scope.launch`. 跑 round 期间不持锁 (LLM 耗时长), `handlePending` 之间互斥防 TOCTOU + 字段竞争.
6. **`tasks` map 全程 `tasksLock.withLock`** — `hasActiveTasks` 也是 suspending
7. **`pendingResultEvents` 用 `isEmpty` peek** — 不用 tryReceive+trySend 的非原子 peek
8. **`inputting(active)` 状态机感知** — 不打断 RUNNING/COLLECTING;只在 WAITING ↔ INPUTTING 之间切换
9. **`state` 转换唯一收敛于 `handlePending`** — round/collect finally 不再显式 `state = WAITING`,handlePending(`postRound = true`) 在锁内决定下一态 (launch 走 `scope.launch` 自然过渡到 RUNNING/COLLECTING;真 idle 才 `state → WAITING`). 避免 RUNNING→WAITING→RUNNING / COLLECTING→WAITING→COLLECTING 闪烁
