package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import java.util.concurrent.Semaphore

/**
 * 一轮 run —— 轮次身份，也是本轮状态的唯一所有者。
 *
 * 持有终局闸：闸保护的是"本轮的终局裁决"（判空 → 发 Final → 关信箱 → 还槽），
 * 终局路径调的就是 `turn.lock()` —— 语义准确；投递侧也取它
 * （见 [io.github.yeyi.agent.ReActAgent.steer]），两边取同一把闸，
 * "投递"与"终局裁决"才互斥。
 *
 * 两个成员直接公开：调用方用它们的机械能力（投递 / 排空 / 判空 / 关闭、登记 / 打断），
 * 不在 AgentTurn 上再铺一层转发方法 —— 转发只是把调用点搬家，没有多出语义。
 *
 * 本轮的执行态也归它：初始查询、调模型的方式、迭代计数、工具调用记录 ——
 * 与轮次同生共死的东西不散成并列参数。
 */
internal class AgentTurn(
    /** 本轮的初始查询 —— 必需且不可变。 */
    val query: AgentQuery,

    /**
     * 本轮调模型的方式 —— 只收请求。
     *
     * 策略不必拿到本轮：需要往出口发事件的分支（流式 delta）由调用方
     * 在闭包里自引用本轮完成。
     */
    val llmCall: suspend (ChatRequest) -> ChatResponse,
) {
    /**
     * 终局闸门 —— 互斥"抢占 Final"与"投递 steering"两个动作.
     *
     * 用 [Semaphore] 而非 [java.util.concurrent.locks.ReentrantLock]: 终局窗口内的 [AgentHook]
     * 回调与 `emit` 都是挂起点，协程恢复后可能换线程; ReentrantLock 是线程亲和的, 换线程 unlock 会抛
     * IllegalMonitorStateException —— 表现为 agent 在已发出 Final 之后又报 Failed。
     * Semaphore 的 permit 与线程无关, release 可由任意线程执行。
     *
     * 代价: 不可重入 —— collector / AgentHook 回调内同步调用 steer() 会死锁
     * (本就是硬约束, 见 [Agent.Steerable.steer] 的 KDoc)。
     */
    private val gate = Semaphore(1)

    /**
     * 本轮事件总线 —— 全历史回放广播（replay = Int.MAX_VALUE，惰性倍增，无预分配）。
     *
     * emit 永不挂起（无限缓冲）：执行体速度与订阅者彻底解耦，也断了终局闸
     * 横跨挂起点的死锁窗口。任何时刻订阅都从 [AgentEvent.Initial] 开始收到完整序列。
     */
    val events = MutableSharedFlow<AgentEvent>(replay = Int.MAX_VALUE, extraBufferCapacity = 0)

    /** 本轮的在途信箱 —— 纯缓冲，不取闸。 */
    val steering = SteerInbox()

    /**
     * 本轮收尾信号 —— 完成即代表"槽位已还、信箱已关"。
     *
     * 由执行体的 finally 完成后作为 [Agent.Cancelable.cancel] 的返回句柄：
     * 调用方 [kotlinx.coroutines.Job.join] 它，即可确保旧轮彻底退场后再开新一轮。
     */
    val terminated = CompletableDeferred<Unit>()

    /** 本轮执行体 job —— cancel() 的打断目标，runTurn 启动执行体时登记。 */
    var job: kotlinx.coroutines.Job? = null

    /** 本轮迭代计数 —— 自产累积量，非输入，故不进构造器。 */
    var iterations: Int = 0

    /** 本轮工具调用记录 —— 同上。 */
    val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()

    /**
     * 本轮当前迭代的上下文 —— 一轮迭代一份，迭代内所有钩子点共用同一个对象。
     *
     * 由 agent 在每轮迭代开始时刷新（`buildContext(turn.iterations)`）。
     */
    lateinit var iterationContext: AgentContext

    fun lock() = gate.acquireUninterruptibly()
    fun unlock() = gate.release()
}

/**
 * 转向消息收纳 —— 本轮的信箱，纯缓冲。
 *
 * 不持闸、不取闸：闸是 agent 层的裁决机制（"投递"与"终局裁决"互斥），
 * 由取闸的两侧各自负责 —— 投递侧见 [io.github.yeyi.agent.ReActAgent.steer]，
 * 终局侧见 `finalize`。信箱只提供 Channel 上的原子操作：投递、排空、判空、关闭。
 */
internal class SteerInbox {
    private val inbox = Channel<AgentQuery>(Channel.UNLIMITED)

    /**
     * 投递在途指令。
     *
     * **调用方必须持有本轮闸** —— 闸护的是"投递"与"终局裁决"互斥：
     * 投递若与判空/发 Final/关信箱交错，指令会既被接受又没被消费。
     */
    fun add(query: AgentQuery): Boolean = inbox.trySend(query).isSuccess

    /**
     * 排空在途指令。不取闸。
     *
     * 排空只由 agent 的执行体调用（串行），与投递并发也无妨 ——
     * 挤在排空之后的那条由下一个检查点（迭代头）消费，不会丢。
     */
    fun drain(): List<AgentQuery> {
        val queries = mutableListOf<AgentQuery>()
        while (true) {
            val query = inbox.tryReceive().getOrNull() ?: return queries
            queries.add(query)
        }
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun isEmpty(): Boolean = inbox.isEmpty

    fun close() = inbox.close()
}
