package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.isTerminal
import io.github.yeyi.agent.llm.ContentPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

// ===== Types =====

private enum class BossState { WAITING, RUNNING }

/**
 * 单个用户轮次 —— 持有本轮事件总线与终局闸。
 *
 * 事件总线用全量回放广播（replay = Int.MAX_VALUE，无限缓冲，emit 永不挂起），
 * 与 ReActAgent 的 [io.github.yeyi.agent.AgentTurn.events] 同构：任何时刻订阅
 * 都从 [AgentEvent.Initial] 收到完整序列，多订阅天然支持 —— 替代旧的
 * Channel 点对点队列（单消费者、close(cause) 只能抛异常无法送达领域终态）。
 *
 * 终局闸保证终态事件（Final/Failed/Canceled）恰好写入一次：所有终态写入路径
 * （正常转发 / 取消兜底 / shutdown 补发）汇聚 [emit] CAS 抢占，
 * 抢占成功者负责 emit，其余路径静默放弃 —— 终局事件全局唯一。
 */
private class UserRound(
    val id: String,
    val input: String,
    val createdAt: Long,
) {
    val events: Flow<AgentEvent> = MutableSharedFlow(replay = Int.MAX_VALUE)

    private val terminal = AtomicBoolean(false)

    fun emit(event: AgentEvent): Boolean {
        if (event.isTerminal && !terminal.compareAndSet(false, true)) return false
        (events as MutableSharedFlow).tryEmit(event) // 无限缓冲下恒成功，无需挂起
        return true
    }
}

/**
 * 单个任务的状态快照.
 *
 * @property taskId 任务 ID
 * @property task 任务描述文本
 * @property events 任务收到的事件列表，通过 [AgentEvent] 类型区分状态
 * @property terminated 是否处于终态（收到 Final 或 Failed 事件）
 */
public data class TaskState(
    public val taskId: String,
    public val task: String,
    internal val roundId: String,
    internal val query: String,
    internal val createdAt: Long,
    public val events: MutableList<AgentEvent> = mutableListOf(),
) {
    public val terminated: Boolean get() = events.lastOrNull()?.isTerminal == true
}

/**
 * 一个 round 内所有任务的状态快照，作为 [BossAgent.tasksState] Flow 的推送单元.
 *
 * @property roundId round ID
 * @property query 该 round 的用户意图
 * @property createdAt 任务组创建时间，用于 UI 排序
 * @property tasks 该 round 内所有任务的 [TaskState] 列表
 * @property terminated 是否所有任务都处于终态
 * @property latestChanged 最新有状态变化的任务
 */
public data class TasksState(
    public val roundId: String,
    public val query: String,
    public val createdAt: Long,
    public val tasks: List<TaskState>,
    public val latestChanged: TaskState
) {
    public val terminated: Boolean get() = tasks.all { it.terminated }
}

// ===== BossAgent =====

public class BossAgent internal constructor(
    private val innerAgent: Agent.Streamable,
    private val scope: CoroutineScope,
    private val onShutdown: () -> Unit = {},
) : Agent, Agent.Shutdownable {
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
     *
     * 投递语义: 不回放（replay = 0，订阅者只收订阅后的事件）——正因如此,
     * **订阅后发射的事件不丢**：缓冲（extra 256）满时 emit 挂起等待订阅者消费
     * （SUSPEND），绝不 DROP。代价是背压：慢订阅者会把报告轮次的 collect 协程
     * 挂起，进而拖住轮次调度——这是"订阅后不丢"的必然取舍。
     * 度量口径是瞬时积压量而非轮总量：稳态下生产速率（LLM token 间隔）远慢于
     * 消费速率，缓冲只在订阅者瞬时卡顿时吸收积压；256 ≈ 数秒卡顿容忍度，
     * 超限由 SUSPEND 背压传导。
     */
    public val report: Flow<AgentEvent> = MutableSharedFlow(extraBufferCapacity = 256)

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
        val queryText = (query.parts.firstOrNull() as? ContentPart.Text)?.text
            ?: query.parts.joinToString(" ") { it.toString() }
        val userRound = UserRound(
            UUID.randomUUID().toString(),
            queryText,
            System.currentTimeMillis(),
        )
        // 关闭检查与登记合并为一道闸 —— record 在已 shutdown 时拒绝。
        // 单独用 "scope 是否还活着" 判断不够：shutdown 若恰好插在检查与登记之间，
        // 登记进去的轮次既无执行者（scope 已取消）也无人补发终局事件，收集方永久挂起。
        if (!shutdownHelper.record(userRound)) {
            // 已关闭 —— 按终态语义抛 ISE。flow 尚未交给调用方（throw 在 return 之前），
            // 这一轮的事件总线无人可达，无需补发终局。
            throw Agent.Shutdownable.ShutdownError()
        }
        scope.launch { scheduler.submitUserRound(userRound) }
        // 轮次流 = 事件总线的 transformWhile 包装：终态事件（自身被发射）即流边界，
        // collect 方必然收到三选一领域事件且流随之完成；全量回放保证多订阅者各自
        // 从 Initial 收到完整序列。注销不在此处做：调用方可能 run 后不 collect（流惰性），
        // 由 runUserRound 完成时注销，未跑到的轮次由 shutdown 兜底补发终局。
        return userRound.events.transformWhile { event -> emit(event); !event.isTerminal }
    }

    /**
     * 关闭 BossAgent — 取消 [scope], 停止所有 boss/pasture 的后台任务,
     * 并对所有未结束 round 补发 [AgentEvent.Canceled] 终局事件终止进行中的 run flow.
     * 之后 boss LLM 不会再被新事件触发; pasture 的 running jobs 也会被取消.
     * 最后触发 [onShutdown] 钩子 (由构造方注入, 用于关闭外部资源如 MCP 连接).
     * 调用方负责在不再使用 boss 时调用本方法 (e.g., 在应用关闭时).
     *
     * 终止协议: 进行中及尚未被收集的 run flow 均以 [AgentEvent.Canceled] 领域事件收尾 ——
     * 不静默截断, 与 [Agent.Shutdownable] 的统一约定一致. 内层 agent 同时被显式关闭
     * （其执行域不再随 boss scope 级联）.
     * 关闭后调用 [run] 抛 [Agent.Shutdownable.ShutdownError].
     */
    public override fun shutdown() {
        val cause = CancellationException(null, Agent.Shutdownable.ShutdownError())
        shutdownHelper.release(cause)
        // 内层 agent 的执行域必须显式终止：架构 1 靠 scope.cancel() 的结构化并发
        // 隐式级联杀死 inner 执行体；架构 2 下 inner 回合跑在自己的私有 scope，
        // 该级联失效 —— 不显式 shutdown，boss 关闭后 inner 仍会烧 token 到自然终局。
        (innerAgent as? Agent.Shutdownable)?.shutdown()
        scope.cancel(cause); onShutdown()
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

    /**
     * 用户轮 — 事件经 [UserRound.emit] (终局闸 CAS) 写入轮次事件总线,
     * finally 注销轮次登记。
     */
    private suspend fun runUserRound(userRound: UserRound) = runRound(
        query = AgentQuery.text(userRound.input),
        emit = { userRound.emit(it) },
        onFinish = { shutdownHelper.remove(userRound) },
    )

    /**
     * 报告轮 — 事件直发 report 热流 (SUSPEND 背压, 订阅后不丢),
     * 终局三选一全部转发: 每轮必以终结事件收尾。
     */
    private suspend fun runResultRound(result: String) {
        runRound(
            query = AgentQuery.text(result, label = REPORT_LABEL),
            emit = { (report as MutableSharedFlow<AgentEvent>).emit(it) },
        )
    }

    /**
     * 统一轮次执行体 — user 轮与报告轮共享同一执行骨架:
     * `innerAgent.runStream(query)` 流式 collect 转发 → 取消/关闭兜底补发 [AgentEvent.Canceled] →
     * finally 轮次收尾。差异仅经参数注入:
     * - [query] 本轮注入 innerAgent 的提问 (user 轮纯文本, 报告轮带 [REPORT_LABEL])
     * - [emit] 事件发射器 (user 轮经 [UserRound.emit] 终局闸 CAS, 报告轮直发 report 热流)
     * - [onFinish] finally 收尾钩子 (user 轮注销登记, 报告轮无)
     *
     * 取消兜底与收尾统一包 [NonCancellable]: 取消协程中挂起调用会立即抛
     * [CancellationException], 兜底补发与轮次收尾必须落地, 否则报告轮/登记轮次
     * 会以无终态形式静默截断, 收集方永久挂起。
     */
    private suspend fun runRound(
        query: AgentQuery,
        emit: suspend (AgentEvent) -> Unit,
        onFinish: suspend () -> Unit = {},
    ) {
        try {
            innerAgent.runStream(query).collect { e -> emit(e) }
        } catch (e: Agent.Shutdownable.ShutdownError) {
            // 与 shutdown 的竞态: 轮次已受理但执行体尚未开跑, inner 已先被 shutdown →
            // runStream 直接抛 ShutdownError。补发 Canceled 终态 —— user 轮终局闸 CAS
            // 保证与 shutdownHelper.release() 的补发不重复, 收集方不会双收终局。
            withContext(NonCancellable) {
                emit(AgentEvent.Canceled(CancellationException(null, e)))
            }
        } catch (e: CancellationException) {
            // 取消唯一来源是 shutdown (release() → inner.shutdown() → scope.cancel()):
            // 可能在内层 Canceled 事件送达 collect 之前抢先取消本收集协程 —— 事件未及
            // 转发即被取消, 订阅方会看到一轮被截断却无终态。此处兜底补发 Canceled,
            // 保证轮次无论走哪条取消路径都以终结事件收尾, 绝不静默截断。
            withContext(NonCancellable) { emit(AgentEvent.Canceled(e)) }
        } finally {
            // 轮次收尾 (含取消异常路径): 调度器必须回到 WAITING, 否则后续轮次永远
            // 排队不执行 —— boss 静默僵死。NonCancellable: 取消路径上 finally 内的
            // 挂起调用会立即抛取消异常, 收尾必须落地。
            withContext(NonCancellable) {
                onFinish()
                scheduler.roundFinished()
            }
        }
    }

    // ========== 内部类: 任务追踪域 ==========

    /**
     * 任务追踪 — 独占 [lock], 管理活跃任务表与看板状态.
     *
     * [apply] 在单次持锁内完成: 事件落账 + 看板推送 + 整轮终态判断 + 格式化结果 + 归档删除,
     * 消除原实现"跨两段锁"的理论缝隙 (快照删除与按 roundId 过滤不一致).
     * 整轮终态返回格式化 result 供调度器唤醒续轮; 非终态返回 null.
     */
    private class TaskTracker {
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
            val terminated = task.terminated
            val roundTasks = tasks.values.filter { it.roundId == task.roundId }

            roundTasks
                .map { ts -> ts.copy(events = ts.events.toMutableList()) }
                .let { TasksState(task.roundId, task.query, task.createdAt, it, task) }
                .let { states.tryEmit(it) }

            if (!terminated || roundTasks.any { !it.terminated }) return null

            roundTasks.forEach { tasks.remove(it.taskId) }

            // 整轮终态: 格式化 + 归档删除, 单锁内完成。
            // 渲染模板区分成功/失败: 成功只回传最终回复文本, 失败回传错误原因,
            // 不再向模型倾倒整个终态事件文本。
            return roundTasks.joinToString("\n") { t ->
                val result = when (val terminal = t.events.last()) {
                    is AgentEvent.Final -> terminal.result.message.content ?: "Unknown result"
                    is AgentEvent.Failed -> terminal.cause.toString()
                    else -> error("Unexpected terminal event: $terminal")
                }
                "- ${t.taskId}: $result"
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
     * 关闭辅助 — 登记所有已创建但尚未结束的 round，为 [shutdown] 兜底补发终局事件.
     *
     * 正常流程中轮次完成时由 [runUserRound] 的 finally 注销自身;
     * 但排队未启动的轮次没有执行者转发事件, 若不兜底其收集方会永久挂起.
     * 因此 shutdown 时统一对登记表中所有未结束 round 补发 [AgentEvent.Canceled] 终局事件
     * （经 [UserRound.emit] CAS, 与正常转发/取消兜底路径互斥, 终局全局唯一）.
     *
     * [record] 与 [release] 互斥: shutdown 之后不再登记任何 round ——
     * 否则会出现"shutdown 已扫过登记表、之后才登记的 round 永远没人执行、终局永不补发"
     * 的窗口, 收集方永久挂起.
     */
    private class ShutdownHelper {
        private val lock = Any()
        private val userRounds: MutableSet<UserRound> = ConcurrentHashMap.newKeySet()

        /** shutdown 已发生 —— 之后 [record] 一律拒绝. */
        private var released = false

        /** 登记 round. 返回 false = 已 shutdown, 调用方须自行终止该轮. */
        fun record(round: UserRound): Boolean = synchronized(lock) {
            if (released) return false
            userRounds += round; return true
        }

        fun remove(round: UserRound) = synchronized(lock) { userRounds -= round }

        fun release(cause: CancellationException) {
            // 快照出锁再逐个 emit：锁内只做登记表清理。当前 tryEmit 在 Int.MAX_VALUE 容量下
            // 恒成功不挂起，但"锁内 emit 永不挂起"是脆弱前提 —— 未来容量收紧或改用挂起 emit
            // 会在持 JVM 监视器锁时挂起，让 shutdown 死锁。与 record/remove 的锁纪律解耦。
            val rounds = synchronized(lock) {
                released = true
                userRounds.toList().also { userRounds.clear() }
            }
            rounds.forEach { it.emit(AgentEvent.Canceled(cause)) }
        }
    }
}
