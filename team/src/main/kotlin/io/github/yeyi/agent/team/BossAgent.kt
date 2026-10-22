package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.Shutdownable
import io.github.yeyi.agent.llm.ContentPart
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

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
    internal val query: String,
    internal val createdAt: Long,
    public val events: MutableList<AgentEvent> = mutableListOf(),
) {
    public val terminal: Boolean
        get() = events.lastOrNull() is AgentEvent.Final || events.lastOrNull() is AgentEvent.Failed
}

/**
 * 一个 round 内所有任务的状态快照，作为 [BossAgent.tasksState] Flow 的推送单元.
 *
 * @property roundId round ID
 * @property query 该 round 的用户意图
 * @property createdAt 任务组创建时间，用于 UI 排序
 * @property tasks 该 round 内所有任务的 [TaskState] 列表
 * @property terminal 是否所有任务都处于终态
 * @property latestChanged 最新有状态变化的任务
 */
public data class TasksState(
    public val roundId: String,
    public val query: String,
    public val createdAt: Long,
    public val tasks: List<TaskState>,
    public val latestChanged: TaskState
) {
    public val terminal: Boolean get() = tasks.all { it.terminal }
}

// ===== BossAgent =====

public class BossAgent internal constructor(
    private val innerAgent: Agent,
    private val scope: CoroutineScope,
) : Agent, Shutdownable {
    public companion object {
        /** 系统报告标签 — 任务完成汇报以 `AgentQuery.text(result, label = REPORT_LABEL)` 注入 innerAgent，
         *  随记忆条目持久化，UI 可据此渲染"系统"气泡，模型侧通过 `report: content` 前缀感知。 */
        public const val REPORT_LABEL: String = "report"
    }

    // ===== 任务追踪 =====
    private val tracker = TaskTracker()

    // ===== 调度器 =====
    private val scheduler = RoundScheduler()

    // ===== 关闭辅助 =====
    private val shutdownHelper = ShutdownHelper()

    // lateinit: 由 [attach] 赋值, 后续 collect 统一从此字段读取.
    // 未初始化访问抛 UninitializedPropertyAccessException (消息固定但可读).
    private lateinit var bulletinBoard: BulletinBoard

    /**
     * 活跃任务.
     */
    public val activeTasks: List<TaskState> get() = tracker.activeTasks

    /**
     * 任务组状态流 — 每次 TaskUpdate 时推送当前 round 的完整状态.
     * 调用方订阅此 Flow 即可实时获取所有任务的更新状态.
     */
    public val tasksState: Flow<TasksState> get() = tracker.states

    // ===== 报告事件流 (hot SharedFlow) =====
    /**
     * 报告事件流 (hot SharedFlow) — 任务结果触发的 round 事件都流到这里.
     * 与 [run] 互补: `run` 是用户驱动的单次 round 流, `report` 是任务驱动的多 round 流.
     * 调用方订阅一次即可收所有报告 (UI + logger 多消费者支持).
     */
    public val report: Flow<AgentEvent> = MutableSharedFlow(
        replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

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
                    is Cancellation -> handleCancellation(event)
                    // 其他事件 BossAgent 不关心, 显式 no-op
                    // 让编译器在 BulletinEvent 加新类型时强制更新此 when.
                }
            }
        }
        subscribed.await()
    }

    // ========== Public API ==========

    override fun run(query: AgentQuery): Flow<AgentEvent> {
        // scope 已取消 (shutdown 调用过) — 已关闭的 Agent 不可再运行，直接抛非法状态异常。
        check(scope.isActive) { "Agent is shut down" }

        val queryText = (query.parts.firstOrNull() as? ContentPart.Text)?.text
            ?: query.parts.joinToString(" ") { it.toString() }
        val userRound = UserRound(
            UUID.randomUUID().toString(),
            queryText,
            System.currentTimeMillis(),
            Channel(Channel.UNLIMITED)
        )
        shutdownHelper.record(userRound)
        scope.launch { scheduler.submitUserRound(userRound) }
        // 注销不在此处做：调用方可能 run 后不 collect（flow 惰性），
        // 由 runUserRound 完成时注销，未跑到的轮次由 shutdown 兜底 clear。
        return flow { for (e in userRound.channel) emit(e) }
    }

    /**
     * 关闭 BossAgent — 取消 [scope], 停止所有 boss/pasture 的后台任务,
     * 并关闭所有未结束 round 的 channel 终止进行中的 run flow.
     * 之后 boss LLM 不会再被新事件触发; pasture 的 running jobs 也会被取消.
     * 调用方负责在不再使用 boss 时调用本方法 (e.g., 在应用关闭时).
     */
    public override fun shutdown() {
        shutdownHelper.closeAll()
        scope.cancel()
    }

    // ========== 内部: 任务事件处理 ==========

    private suspend fun handleTaskAssignments(event: TaskAssignments) {
        // BossAgent 内部 currentRoundId, 自行关联这批 task 到当前 round.
        // 一次 publish_task 调用可以属于当前 round 的多次调用之一.
        // publish 只发生在 user round 进行中 (LLM 调用), 此时 currentRound 必已赋值.
        val round = scheduler.currentRound
        tracker.track(event, round.id, round.createdAt)
    }

    private suspend fun handleTaskUpdate(update: TaskUpdate) {
        tracker.apply(update)?.let { result -> scheduler.submitResult(result) }
    }

    /**
     * 处理取消请求.
     *
     * 取消目标分两种情形:
     * 1. 任务在活跃追踪表中 —— Boss 不干预:
     *    - 未终态: Pasture 负责真正取消, 通过 TaskUpdate 走既有续轮流程;
     *    - 已终态 (同轮其它任务未完成): 取消落空但轮次语义不变 —— 继续等待
     *      全部任务终态后自然完成, 不提前唤醒产生不完整报告.
     * 2. 任务不在追踪表中 —— 已完成并归档 (或从未发布): 取消必然落空.
     *    直接唤醒续轮, 告知模型: 任务已结束, 无法取消.
     */
    private suspend fun handleCancellation(e: Cancellation) {
        if (tracker.isTracked(e.taskId)) return
        scheduler.submitResult("Task ${e.taskId} has already finished and cannot be cancelled.")
    }

    // ========== 内部: 跑轮次 ==========

    private suspend fun runUserRound(userRound: UserRound) {
        try {
            innerAgent.run(AgentQuery.text(userRound.input)).collect { e ->
                userRound.channel.send(e)
            }
        } finally {
            userRound.channel.close()
            // 轮次完成即移除，无人收集的 round 不滞留登记表
            shutdownHelper.remove(userRound)
            scheduler.roundFinished()
        }
    }

    private suspend fun runResultRound(result: String) {
        innerAgent.run(AgentQuery.text(result, label = REPORT_LABEL)).collect { e ->
            (report as MutableSharedFlow).emit(e)
        }
        scheduler.roundFinished()
    }

    // ========== 内部类: 任务追踪域 ==========

    /**
     * 任务追踪 — 独占 [lock], 管理活跃任务表与看板状态.
     *
     * [apply] 在单次持锁内完成: 事件落账 + 看板推送 + 整轮终态判断 + 格式化结果 + 归档删除,
     * 消除原实现"跨两段锁"的理论缝隙 (快照删除与按 roundId 过滤不一致).
     * 整轮终态返回格式化 result 供调度器唤醒续轮; 非终态返回 null.
     */
    private inner class TaskTracker {
        private val lock = Mutex()
        private val tasks: MutableMap<String, TaskState> = mutableMapOf()
        val states: MutableSharedFlow<TasksState> = MutableSharedFlow(
            replay = 0, extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
        )

        val activeTasks: List<TaskState> get() = tasks.values.toList()

        suspend fun track(event: TaskAssignments, roundId: String, createdAt: Long) {
            lock.withLock {
                for (task in event.tasks) {
                    tasks[task.taskId] = TaskState(
                        task.taskId, task.task, roundId, event.query, createdAt
                    )
                }
            }
        }

        suspend fun apply(update: TaskUpdate): String? = lock.withLock {
            val task = tasks[update.taskId]!!
            task.events += update.event
            val terminal = task.terminal
            val roundTasks = tasks.values.filter { it.roundId == task.roundId }

            roundTasks
                .map { ts -> ts.copy(events = ts.events.toMutableList()) }
                .let { TasksState(task.roundId, task.query, task.createdAt, it, task) }
                .let { states.tryEmit(it) }

            if (!terminal || roundTasks.any { !it.terminal }) return null

            roundTasks.forEach { tasks.remove(it.taskId) }

            // 整轮终态: 格式化 + 归档删除, 单锁内完成
            return buildString {
                for (t in roundTasks) {
                    append("- ${t.taskId}: ${t.events.last()}\n")
                }
            }
        }

        suspend fun isTracked(taskId: String): Boolean = lock.withLock { taskId in tasks }
    }

    // ========== 内部类: 轮次调度域 ==========

    /**
     * 轮次调度 — 独占 [decideLock], 序列化"读状态 + 启动轮次"的决策.
     *
     * 对外三个方法即全部唤醒入口: 提交用户轮次 / 提交结果 / 轮次结束.
     * 跑 round 期间 (LLM 调用) 不持锁; 决策之间互斥防 TOCTOU & 字段竞争,
     * 所有 race-free 集中在 [decide] 锁内.
     */
    private inner class RoundScheduler {
        private val state = MutableStateFlow(BossState.WAITING)

        /** 当前 user round — handleTaskAssignments 读它关联任务归属 (publish 必在 user round 进行中). */
        lateinit var currentRound: UserRound
            private set

        // Channel(UNLIMITED) 自然排队, decide 消费时取队首
        private val pendingUserRounds: Channel<UserRound> = Channel(capacity = Channel.UNLIMITED)
        private val pendingResultEvents: Channel<String> = Channel(capacity = Channel.UNLIMITED)
        private val decideLock: Mutex = Mutex()

        /** 提交用户轮次: 入队后立即尝试启动. 由 run() 调用. */
        suspend fun submitUserRound(round: UserRound) {
            pendingUserRounds.trySend(round)
            decide(postRound = false)
        }

        /** 提交结果轮次: 入队后立即尝试启动. 由终态 TaskUpdate / 落空 Cancellation 调用. */
        suspend fun submitResult(result: String) {
            pendingResultEvents.trySend(result)
            decide(postRound = false)
        }

        /** 轮次结束: postRound=true 绕过 RUNNING 守卫, 让排队中的结果轮次接续. 由 run*Round 的 finally 调用. */
        suspend fun roundFinished() = decide(postRound = true)

        /**
         * 唯一决策点 — 锁内决策是否启动轮次.
         */
        private suspend fun decide(postRound: Boolean) {
            decideLock.withLock {
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
    }

    // ========== 内部类: 关闭辅助域 ==========

    /**
     * 关闭辅助 — 登记所有已创建但尚未结束的 round，为 [shutdown] 兜底关闭服务.
     *
     * 正常流程中轮次完成时由 [runUserRound] 的 finally 关闭自身 channel 并注销;
     * 但排队未启动的轮次没有执行者关闭 channel, 若不兜底其收集方会永久挂起.
     * 因此 shutdown 时统一关闭登记表中所有未结束 round 的 channel（close 幂等）.
     */
    private class ShutdownHelper {
        private val userRounds: MutableSet<UserRound> = ConcurrentHashMap.newKeySet()
        fun record(round: UserRound) = run { userRounds += round }
        fun remove(round: UserRound) = run { userRounds -= round }
        fun closeAll() {
            userRounds.forEach { it.channel.close() }
            userRounds.clear()
        }
    }
}
