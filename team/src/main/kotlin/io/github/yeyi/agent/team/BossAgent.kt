package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ===== Types =====

public enum class BossState { WAITING, RUNNING, INPUTTING, COLLECTING }

internal class UserRound(
    val input: String,
    val channel: Channel<AgentEvent>,
)

internal class TaskState(
    val selections: List<Selection>,
    val task: String,
    val events: MutableList<AgentEvent> = mutableListOf(),
) {
    val terminal: Boolean
        get() = events.lastOrNull() is AgentEvent.Final || events.lastOrNull() is AgentEvent.Failed
}

// ===== BossAgent =====

public class BossAgent internal constructor(
    private val innerAgent: Agent,
    private val scope: CoroutineScope
) : Agent {

    private val _state = MutableStateFlow(BossState.WAITING)
    public val state: StateFlow<BossState> = _state.asStateFlow()

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

    // 用 null/非 null 同时表达 "是否已 attach" 和 "attach 到哪个 bb" — 比单独 Boolean flag 表达力更强
    // (顺带防 attach 到不同 bb 的逻辑混乱), 且字段少一个.
    private var attachedBoard: BulletinBoard? = null

    /**
     * 启动后台 collect 协程, 订阅 [bb] 的 publishEvents (TaskAssignment 路径) 与
     * progressEvents (TaskUpdate 路径). 返回时**保证**两个 collector 都已注册到 `_events`
     * (用 `subscriptionCount.first {}` 同步等到 — 这是 SharedFlow 标准的硬保证).
     *
     * 两次 attach 会抛 [IllegalStateException]. 由 [BossAgentBuilder.build] 在构造完成后
     * runBlocking 调用一次.
     */
    internal suspend fun attach(bb: BulletinBoard) {
        check(attachedBoard == null) { "BossAgent.attach() must be called only once" }
        attachedBoard = bb
        val expected = bb.subscriptionCount.value + 2
        scope.launch {
            bb.publishEvents
                .filterIsInstance<TaskAssignment>()
                .collect { assignment ->
                    tasksLock.withLock {
                        tasks[assignment.taskId] = TaskState(assignment.selections, assignment.task)
                    }
                }
        }
        scope.launch {
            bb.progressEvents
                .collect { handleTaskUpdate(it as TaskUpdate) }
        }
        bb.subscriptionCount.first { it >= expected }
    }

    // ========== Public API ==========

    override fun run(input: String): Flow<AgentEvent> {
        // scope 已取消 (shutdown 调用过) — run() 返回 Failed 事件.
        if (!scope.isActive) {
            return flow { emit(AgentEvent.Failed(IllegalStateException("Agent is shut down"))) }
        }

        val round = UserRound(input, Channel(Channel.UNLIMITED))
        // 投递到 handlePending — 锁内决策: 闲时直接 launch, 忙时挂起到字段.
        // run() 不沾字段,避免与 finally 清字段覆盖并发写入的 race.
        scope.launch { handlePending(round = round) }
        // 返回的 Flow 内容就是该轮的事件; channel 关闭时 Flow 自然结束
        return flow { for (e in round.channel) emit(e) }
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
    @OptIn(ExperimentalCoroutinesApi::class)
    private suspend fun handlePending(
        round: UserRound? = null,
        postRound: Boolean = false,
    ) {
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
            // postRound 决策先于 channel.close — 保证 toList() 返回前 state 已落定 (避免 race window).
            handlePending(postRound = true)
        } finally {
            round?.channel?.close()
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
