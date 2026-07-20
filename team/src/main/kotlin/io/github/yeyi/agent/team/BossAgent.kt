package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

// ===== Types =====

private enum class BossState { WAITING, RUNNING }

private class UserRound(
    val id: String,
    val input: String,
    val createdAt: Long,
    val channel: Channel<AgentEvent>,
)

/**
 * 单个任务的状态快照.
 *
 * @property taskId 任务 ID
 * @property task 任务描述文本
 * @property events 任务收到的事件列表，通过 [AgentEvent] 类型区分状态
 * @property terminal 是否处于终态（收到 Final 或 Failed 事件）
 */
public data class TaskState(
    public val taskId: String,
    public val task: String,
    internal val roundId: String,
    internal val userInput: String,
    internal val createdAt: Long,
    public val events: MutableList<AgentEvent> = mutableListOf(),
) {
    public val terminal: Boolean
        get() = events.lastOrNull() is AgentEvent.Final || events.lastOrNull() is AgentEvent.Failed
}

/**
 * 一个 round 内所有任务的状态快照，作为 [BossAgent.tasksStates] Flow 的推送单元.
 *
 * @property id round ID
 * @property input 该 round 的用户输入
 * @property createdAt 任务组创建时间，用于 UI 排序
 * @property task 该 round 内所有任务的 [TaskState] 列表
 * @property terminal 是否所有任务都处于终态
 */
public data class TaskGroupState(
    public val id: String,
    public val input: String,
    public val createdAt: Long,
    public val task: List<TaskState>
) {
    public val terminal: Boolean get() = task.all { it.terminal }
}

// ===== BossAgent =====

public class BossAgent internal constructor(
    private val innerAgent: Agent,
    private val scope: CoroutineScope
) : Agent {

    private val state = MutableStateFlow(BossState.WAITING)

    // ===== 任务追踪 =====
    private val tasks: MutableMap<String, TaskState> = mutableMapOf()
    private val tasksLock: Mutex = Mutex()

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

    private lateinit var currentRound: UserRound

    // ===== 用户轮次队列 =====
    // Channel(UNLIMITED) 自然排队，handlePending 消费时取队首
    private val pendingUserRounds: Channel<UserRound> = Channel(capacity = Channel.UNLIMITED)

    // ===== 续轮 summary 队列 =====
    // 结果完成时由 formatTasksResultSummary 格式化后压入,runPendingRound 消费.
    private val pendingResultEvents: Channel<String> = Channel(capacity = Channel.UNLIMITED)

    // ===== 任务组状态推送 =====
    private val tasksStateEmitter = MutableSharedFlow<TaskGroupState>(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /**
     * 任务组状态流 — 每次 TaskUpdate 时推送当前 round 的完整状态.
     * 调用方订阅此 Flow 即可实时获取所有任务的更新状态.
     */
    public val tasksStates: Flow<TaskGroupState> = tasksStateEmitter.asSharedFlow()

    // ===== 并发控制 =====
    // decisionLock 锁内 atomically: 读 state + scope.launch.
    // 跑 round 期间 (LLM 调用) 不持锁; handlePending 之间互斥防 TOCTOU & 字段竞争.
    // 单一入口: 所有 race-free 集中在 [handlePending] 锁内.
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
            bulletinBoard.events.onSubscription { subscribed.complete(Unit) }.collect { event ->
                when (event) {
                    is TaskAssignments -> handleTaskAssignments(event)
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

        val round = UserRound(
            UUID.randomUUID().toString(),
            input,
            System.currentTimeMillis(),
            Channel(Channel.UNLIMITED)
        )
        pendingUserRounds.trySend(round)

        scope.launch { handlePending(false) }
        return flow { for (e in round.channel) emit(e) }
    }

    override fun runStream(input: String): Flow<AgentEvent> = run(input)

    /**
     * 关闭 BossAgent — 取消 [scope], 停止所有 boss/pasture 的后台任务.
     * 之后 boss LLM 不会再被新事件触发; pasture 的 running jobs 也会被取消.
     * 调用方负责在不再使用 boss 时调用本方法 (e.g., 在应用关闭时).
     */
    public fun shutdown() {
        scope.cancel()
    }

    // ========== 内部: 任务事件处理 ==========

    private suspend fun handleTaskAssignments(event: TaskAssignments) {
        // BossAgent 内部 currentRoundId, 自行关联这批 task 到当前 round.
        // 一次 publish_task 调用可以属于当前 round 的多次调用之一.
        tasksLock.withLock {
            for (task in event.tasks) {
                tasks[task.taskId] =
                    TaskState(
                        task.taskId,
                        task.task,
                        currentRound.id,
                        currentRound.input,
                        currentRound.createdAt
                    )
            }
        }
    }

    private suspend fun handleTaskUpdate(update: TaskUpdate) {
        val isTerminal: Boolean
        val state: TaskState
        val roundTasks: List<TaskState>
        tasksLock.withLock {
            val task = tasks[update.taskId]!!
            task.events += update.event
            isTerminal = task.terminal
            roundTasks = tasks.values.filter { it.roundId == task.roundId }
            state = task
        }

        // 每次 TaskUpdate 都推送当前 round 状态
        roundTasks
            .map { ts -> ts.copy(events = ts.events.toMutableList()) }
            .let { TaskGroupState(state.roundId, state.userInput, state.createdAt, it) }
            .run { tasksStateEmitter.tryEmit(this) }

        if (isTerminal.not()) return

        // 检查 round 内所有 task 是否都 terminal
        if (roundTasks.all { it.terminal }) {
            val summary = formatTasksResultSummary(state.roundId)
            pendingResultEvents.trySend(summary)
            handlePending(postRound = false)

            tasksLock.withLock { roundTasks.forEach { tasks.remove(it.taskId) } }
        }
    }

    // ========== 内部: 决定 + 启动 (并发安全) ==========

    /**
     * 唯一决策点 — 锁内决策是否启动 runPendingRound.
     *
     * 触发源:
     * - run() 投递 user round 到 pendingUserRounds
     * - 终态 TaskUpdate 投递 summary 到 pendingResultEvents
     */
    private suspend fun handlePending(postRound: Boolean) {
        decisionLock.withLock {
            if (!postRound && state.value == BossState.RUNNING) return@withLock

            pendingUserRounds.tryReceive().getOrNull()?.let { userRound ->
                currentRound = userRound
                state.value = BossState.RUNNING
                scope.launch { runUserRound(userRound) }
                return
            }

            pendingResultEvents.tryReceive().getOrNull()?.let { result ->
                state.value = BossState.RUNNING
                scope.launch { runResultRound(result) }
                return
            }

            if (state.value != BossState.WAITING) {
                state.value = BossState.WAITING
            }
        }
    }

    // ========== 内部: 跑轮次 ==========

    private suspend fun runUserRound(userRound: UserRound) {
        try {
            innerAgent.run(userRound.input).collect { e ->
                userRound.channel.send(e)
            }
        } finally {
            userRound.channel.close()
            handlePending(postRound = true)
        }
    }

    private suspend fun runResultRound(result: String) {
        innerAgent.run(result).collect { e ->
            continuationsEmitter.emit(e)
        }
        handlePending(postRound = true)
    }

    // ========== 内部: 状态查询与合并 ==========

    /**
     * 格式化 round 内所有 task 的 terminal event 为可读 summary.
     * 由 BossAgent 从 tasks.get(taskId).events 聚合,不依赖 Pasture 提供.
     *
     * @param roundId 要汇总的 round ID
     * @return summary 字符串
     */
    private suspend fun formatTasksResultSummary(roundId: String): String = tasksLock.withLock {
        val roundTasks = tasks.entries.filter { it.value.roundId == roundId }
        buildString {
            append("Tasks finished:\n")
            for ((taskId, task) in roundTasks) {
                val lastEvent = task.events.last()
                append("- $taskId: $lastEvent\n")
            }
        }
    }
}
