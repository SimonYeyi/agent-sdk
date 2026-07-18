package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.llm.ChatMessage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal class Pasture(
    private val assembler: BeastAssembler,
    private val scope: CoroutineScope,
) {
    // ===== DAG 调度状态 =====
    // dag map key=taskId, value=DagNode. 全局唯一下游调度边界,不是按 round 分割.
    // Pasture 完全不感知 roundId —— roundId 是 BossAgent 内部状态,不在事件流中.
    private val dag: MutableMap<String, DagNode> = mutableMapOf()
    private val dagLock: Mutex = Mutex()

    // lateinit: 由 [observe] 赋值, 后续 collect / dispatch 统一从此字段读取.
    // 未初始化访问抛 UninitializedPropertyAccessException (消息固定但可读).
    private lateinit var bulletinBoard: BulletinBoard

    private enum class Status { PENDING, READY, RUNNING, DONE, FAILED, CANCELED }

    private class DagNode(
        val assignment: TaskAssignment,
        var status: Status,
        var job: Job? = null,
        // 上游结果缓存: key=上游 taskId, value=Final event.content(给下游 task 的 context prepend 用)
        val upstreamResults: MutableMap<String, String> = mutableMapOf(),
        // 自己作为上游的 final 结果: 仅 DONE 状态填充.
        var result: String? = null,
        // 自己作为 task 的 final event(Failed 时存 throwable 的 message)
        var failureMessage: String? = null,
    )

    /**
     * 启动后台 collect 协程, 订阅 [bb] 的原始 [BulletinBoard.events] 统一流, 内部 when 区分
     * TaskAssignments / Cancellation 分发到对应处理逻辑. Pasture 在 `events` 上注册**一个**
     * collector — 用 [onSubscription] 回调 + [CompletableDeferred] 同步等到"**自己的**" collector
     * 注册完成.
     *
     * 两次 observe 会抛 [IllegalStateException]. 由 [BossAgentBuilder.build] 在构造完成后
     * runBlocking 调用一次.
     *
     * 注意: Pasture 订阅的是 `events` 统一流 (不是 publishEvents), 因为 TaskAssignments
     * 是 PublishEvent 的实现类,会出现在 events 流中. 类型检查区分.
     */
    internal suspend fun observe(bb: BulletinBoard) {
        check(!::bulletinBoard.isInitialized) { "Pasture.observe() must be called only once" }
        bulletinBoard = bb
        val subscribed = CompletableDeferred<Unit>()
        scope.launch {
            bulletinBoard.events
                .onSubscription { subscribed.complete(Unit) }
                .collect { event ->
                    when (event) {
                        is TaskAssignments -> handleTaskAssignments(event)
                        is Cancellation -> handleCancellation(event)
                        // 其他事件 (TaskUpdate 等) Pasture 不关心, 显式 no-op
                        // 让编译器在 BulletinEvent 加新类型时强制更新此 when.
                        else -> Unit
                    }
                }
        }
        subscribed.await()
    }

    /**
     * 处理整批 TaskAssignments: 注册到 DAG, 立即 dispatch READY 节点, 推进 PENDING 节点.
     * 批次只是 publish 边界, 不是调度边界 —— DAG 是全局的, 跨 batch 引用通过 knownTaskIds 校验.
     */
    private suspend fun handleTaskAssignments(group: TaskAssignments) {
        val newNodes = dagLock.withLock {
            group.tasks.map { task ->
                val node = DagNode(
                    assignment = task,
                    status = if (task.dependsOn.isEmpty()) Status.READY else Status.PENDING,
                )
                dag[task.taskId] = node
                node
            }
        }
        for (node in newNodes) {
            when (node.status) {
                Status.READY -> dispatch(node)
                Status.PENDING -> tryAdvancePending(node.assignment.taskId)
                else -> Unit // RUNNING/DONE/FAILED/CANCELED should not occur at registration
            }
        }
    }

    /**
     * 尝试推进 PENDING 节点: 检查上游依赖状态, 满足条件则标 READY 并 dispatch.
     *
     * @return 要 dispatch 的节点, 或 null (不该 dispatch)
     */
    private suspend fun tryAdvancePending(taskId: String) {
        val toDispatch = dagLock.withLock {
            val node = dag[taskId] ?: return
            val upstream = node.assignment.dependsOn.mapNotNull { dag[it] }
            when {
                // 上游有失败/取消, 直接标 FAILED, 不等 dispatch
                upstream.any { it.status == Status.FAILED || it.status == Status.CANCELED } -> {
                    dag[taskId] = node.also {
                        it.status = Status.FAILED
                        it.failureMessage = "Upstream task failed"
                    }
                    null
                }
                // 所有上游都 DONE, 合并上游结果后标 READY
                upstream.all { it.status == Status.DONE } -> {
                    val results = upstream.associate { it.assignment.taskId to (it.result ?: "") }
                    dag[taskId] = node.also {
                        it.status = Status.READY
                        it.upstreamResults.putAll(results)
                    }
                    dag[taskId]
                }
                else -> null  // 还有上游 PENDING/RUNNING, 等 cascade 触发
            }
        }
        toDispatch?.let { dispatch(it) }
        if (toDispatch == null && dagLock.withLock { dag[taskId]?.status == Status.FAILED }) {
            cascade(taskId)
        }
    }

    /**
     * 派发 Ready 节点: 合并上游结果到 context, 组装 beast, 启动 job.
     * job 完成时 handleTerminal 标终态 + emit TaskUpdate, 然后 cascade 下游.
     */
    private suspend fun dispatch(node: DagNode) {
        val taskId = node.assignment.taskId

        // 1) 合并上游 results 到 context
        val mergedContext = buildString {
            node.upstreamResults.forEach { (id, result) ->
                append("[$id]\n$result\n\n")
            }
            node.assignment.context?.let { append(it) }
        }.takeIf { it.isNotEmpty() }

        val userInput = if (mergedContext == null) node.assignment.task
                        else "$mergedContext\n\n${node.assignment.task}"

        // 2) assemble beast (可能 IO 耗时, 放 Dispatchers.IO)
        val beast: Beast = withContext(Dispatchers.IO) { assembler.assemble(node.assignment.selections) }

        // 3) launch job
        val job = scope.launch {
            try {
                beast.run(userInput) { event ->
                    if (event is AgentEvent.Final) {
                        val content = event.result.message.content
                        dagLock.withLock { dag[taskId]?.let { it.result = content ?: "" } }
                    }
                }
                handleTerminal(taskId, isSuccess = true)
            } catch (t: Throwable) {
                handleTerminal(taskId, isSuccess = false, throwable = t)
                if (t is CancellationException) throw t
            }
        }

        dagLock.withLock {
            val n = dag[taskId] ?: return@withLock
            dag[taskId] = n.also { it.job = job; it.status = Status.RUNNING }
        }

        // job 完成时 cascade 下游 (invokeOnCompletion 回调不是 suspend, 用 runBlocking 同步执行)
        job.invokeOnCompletion { runBlocking { cascade(taskId) } }
    }

    /**
     * 处理 task 终态: 标 Status, emit TaskUpdate 给 BossAgent.
     */
    private suspend fun handleTerminal(taskId: String, isSuccess: Boolean, throwable: Throwable? = null) {
        val node = dagLock.withLock { dag[taskId] } ?: return
        val newStatus = when {
            isSuccess -> Status.DONE
            throwable is CancellationException -> Status.CANCELED
            else -> Status.FAILED
        }
        dagLock.withLock {
            dag[taskId] = node.also {
                it.status = newStatus
                if (!isSuccess) it.failureMessage = throwable?.message ?: throwable?.toString() ?: "Unknown failure"
            }
        }

        // emit TaskUpdate (透明, 所有 task 包括 upstream 都 emit, 不带 roundId)
        val event = if (isSuccess) AgentEvent.Final(AgentResult(ChatMessage.Assistant(node.result ?: ""), 0, emptyList(), null))
                    else AgentEvent.Failed(throwable ?: IllegalStateException(node.failureMessage))
        bulletinBoard.progressEvent(TaskUpdate(taskId, event))
        // BossAgent 收到后自己判断所属 round 是否完成 → 触发续轮
    }

    /**
     * Cascade 下游: 找出所有依赖 completedTaskId 的 PENDING 节点, 尝试推进.
     * 每次 task terminal 都 cascade 一次, 保证下游及时被唤醒.
     */
    private suspend fun cascade(completedTaskId: String) {
        val dependents = dagLock.withLock {
            dag.values
                .filter { completedTaskId in it.assignment.dependsOn && it.status == Status.PENDING }
                .map { it.assignment.taskId }
                .toList()
        }
        for (depId in dependents) {
            tryAdvancePending(depId)
        }
    }

    /**
     * 处理 Cancellation: PENDING/READY 直接标 CANCELED + handleTerminal;
     * RUNNING 调用 job.cancel(); DONE/FAILED/CANCELED 幂等 no-op.
     */
    private suspend fun handleCancellation(e: Cancellation) {
        val node = dagLock.withLock { dag[e.taskId] } ?: return
        when (node.status) {
            Status.DONE, Status.FAILED, Status.CANCELED -> return  // 幂等
            Status.PENDING, Status.READY -> {
                dagLock.withLock { dag[e.taskId] = node.also { it.status = Status.CANCELED } }
                handleTerminal(e.taskId, isSuccess = false, throwable = CancellationException("task canceled"))
                cascade(e.taskId)
            }
            Status.RUNNING -> node.job?.cancel()
        }
    }
}
