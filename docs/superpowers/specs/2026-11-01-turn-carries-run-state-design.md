# 轮次承载执行态（Turn as Run Context）—— 设计

> 承接 [轮次一等公民（Turn）—— 设计](2026-10-31-turn-first-class-design.md)。
> 那一轮把"轮次"从概念提升为对象（`AgentTurn` 持有闸 / 信箱 / 流），
> 但**本轮的执行态仍散在方法参数里** —— 本轮把它收进轮次本身。

## 背景与问题

`ReActAgent` 的执行链是 `loop → loopOnce → callTools / finalize`，每层都背着一串参数：

```kotlin
private suspend fun loop(
    query: AgentQuery,
    turn: AgentTurn,
    llmCall: suspend (ChatRequest) -> ChatResponse,
    emit: suspend (AgentEvent) -> Unit,
)

private suspend fun loopOnce(
    iterations: Int,
    toolCalls: MutableList<AgentResult.ToolCallRecord>,
    turn: AgentTurn,
    llmCall: suspend (ChatRequest) -> ChatResponse,
    emit: suspend (AgentEvent) -> Unit,
): AgentResult?

private suspend fun finalize(
    response: ChatResponse,
    iterations: Int,
    toolCalls: MutableList<AgentResult.ToolCallRecord>,
    turn: AgentTurn,
    context: AgentContext,
    emit: suspend (AgentEvent) -> Unit,
): AgentResult?
```

### 参数归属

把参数按"归属于谁"摊开，问题就显形了：

| 参数 | 真实归属 | 生命周期 |
|---|---|---|
| `query` | 本轮 | 与轮次同生共死 |
| `emit` | 本轮（本轮的事件出口） | 与轮次同生共死 |
| `llmCall` | 本轮（本轮调模型的方式） | 与轮次同生共死 |
| `toolCalls` | 本轮（工具调用累积） | 与轮次同生共死 |
| `iterations` | 本轮（迭代计数） | 与轮次同生共死 |
| `turn` | 本轮（轮次身份） | 与轮次同生共死 |
| `response` / `allAsync` | 方法局部 | 单次调用 |
| `context` | 迭代派生（`AgentContext`） | 单次迭代 |
| `memory` / `toolRegistry` / `persona` / `hook` / `modalityAdapter` / `llmProvider` / `maxIterations` | agent 级 | 随 agent |

前六项**同生共死**——它们和轮次一起创建、一起终结；后两类要么是纯局部，要么本就是 agent 字段。

于是现状的结构性矛盾很清楚：**五个只属于本轮的状态，被拆成了五个并列参数，而"本轮是谁"反倒是这些参数之一**。
签名上等于宣称「`turn` 只是又一个上下文」，而它其实是这些上下文**唯一的主语**。

### 顺带暴露的重复

`run` 与 `runStream` 除了"调模型的方式"以外完全一样，却各写了一遍 `loop(...)` 调用：

```kotlin
override fun run(query: AgentQuery): Flow<AgentEvent> = runTurn { turn, emit ->
    loop(query, turn, { req -> llmProvider.chat(req) }, emit)
}

override fun runStream(query: AgentQuery): Flow<AgentEvent> = runTurn { turn, emit ->
    val streaming = StreamingRunner(llmProvider)
    loop(query, turn, { req -> streaming.run(req) { emit(AgentEvent.TextDelta(it)) } }, emit)
}
```

差异只有 `llmCall` 一个参数，却复制了整段调用。

---

## 核心决策

| 决策点 | 结论 |
|---|---|
| 收拢形状 | **形状一**：本轮执行态直接搬进 `AgentTurn` —— 轮次即一切，签名只传轮次 |
| 为何不是独立 `TurnRun` | 两者严格 1:1（一个轮次一份执行态），拆分只为"关注点纯度"付出多一层间接 —— 本轮一次事件的出口、一次调模型的方式，本就是"这一轮"的属性 |
| `AgentTurn` 职责 | 从"身份 + 机械"扩为"身份 + 机械 + 执行态"：闸 / 信箱 / 流 / 迭代计数 / 工具调用记录 / 本轮输入与调模型策略 |
| 归属判据 | 与轮次同生共死 ⟹ 进轮次；随 agent ⟹ 留在 agent 字段；单次调用 ⟹ 留作参数 |
| **构造器 = 必需且不可变** | `query`（必需输入）与 `llmCall`（必需策略）进构造器 —— 编译期强制必填、不可后改 |
| **类体初始化 = 自产累积量** | `iterations` / `toolCalls` 不是输入，在类体初始化，不进构造器 |
| **出口归谁持有** | **归 `TurnFlow`** —— 它才是拥有 channel 的一侧；出口留在它自己手里，**不外借、不回填** |
| **发射不需要轮次** | `turn.flow.emit(event)` —— 出口的持有者就是流本身，发射方不必经手轮次 |
| `llmCall` 的形态 | **单个 lambda、一个参数** `suspend (ChatRequest) -> ChatResponse` —— 策略只管"怎么调模型"，不必认识 `TurnFlow`；需要往出口发事件的分支（流式 delta）由调用方在闭包里自引用本轮完成 |
| 晚绑定的数量 | **零**（从调用方视角）—— `query` / `llmCall` 走构造器；出口由流在收集时装进**自己**；`iterations` / `toolCalls` 自产 |
| `turn.flow` 的 channel 模型 | **不动** —— `channelFlow` + LAZY producer + `attach` 三态 + `buffer(RENDEZVOUS)` 是反复调出来的取消/背压语义，换模型风险远大于收益 |
| `context` | **挂到轮次上**（`turn.iterationContext`），每轮迭代刷新一次 —— 它带可变状态（`AgentContext.metadata`），不是纯派生量 |
| agent 级字段 | 一律不动（`memory` / `toolRegistry` / `persona` / `hook` / `modalityAdapter` / `llmProvider` / `maxIterations`） |
| 公开 API | **本轮重构不变** —— `Agent.run` / `runStream` / `steer` 签名与契约均不动（`cancel` 的签名在本轮之后另行调整，见《打断后的重开》—— 它改为返回收尾句柄） |
| `BossAgent` | **不动** —— 本轮只动 `ReActAgent` + `AgentTurn` + `TurnFlow` |

---

## 出口（emit）放在哪一侧

### 出口的本质

出口就是收集方的 `send`。`buffer(Channel.RENDEZVOUS)` 之下，**生产者的 `send` 要等收集方真正接走才返回**。于是：

- 闸内 `emit(Final)` 会**挂在闸里**，直到收集方取走 Final；
- `finalize` 里"先还槽、后关门"就建立在这个同步交接上 ——
  "终局事件发完 ⟺ `steer()` 返回 false ⟺ 可安全开新轮"是**一条原子事实**；
- 由此才有那两条被写进 KDoc 的死锁形状（回调内同步 `steer()` ⟹ 自死锁 / 跨线程死锁）。

### 因此：出口必须来自收集方那一侧

出口**不能**由轮次自持 channel 再转发出去 —— 那会多一跳：

```
producer.send → turn.channel（被转发器接走）→ 转发器 emit → 收集方
```

生产者的 `send` 在**收集方真正收到之前**就返回了 —— 闸内 `emit(Final)` 提前放闸、槽位提前归还，
`steer()` 可能返回 false，而旧 Final 还在管道里 → 新轮 Initial 先发、旧 Final 后到，**混流**。
这正是当初把 `emit(Final)` 留在闸内、并让 `emit` 同步交接的原因。

多一跳的代价不是"多一次拷贝"，而是**把一条原子事实拆成两段**。

### 但出口不必"外借"

出口的**来源**受约束（必须是收集方的 `send`），**归属**却是自由的：
既然 `TurnFlow` 才是拥有 channel 的一侧，出口就该待在它自己手里：

```
collect ─► channelFlow { … send(e) … }      ← 收集方的 send
              │
              ├─ 存进 TurnFlow 自己的字段     ← 装一次，此后发射不再经手任何人
              ▼
        turn.flow.emit(event) → send(event) → 收集方
```

于是**没有任何回填**：没有"流把出口交给轮次"这一步，也没有安装回调；
`AgentTurn` 压根不碰出口 —— 它只是**持有**一个 `TurnFlow`。

**发射不需要轮次参数**：`turn.flow.emit(event)` 里的 `turn` 只是寻址手段，
真正被调用的是流自己；策略侧同理 —— 流式分支拿到的也是流，不是轮次。

---

## 时序

```
run() ──► AgentTurn(query, llmCall) 建立
            │  · 身份与机械：闸 / 信箱 / 流
            │  · 执行态：iterations / toolCalls
            ▼
collect ─┤► turn.flow.start { loop(turn) }
            │     ├─ attach(producer)          登记执行体（Ok / Canceled / Repeated）
            │     ├─ send 存入本流               出口装进自己（一次性）
            │     └─ producer.await()           执行体才开始跑
            ▼
      流已可发事件 —— 执行体启动时出口必已就位
```

顺序不可换：`attach` 判出 `Canceled` / `Repeated` 时直接抛，流从未收集过、执行体也从未启动。

**装出口是一次性的**（重复收集抛出的 `RepeatedCollectionException` 在 `attach` 阶段就被拦下，
根本走不到装出口那一步）。

---

## 改造后的形状

### `TurnFlow`（`agent/core/src/main/kotlin/io/github/yeyi/agent/AgentTurn.kt`）

多了一件事：**持有本轮的事件出口**。其余（`attach` 三态、LAZY producer、`buffer(RENDEZVOUS)`）原样不动。

```kotlin
internal class TurnFlow {
    private enum class Attach { Ok, Canceled, Repeated }

    private val lock = Any()
    private var job: Job? = null
    private var canceled = false

    /** 本轮的事件出口 —— 收集时装一次，此后发射走它。 */
    private var sink: (suspend (AgentEvent) -> Unit)? = null

    /**
     * 启动本轮执行体。
     *
     * 出口在收集时装进本流（[sink]），执行体随后才开始跑 —— 保证执行体启动时
     * 本轮出口必已就位。`Canceled` / `Repeated` 直接抛：出口从未装上，执行体从未启动。
     */
    fun start(body: suspend () -> Unit): Flow<AgentEvent> = channelFlow {
        val producer = async(start = CoroutineStart.LAZY) { body() }
        when (attach(producer)) {
            Attach.Ok -> Unit
            Attach.Canceled -> throw CancellationException("flow is canceled")
            Attach.Repeated -> throw RepeatedCollectionException()
        }
        sink = { e -> send(e) }; producer.await()
    }.buffer(Channel.RENDEZVOUS)

    private fun attach(job: Job): Attach = synchronized(lock) { /* 原样 */ }

    /**
     * 向本轮的事件出口发一个事件 —— **不需要轮次**，出口就在本流手里。
     *
     * 未收集即调用是内部不变式违规 —— 明确抛 [IllegalStateException]，
     * **不静默丢弃**（静默吞事件会表现为"事件凭空消失"，是最难查的一类故障）。
     */
    suspend fun emit(event: AgentEvent) {
        checkNotNull(sink) { "turn flow is not started yet" }.invoke(event)
    }

    fun cancel(): Boolean = synchronized(lock) { /* 原样 */ }
}
```

**依赖形状**：流与轮次之间**没有回调、没有反向引用** —— 轮次持有流，流不知道轮次；
`SteerInbox` 降为**纯缓冲**（不持轮次、不取闸），依赖仍是单向的（轮次 → 信箱 / 轮次 → 流 ← 轮次）。

**闸只护一处，且由取闸的两侧各自负责**：闸保护的是"终局裁决"
（判空 → 发 Final → 关信箱）与"投递"互斥 —— 投递若与判空/发 Final/关信箱交错，
指令会既被接受又没被消费。终局侧在 `finalize` 取闸，投递侧在 `steer` 取闸，
两边取同一把闸（[AgentTurn] 持有），互斥成立。
信箱的**排空不取闸**：它只由 agent 的执行体调用（串行），Channel 收发本身原子，
与投递并发也无妨 —— 挤在排空之后的那条由下一个检查点（迭代头）消费，不会丢。
不取闸还消掉一个死锁陷阱：闸不可重入，排空若在持闸时调用会自锁，
`finalize` 那条"有指令就抢占 Final"的分支因此不必再为排空而绕开持闸。
（它仍要先解锁再消费指令 —— 理由不同：消费要写记忆，记忆压缩可能触发一次 LLM 调用，
闸内只容得下 `emit(Final)` 这一个已知挂起点。）

### `AgentTurn`（同文件）

```kotlin
internal class AgentTurn(
    /** 本轮的初始查询 —— 必需且不可变，故进构造器。 */
    val query: AgentQuery,

    /**
     * 本轮调模型的方式 —— **单个 lambda、一个参数**：只收请求。
     *
     * 策略不必认识 `TurnFlow`：需要往出口发事件的分支（流式 delta）
     * 由调用方在闭包里自引用本轮完成。
     */
    val llmCall: suspend (ChatRequest) -> ChatResponse,
) {
    private val gate = Semaphore(1)

    /** 本轮的流 —— 事件出口在它手里。 */
    val flow = TurnFlow()

    val steering = SteerInbox()

    /** 本轮迭代计数 —— 自产累积量，非输入，故不进构造器。 */
    var iterations: Int = 0

    /** 本轮工具调用记录 —— 同上。 */
    val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()

    /**
     * 本轮当前迭代的上下文 —— 一轮迭代一份，迭代内所有钩子点共用同一个对象。
     *
     * 由 agent 在每轮迭代开始时刷新（`buildContext(turn.iterations)`）。
     * 不能各派生各的：[AgentContext.metadata] 是可变 map，KDoc 承诺"供后续 hooks 使用" ——
     * 一旦一轮迭代里拆成多个对象，钩子之间就传不了数据了。
     */
    lateinit var iterationContext: AgentContext

    fun lock() = gate.acquireUninterruptibly()
    fun unlock() = gate.release()
}
```

> `AgentTurn` 仍然只依赖 `llm` 包的 `ChatRequest` / `ChatResponse` 与 `agent` 包的
> `AgentQuery` / `AgentEvent` / `AgentResult` —— 同模块，无新增依赖。
> 它**不碰出口**：出口是流的事。

### `ReActAgent`（`agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt`）

```kotlin
override fun run(query: AgentQuery): Flow<AgentEvent> {
    val turn = AgentTurn(query) { req -> llmProvider.chat(req) }
    return runTurn(turn)
}

override fun runStream(query: AgentQuery): Flow<AgentEvent> {
    // 策略只收请求，发 delta 需要本轮的出口 —— 故由闭包自引用本轮。
    // llmCall 只在执行体里被调用（收集之后），那时本轮必已赋值。
    val streaming = StreamingRunner(llmProvider)
    lateinit var turn: AgentTurn
    turn = AgentTurn(query) { req ->
        streaming.run(req) { turn.flow.emit(AgentEvent.TextDelta(it)) }
    }
    return runTurn(turn)
}

/**
 * 回合的公共骨架：占活跃槽位 → 交由可中断容器执行 → 无论何种终态都释放槽位。
 */
private fun runTurn(turn: AgentTurn): Flow<AgentEvent> {
    if (!activeTurn.compareAndSet(null, turn))
        error("Concurrent run not supported: another run is active")
    return turn.flow.start { loop(turn) }.onCompletion { cause ->
        // 非拥有者的收集（重复收集）不得收尾：认人 CAS 会命中仍在运行的轮次，
        // 把它的槽位提前释放（可开出并发双跑），并关掉它的信箱（steer() 对活着的轮返回 false）。
        if (cause is RepeatedCollectionException) return@onCompletion
        // 轮次退场：流已结束，没有执行体在跑 —— 退场不必取闸。
        retireTurn(turn)
    }
}
```

**退场 = 还槽 + 关信箱 + 完成收尾信号**（`retireTurn`）——终局路径（`finalize`）与
收尾路径（此处、`cancel` 放弃未收集的轮次）走的是同一个方法，所以"这一轮退场了"只有一个时刻，
收尾信号也只在那时完成。

**退场不必在闸内**：闸护的是"终局裁决"与"投递"互斥；退场时执行体已结束，
唯一可能并发的只有投递 —— "还槽"与"关信箱"之间被挤进一个投递，代价只是那次 steer
对已死的轮返回 true（与"cancel 之后 steer 仍可能返回 true"同一语义）。
终局路径则天然在闸内调它（它本就持闸跨越终态发射），那里的投递被挡在闸外 →
对已死的轮返回 false。
**先还槽、后关信箱**：恒有「信箱已关 ⟹ 槽位已空」——
steer() 的重试循环靠这条必然推进，也保证「false ⟹ 可安全开新轮」。

**轮次在 `run` / `runStream` 里建**，`runTurn` 只管占槽与收尾 ——
建轮与占槽分开不影响语义：CAS 失败照样抛 "Concurrent run not supported"，
轮次建了却没占上，直接丢弃 —— 此时它既没装出口、也没起执行体，不漏资源。

`StreamingRunner` **既不提为 agent 字段，也留在 `runStream` 的局部变量里** ——
它无状态（可变状态都在 `run()` 的局部变量里），每轮 new 一次、整轮持有即可；
挂在 agent 上等于把一次性协作的东西抬成 agent 的常驻面。
（改造中途曾把它放进 `llmCall` 闭包、"每次调模型 new 一次" ——
那在多轮循环下是无谓分配，已改回每轮一次。）

执行链随之塌缩：

```kotlin
private suspend fun loop(turn: AgentTurn) { ... }
private suspend fun loopOnce(turn: AgentTurn): AgentResult? { ... }
private suspend fun callTools(turn: AgentTurn, response: ChatResponse, allAsync: Boolean) { ... }
private suspend fun finalize(turn: AgentTurn, response: ChatResponse): AgentResult? { ... }
private suspend fun consumeSteering(turn: AgentTurn) { ... }
private suspend fun llmCallWithContextOverflowHandle(request: ChatRequest, turn: AgentTurn): ChatResponse { ... }
```

| 方法 | 现状参数数 | 改造后 | 变化 |
|---|---|---|---|
| `loop` | 4 | 1 | −3 |
| `loopOnce` | 5 | 1 | −4 |
| `callTools` | 5 | 3 | −2 |
| `finalize` | 6 | 2 | −4 |
| `llmCallWithContextOverflowHandle` | 2 | 2 | 0（`llmCall` → `turn.llmCall`） |
| `run` / `runStream` | 各 1 段 loop 调用 | 各 1 行调模型策略 | 重复调用消失 |

`callTools` 保留 `response` / `allAsync` 两个局部量。

`context` **挂到轮次上**（`turn.iterationContext`）—— 它不是"纯派生量"：
[AgentContext.metadata] 是可变 map，KDoc 承诺"供后续 hooks 使用"，一轮迭代里拆成多个对象，
钩子之间就传不了数据了。故一轮迭代只造一个 context，由 `loopOnce` 造好放进轮次，
`callTools` / `finalize` 从轮次取用（签名仍只传轮次）。

`buildContext` 的调用点收敛为两处，且都只往轮次里装：
循环外的前置一次（迭代 0 —— 记忆压缩钩子与失败路径都要用），
循环内每轮迭代刷新成当轮的。其余消费点一律从 `turn.iterationContext` 取：
记忆压缩钩子（`attachHook`）、`onRunFailed`、`callTools` 的工具钩子与 `ToolExecutionContext`、
`onRunCompleted`。

`loop` 体内的局部量按归属上移：`iterations` / `toolCalls` 由局部变量改为轮次字段
（`turn.iterations`、`turn.toolCalls`），循环条件与计数改为 `turn.iterations`。

### 事件出口的改写点

所有发事件的调用点从"传进来的 `emit`"改为"`turn.flow.emit`"：

- `loop` 的 `emit(AgentEvent.Initial(query))` → `turn.flow.emit(...)`
- `MemoryCompressProxyHook(hook, emit)` → `MemoryCompressProxyHook(hook) { turn.flow.emit(it) }`
  （hook 是 agent 级的，只有出口是轮次级的 —— 包装器每轮新建，正合其意）
- `loopOnce` / `callTools` / `finalize` 中的 `AgentEvent.*` 发事件

---

## 语义不变式

改造**不改变任何可观察行为**，以下不变式逐条保持：

| 不变式 | 保持方式 |
|---|---|
| 终局三选一（Final / Failed / CancellationException） | `loop` 的 try/catch 结构不动 |
| 闸内只允许两个已知挂起点 | `finalize` 的持闸范围不动，只把 `emit` 换成 `turn.flow.emit` |
| 收集方取消 ⟹ 执行体一并停止 | `TurnFlow` 的 channelFlow / LAZY producer 不动 |
| 重复收集抛 `RepeatedCollectionException`、不转 Failed | `TurnFlow.attach` 三态不动；`onCompletion` 的认人分支不动 |
| **闸内 `emit(Final)` 的同步交接** | 出口仍是收集方的 `send`，`buffer(RENDEZVOUS)` 不动 —— 见《出口放在哪一侧》 |
| 死锁硬约束（回调内不得同步 `steer()`） | `AgentTurn.gate` 仍是 `Semaphore`，不可重入的性质不变 |
| 认人归还槽位 | `activeTurn.compareAndSet(turn, null)` 两处不动 |
| 事件顺序 | `turn.flow.emit` 即原 `emit`，同一个 sink，无缓冲差异 |
| **一轮迭代内钩子点共用同一个 `AgentContext`** | `context` 挂到轮次上（`turn.iterationContext`），每轮迭代刷新一次 —— `AgentContext.metadata` 是可变 map，拆开就传不了数据 |

**新增**一条内部不变式：**执行体启动前出口必已装好**（由 `start` 内部的顺序保证；
未装即发射抛 ISE，不静默丢）。

---

## 风险与取舍

| 风险 | 评估 | 处置 |
|---|---|---|
| 出口未装即发射 → 事件丢失 | 中 | 装出口由 `TurnFlow.start` 自己做（调用方无从跳过）；未装即发射抛 ISE，不静默丢 |
| 出口多一跳导致还槽早于 Final 抵达 | 中 | 不改 channel 模型（见《出口放在哪一侧》），语义逐条对照上表 |
| 策略不再拿流 → 发 delta 需自引用本轮 | 低 | 流式分支在闭包里自引用本轮（`lateinit var turn`）；`llmCall` 只在执行体里被调用，那时本轮必已赋值 |
| `AgentTurn` 变胖，关注点变宽 | 中（设计取舍） | 本轮接受 —— 判据是"与轮次同生共死"；`AgentTurn` KDoc 已自称"本轮状态的唯一所有者" |
| 可变状态上轮次（`toolCalls` / `iterations`） | 低 | 均为本轮私有累积，轮次终结即丢弃 |
| 回归风险 | 中 | 取消 / 重复收集 / 背压三处语义敏感，改造后必须重跑 `CancelableTest` 相关用例 |

**明确的取舍**：
1. 不选"独立 `TurnRun` 对象" —— 两者严格 1:1，拆分的收益只是命名整洁，代价是每次访问多一层间接。
2. 不选"轮次自持 channel" —— 见《出口放在哪一侧》的多一跳论证。
3. 出口**留在流里**而不是搬到轮次 —— 持有 channel 的一侧就是它自己；搬走只会制造回填。
4. `TurnFlow` 的 `attach` 三态与 `cancel` 逻辑**原样保留**，只多一个出口字段。

---

## 改动清单

| 文件 | 改动 |
|---|---|
| `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentTurn.kt` | `TurnFlow` 增 `sink` / `emit`，`with` 改 `start`、去泛型（出口类型固定为 `AgentEvent`）；`AgentTurn` 构造器加 `query` / `llmCall`（单参 lambda），新增 `iterations` / `toolCalls` / `context` |
| `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt` | `run` / `runStream` 建轮并只提供调模型策略（`llmCall`）；`runTurn` 收轮次（占槽 + 收尾）；`loop` / `loopOnce` / `callTools` / `finalize` / `consumeSteering` / `llmCallWithContextOverflowHandle` 签名改为只传轮次；事件出口改走 `turn.flow.emit`；`context` 由 `loop`（迭代 0 兜底）与 `loopOnce`（每轮刷新）造好放进轮次，其余消费点一律从 `turn.iterationContext` 取 |
| `agent/core/src/test/kotlin/io/github/yeyi/agent/CancelableTest.kt` | 回归用例须全绿（取消 / 重复收集 / 终局三选一） |
| `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentHookTest.kt` | 回归用例：一轮迭代内钩子点共用同一个 `AgentContext`（metadata 跨阶段可见） |
| `docs/superpowers/specs/2026-10-31-turn-first-class-design.md` | 落地后同步：类的职责一节补执行态归属说明 |

**不在改动范围**：公开 API（`Agent` / `Agent.Streamable` / `Agent.Steerable` / `Agent.Cancelable`）、
`TurnFlow` 的取消/登记语义、`BossAgent` 及其 team 模块。
（`SteerInbox` 已随《闸只护一处》一节调整：降为纯缓冲，取闸挪到投递侧。）

---

## 打断后的重开（`cancel` 返回收尾句柄）

**问题**：打断只投递取消，槽位由该轮流的 `onCompletion`（收集方收尾时）归还。
`cancel()` 返回后旧轮往往还在收尾，此刻 `run()` 会撞上 `Concurrent run not supported` ——
收集方与 `cancel()` 同线程时（Android 主线程的常态）这是确定性失败，不是竞态。

**取舍**：不加新接口（`cancelAndAwait` 之类），改为**让 `cancel` 把"等不等"的选择权交给调用方**：

```kotlin
public fun cancel(): Job?
```

返回的句柄 = 该轮的收尾信号（`AgentTurn.terminated`，一个 `CompletableDeferred`），
完成点定在"还槽 + 关信箱"之后 —— 等待者醒来时旧轮已彻底退场，随后 `run()` 必定能起来。
无活跃轮次时返回 `null`（幂等 no-op）。

**等或不等，由调用方决定**：

```kotlin
agent.cancel()?.join()      // 等：旧轮退场后再开新轮，必定成功
agent.cancel()               // 不等：打断立即返回；收尾完成前开新轮仍可能撞并发拒绝
```

**完成点 = 轮次退场的那一刻**：终局路径（`finalize`）与收尾路径（轮次流结束、
放弃未收集的轮次）做的是同一件事 —— 还槽 + 关信箱 + 完成信号，
所以"这一轮退场了"只有一个时刻，句柄就在那时完成。
区别只在**是否持闸**：终局路径本就持闸跨越终态发射，顺手在闸内调；
收尾路径在闸外调（退场时执行体已结束，唯一可能并发的只有投递，
被挤进来的那次投递只是让 `steer` 对已死的轮返回 `true`）。
（早前版本把完成点放在收集方收尾里：那要求终局路径不碰信号，
于是"还槽+关信箱"与"完成信号"被拆到两个方法、两处代码 —— 与退场本是一件事。）

**句柄完成的时机（打断路径）**：句柄在轮次退场（轮次流结束 → `retireTurn`）时完成，
而那时执行体已经跑完 —— `producer.await()` 要等 deferred 终结才抛取消异常，
终结发生在执行体返回之后，包括取消时那次把未配对的 toolCall 补写进共享记忆。
故句柄完成后开新轮，不会与旧轮的补写并发。

**连带澄清的一点**：`cancel()` 之后、旧轮收尾之前，`steer()` 仍会返回 `true`
（信箱还没关），但那条指令投进的是一具死轮次的信箱，会被丢弃 ——
这是正常语义，不是缺陷：轮次已死，未消费的在途指令随它作废（在途指令不跨轮）。
硬不变式只有一条：**`steer` 返回 `false` ⟹ 没有活跃轮次 ⟹ 调用方可安全开新轮**。
句柄完成后（信箱已关、槽位已空），`steer()` 老实返回 `false`。

**纪律**：严禁在收集回调内 `join` 该句柄 —— 收尾信号由轮次退场时完成，
而在收集回调里等它，可能等的就是自己这一轮（自等自，死锁）。
与 `steer()` 的"闸门不可重入"同源。
