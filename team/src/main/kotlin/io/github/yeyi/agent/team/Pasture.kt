package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.llm.ContentPart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private enum class Status { PENDING, READY, RUNNING, DONE, FAILED, CANCELED }

private class DagNode(
    val assignment: TaskAssignment,
    var status: Status,
    var job: Job? = null,
    // 上游结果缓存: key=上游 taskId, value=Final event.content(给下游 task 的 context prepend 用)
    val upstreamResults: MutableMap<String, String> = mutableMapOf(),
    // 从 beast 来的终态。content 已在写入前经 [ensureContent] 补齐, 消费方无需再判空。
    var result: AgentEvent.Final? = null,
    // 从 beast 来的 failed 或 pasture 自己产生的异常
    var error: Throwable? = null,
)

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
            bulletinBoard.events.onSubscription { subscribed.complete(Unit) }.collect { event ->
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
        var shouldFailFromUpstream = false
        val toDispatch = dagLock.withLock {
            val node = dag[taskId] ?: return
            // 只推进仍在等待的节点: 多个上游几乎同时完成时, cascade 会并发地对同一节点
            // 调用本方法, 不加这道闸会重复 dispatch —— 同一个任务跑两遍。
            if (node.status != Status.PENDING) return
            val upstream = node.assignment.dependsOn.mapNotNull { dag[it] }
            when {
                // 上游有失败/取消, 直接标 FAILED, emit TaskUpdate
                upstream.any { it.status == Status.FAILED || it.status == Status.CANCELED } -> {
                    shouldFailFromUpstream = true
                    null
                }
                // 所有上游都 DONE, 合并上游结果后标 READY
                upstream.all { it.status == Status.DONE } -> {
                    val results = upstream.associate {
                        it.assignment.taskId to it.result!!.result.message.content!!
                    }
                    dag[taskId] = node.also {
                        it.status = Status.READY
                        it.upstreamResults.putAll(results)
                    }
                    dag[taskId]
                }

                else -> null  // 还有上游 PENDING/RUNNING, 等 cascade 触发
            }
        }
        if (shouldFailFromUpstream) {
            handleTerminal(taskId, RuntimeException("Upstream task failed"))
            cascade(taskId)
        } else {
            toDispatch?.let { dispatch(it) }
            if (toDispatch == null && dagLock.withLock { dag[taskId]?.status == Status.FAILED }) {
                cascade(taskId)
            }
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
        val beast: Beast =
            withContext(Dispatchers.IO) { assembler.assemble(node.assignment.selection) }

        // 3) launch job
        val job = scope.launch {
            beast.run(AgentQuery.text(userInput))
                .onCompletion { beast.shutdown() }
                .collect { event ->
                    when (event) {
                        is AgentEvent.Final -> {
                            dagLock.withLock { dag[taskId]?.result = event.ensureContent() }
                            handleTerminal(taskId, null)
                        }

                        is AgentEvent.Failed -> handleTerminal(taskId, event.cause)
                        // 取消终局：标 CANCELED（而非落进 else 被标 DONE——下游会拿到空结果）。
                        // Canceled 后 collect 正常结束、本回合不抛；node.job.cancel() 掐订阅时
                        // 抛出的 CancellationException 直接上抛，job 以取消收尾，无需 catch。
                        is AgentEvent.Canceled -> handleTerminal(taskId, event.cause)
                        else -> bulletinBoard.progressEvent(TaskUpdate(taskId, event))
                    }
                }
        }

        dagLock.withLock {
            val n = dag[taskId] ?: return@withLock
            n.job = job
            // 只做单向推进: 已终态的节点不得被翻回 RUNNING。
            // job 可能在本次写入前就跑完并写了 DONE, 无条件写 RUNNING 会把终态抹掉,
            // 下游 cascade 永远等不到上游 DONE, 整轮卡死。
            if (n.status == Status.PENDING || n.status == Status.READY) n.status = Status.RUNNING
        }

        job.invokeOnCompletion { scope.launch { cascade(taskId) } }
    }

    /**
     * 处理 task 终态: 标 Status, emit TaskUpdate 给 BossAgent.
     */
    private suspend fun handleTerminal(taskId: String, e: Throwable? = null) {
        val newStatus = when (e) {
            null -> Status.DONE
            is CancellationException -> Status.CANCELED
            else -> Status.FAILED
        }

        val event = dagLock.withLock {
            val node = dag[taskId] ?: return
            if (node.status == Status.DONE || node.status == Status.FAILED || node.status == Status.CANCELED) return
            dag[taskId] = node.also {
                it.status = newStatus
                if (e != null) it.error = e
            }
            if (e == null) node.result!!
            else AgentEvent.Failed(e)
        }
        bulletinBoard.progressEvent(TaskUpdate(taskId, event))
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
     * RUNNING 直接标 CANCELED 再调用 job.cancel()（打断订阅；dispatch 的 finally 由
     * beast.shutdown() 终止在途回合）；DONE/FAILED/CANCELED 幂等 no-op.
     */
    private suspend fun handleCancellation(e: Cancellation) {
        val node = dagLock.withLock { dag[e.taskId] } ?: return
        when (node.status) {
            Status.DONE, Status.FAILED, Status.CANCELED -> return  // 幂等
            else -> {
                handleTerminal(e.taskId, CancellationException("task canceled"))
                cascade(e.taskId)
                node.job?.cancel()
            }
        }
    }
}

/**
 * 保证 Final 的 content 可转述: 非空原样返回; 为 null(仅出现在可终结工具短路路径)
 * 时以最后一次迭代的助手文本 + 该批工具结果的文本部分按序拼装。
 *
 * 空正文对前台消费方是协议的一部分——工具已把产出直接呈现给用户;但 beast 是
 * 后台 agent, Final 是其产出唯一出口(下游 task 的 context、BossAgent 汇总报告),
 * 故在此补齐, 让 [DagNode.result] 恒可读。
 *
 * 取「最后一次迭代」而非全部记录: 早期批次的中间结果不属于本次产出。
 */
private fun AgentEvent.Final.ensureContent(): AgentEvent.Final {
    if (result.message.content != null) return this
    val last = result.iterationRecords.last()
    val text = (listOfNotNull(last.callExplanation) + last.calls.map { call ->
        call.result.parts.filterIsInstance<ContentPart.Text>().joinToString("\n") { it.text }
    }).joinToString("\n")
    return AgentEvent.Final(result.copy(message = result.message.copy(content = text)))
}
