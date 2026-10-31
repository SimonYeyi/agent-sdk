package io.github.yeyi.agent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.buffer
import kotlinx.coroutines.flow.channelFlow
import java.util.concurrent.Semaphore

/**
 * 一轮 run —— 轮次身份，也是本轮状态的唯一所有者。
 *
 * 持有终局闸：闸保护的是"本轮的终局裁决"（判空 → 发 Final → 关信箱 → 还槽），
 * 终局路径调的就是 `turn.lock()` —— 语义准确；信箱的投递 / 排空也要用它，
 * 于是信箱持有本轮的引用。
 *
 * 两个成员直接公开：调用方用它们的机械能力（投递 / 排空 / 判空 / 关闭、登记 / 打断），
 * 不在 AgentTurn 上再铺一层转发方法 —— 转发只是把调用点搬家，没有多出语义。
 */
internal class AgentTurn {
    /**
     * 终局闸门 —— 互斥"抢占 Final"与"投递 steering"两个动作.
     *
     * 用 [Semaphore] 而非 [java.util.concurrent.locks.ReentrantLock]: 终局窗口会跨越挂起点 ('emit' 是 suspend),
     * 协程恢复后可能换线程; ReentrantLock 是线程亲和的, 换线程 unlock 会抛
     * IllegalMonitorStateException —— 表现为 agent 在已发出 Final 之后又报 Failed。
     * Semaphore 的 permit 与线程无关, release 可由任意线程执行。
     *
     * 代价: 不可重入 —— collector / AgentHook 回调内同步调用 steer() 会死锁
     * (本就是硬约束, 见 [Agent.Steerable.steer] 的 KDoc)。
     */
    private val gate = Semaphore(1)
    val flow = CancelableFlow()
    val steering = SteerInbox(this)   // 构造期传 this 是安全的：信箱只在调用期用它取闸

    fun lock() = gate.acquireUninterruptibly()
    fun unlock() = gate.release()
}

/**
 * 转向消息收纳 —— 本轮的信箱，只做机械操作。
 *
 * 锁不在这里，而在 [AgentTurn]：闸保护的是"本轮的终局裁决"，终局路径调的是 `turn.lock()`。
 * 本类的投递 / 排空也要用它，故持有本轮引用 —— 双向依赖，语义闭合。
 */
internal class SteerInbox(private val turn: AgentTurn) {
    private val inbox = Channel<AgentQuery>(Channel.UNLIMITED)

    /** 投递在途指令。闸内执行，与终局裁决互斥。 */
    fun add(query: AgentQuery): Boolean {
        turn.lock()
        try {
            return inbox.trySend(query).isSuccess
        } finally {
            turn.unlock()
        }
    }

    /** 排空在途指令。闸内执行。 */
    fun drain(): List<AgentQuery> {
        turn.lock()
        try {
            val queries = mutableListOf<AgentQuery>()
            while (true) {
                val query = inbox.tryReceive().getOrNull() ?: return queries
                queries.add(query)
            }
        } finally {
            turn.unlock()
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun isEmpty(): Boolean = inbox.isEmpty

    fun close() = inbox.close()
}

/**
 * 可中断的执行容器 —— 把"在子协程里跑一段产生事件的逻辑，并允许外部打断"这套语义收在一处。
 *
 * 每轮一份 —— 执行体登记下沉到轮次，跨轮不再有可被顶掉的全局单槽。
 *
 * 设计要点：
 * - 执行体跑在收集方的 scope 里（[channelFlow] 的子协程），因此收集方被取消时，
 *   结构化并发照旧向下传导，执行体一并停止；
 * - [cancel] 只取消这一个子协程 —— 协程取消向下传播、不向上，收集方协程本身不被取消，
 *   但收集会因 [CancellationException] 而结束，沿用"collect 外围 try/catch 取消异常"的既有用法；
 */
internal class CancelableFlow {
    private enum class Attach { Ok, Canceled, Repeated }

    private val lock = Any()
    private var job: Job? = null
    private var canceled = false

    /**
     * 构造本轮的可取消流。
     *
     * [Attach.Repeated] 抛出的 [RepeatedCollectionException] 是"这次收集不是拥有者"的
     * 内部信号 —— 轮次收尾据此跳过，避免重复收集提前释放仍在运行的轮次的槽位与信箱。
     */
    fun <T> with(body: suspend (emit: suspend (T) -> Unit) -> Unit): Flow<T> = channelFlow {
        // LAZY 启动：执行体在 await() 之前不运行 —— 一旦开始收集，执行体必已登记进本轮，
        // 此后的 cancel() 不会落空。
        val producer = async(start = CoroutineStart.LAZY) { body { send(it) } }
        when (attach(producer)) {
            Attach.Ok -> Unit
            // 本轮已被取消作废、且从未被收集 → 不再执行；以取消异常收尾，
            // 收集方不会拿到"零事件 + 正常结束"的静默出口。
            Attach.Canceled -> throw CancellationException("flow is canceled")
            // 同一轮被重复收集（含跑完后再收集一次）—— 跑不起来的调用错误，不是运行时错误。
            Attach.Repeated -> throw RepeatedCollectionException()
        }
        // 取消异常原样上抛：打断与"执行体自身被取消"对收集方是同一种信号，
        // 调用方沿用 collect 外围 try/catch(CancellationException) 即可。
        producer.await()
    }.buffer(Channel.RENDEZVOUS)

    /**
     * 登记执行体 —— 与打断同锁，二者必有其一先发生：
     * 登记先于打断 ⟹ 打断拿到执行体并取消它；打断先于登记 ⟹ 流已取消，登记被拒。
     */
    private fun attach(job: Job): Attach = synchronized(lock) {
        when {
            // 曾收集过 —— 含已跑完后再收集一次。
            this.job != null -> Attach.Repeated
            // 被 cancel 作废、从未收集 → 迟收集。
            canceled -> Attach.Canceled
            else -> {
                this.job = job; Attach.Ok
            }
        }
    }

    /**
     * 打断本轮执行体（对已结束的 job 是无害的空操作）。
     *
     * @return true = 取消成功，打断执行体；false = 取消失败，无执行体可取消
     */
    fun cancel(): Boolean = synchronized(lock) {
        canceled = true
        return job?.cancel()?.let { true } ?: false
    }
}

/**
 * 同一轮次被重复收集 —— 调用方错误。
 *
 * **抛出时机**：不是 [Agent.run] / [Agent.Streamable.runStream] 调用时，而是**收集时** ——
 * 违规的那一次 collect 一进入流就收到本异常。重复收集只有到收集时才会被发现：
 * `run()` 只建轮、不碰执行体，调用它本身不会抛本异常。
 * （`run()` 调用时确实可能抛 [IllegalStateException] —— 那是"已有活跃轮次"的并发拒绝，
 * 与重复收集无关。）
 *
 * **不转 [AgentEvent.Failed]**：Failed 的唯一产地是轮次执行体内部的失败路径，
 * 而重复收集发生在执行体启动之前（执行体 LAZY，从未运行）—— 这一轮没有产出任何
 * 事件，也就没有失败可言。正在运行的那一轮的收集者同样收不到这条失败：
 * 失败事件发在这一轮共享的流上，若因第二次收集伪造一条，污染的正是真正的收集者。
 *
 * 继承 [IllegalStateException]：对外仍表现为"调用方错误"，但内部可以精确识别 ——
 * 轮次收尾据此判断"这次收集是不是拥有者"，非拥有者的收集不得触碰仍在运行的轮次
 * 的槽位与信箱；用专属类型而非笼统的 ISE，是为了不和收集方自己抛出的
 * [IllegalStateException] 混淆。
 */
internal class RepeatedCollectionException :
    IllegalStateException("a turn flow may only be collected once")
