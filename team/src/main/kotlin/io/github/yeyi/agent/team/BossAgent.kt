package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ===== Types =====

public enum class BossState { WAITING, RUNNING, INPUTTING }

internal class UserRound(
    val input: String,
    val channel: Channel<AgentEvent>,
)

internal class TaskState(
    val selections: List<Selection>,
    val task: String,
    val roundId: String,
    val dependsOn: List<String> = emptyList(),
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

    // ===== 当前 round ID =====
    // 每 run() 时生成, attach 收到 TaskAssignments 时用来关联该批 task 所属 round.
    // roundId 是 BossAgent 视角的元数据,不属于事件业务载荷 —— 事件本身不带 roundId.
    private var currentRoundId: String = ""

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

    // ===== 待合并的 round summary 字符串 =====
    // round 完成时由 formatRoundSummary 格式化后压入,handlePending 消费.
    private val pendingResultEvents: Channel<String> = Channel(capacity = Channel.UNLIMITED)

    // ===== 并发控制 =====
    // decisionLock 锁内 atomically: 读 state + 读/清 pendingUserRound + scope.launch.
    // 跑 round 期间 (LLM 调用) 不持锁; handlePending 之间互斥防 TOCTOU & 字段竞争.
    // 单一入口: 所有 race-free 集中在 [handlePending] 锁内的"读+清+launch"三步.
    private val decisionLock: Mutex = Mutex()

    // lateinit: 由 [attach] 赋值, 后续 collect 统一从此字段读取.
    // 未初始化访问抛 UninitializedPropertyAccessException (消息固定但可读).
    private lateinit var bulletinBoard: BulletinBoard

    /**
     * 启动后台 collect 协程, 订阅 [bb] 的原始 [BulletinBoard.events] 统一流, 内部 when 区分
     * TaskAssignments / TaskUpdate 分发到对应处理逻辑. BossAgent 在 `_events` 上注册**一个**
     * collector — 用 [onSubscription] 回调 + [CompletableDeferred] 同步等到"**自己的**" collector
     * 注册完成.
     *
     * 两次 attach 会抛 [IllegalStateException]. 由 [BossAgentBuilder.build] 在构造完成后
     * runBlocking 调用一次.
     */
    internal suspend fun attach(bb: BulletinBoard) {
        check(!::bulletinBoard.isInitialized) { "BossAgent.attach() must be called only once" }
        bulletinBoard = bb
        val subscribed = CompletableDeferred<Unit>()
        scope.launch {
            bulletinBoard.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { event ->
                    when (event) {
                        is TaskAssignments -> {
                            // BossAgent 内部 currentRoundId, 自行关联这批 task 到当前 round.
                            // 一次 publish_task 调用可以属于当前 round 的多次调用之一.
                            val roundId = currentRoundId
                            tasksLock.withLock {
                                for (task in event.tasks) {
                                    tasks[task.taskId] = TaskState(task.selections, task.task, roundId, task.dependsOn)
                                }
                            }
                        }
                        is TaskUpdate -> handleTaskUpdate(event)
                        // 其他事件 (Cancellation 等) BossAgent 不关心, 显式 no-op
                        // 让编译器在 BulletinEvent 加新类型时强制更新此 when.
                        else -> Unit
                    }
                }
        }
        subscribed.await()
    }

    // ========== Public API ==========

    override fun run(input: String): Flow<AgentEvent> {
        // scope 已取消 (shutdown 调用过) — run() 返回 Failed 事件.
        if (!scope.isActive) {
            return flow { emit(AgentEvent.Failed(IllegalStateException("Agent is shut down"))) }
        }

        currentRoundId = java.util.UUID.randomUUID().toString()  // 每轮一个新 roundId
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
            // 其他 state: no-op (不打断 RUNNING)
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
        val roundId: String
        tasksLock.withLock {
            val task = tasks[update.taskId] ?: return
            task.events += update.event
            if (!task.terminal) return  // 非终态, 只更新状态
            roundId = task.roundId
        }
        // 检查 round 内所有 task 是否都 terminal
        val allDone = tasksLock.withLock {
            tasks.values.filter { it.roundId == roundId }.all { it.terminal }
        }
        if (allDone) {
            val summary = formatRoundSummary(roundId) ?: return
            pendingResultEvents.trySend(summary)
            handlePending()
        }
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
            if (!postRound && _state.value == BossState.RUNNING) return@withLock

            // 3) postRound 接班 或 外部撞闲: 决策
            val pendingRound = pendingUserRound
            pendingUserRound = null  // 锁内清, race-free
            val roundSummary = pendingResultEvents.tryReceive().getOrNull()

            when {
                // 合并用户输入 / 纯续轮
                pendingRound != null || roundSummary != null -> {
                    _state.value = BossState.RUNNING
                    scope.launch { runPendingRound(pendingRound, roundSummary) }
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
     * @param roundSummary 当前 round 内所有 task 完成后的 summary (由 formatRoundSummary 生成).
     */
    private suspend fun runPendingRound(round: UserRound? = null, roundSummary: String? = null) {
        try {
            val merged = drainPendingWith(round?.input, roundSummary)
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

    // ========== 内部: 状态查询与合并 ==========

    /**
     * 取出 round summary, 与可选 user input 合并.
     *
     * @param input       用户本轮输入 (可为 null, 表示纯续轮)
     * @param roundSummary 当前 round 内所有 task 完成后的 summary (可为 null)
     * @return 合并后的字符串, null 表示两者都为空
     */
    private fun drainPendingWith(input: String?, roundSummary: String?): String? {
        if (input == null && roundSummary == null) return null
        return buildString {
            input?.let { append(it); if (roundSummary != null) append("\n\n") }
            roundSummary?.let { append(it) }
        }
    }

    /**
     * 格式化 round 内所有 task 的 terminal event 为可读 summary.
     * 由 BossAgent 从 tasks[taskId].events 聚合,不依赖 Pasture 提供.
     *
     * @param roundId 要汇总的 round ID
     * @return summary 字符串, null 表示 round 内无 task
     */
    private suspend fun formatRoundSummary(roundId: String): String? = tasksLock.withLock {
        val roundTasks = tasks.entries.filter { it.value.roundId == roundId }
        if (roundTasks.isEmpty()) return@withLock null
        buildString {
            append("Tasks completed:\n")
            for ((taskId, task) in roundTasks) {
                val lastEvent = task.events.lastOrNull()
                val marker = when (lastEvent) {
                    is AgentEvent.Final -> "✓ done"
                    is AgentEvent.Failed -> "✗ failed"
                    else -> "?"
                }
                val detail = lastEvent?.let { ": $it" } ?: ""
                append("- $taskId $marker$detail\n")
            }
        }
    }
}
