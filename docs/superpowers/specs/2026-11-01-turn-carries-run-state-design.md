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
| **构造器 = 必需且不可变** | `query`（必需输入）与 `llmCaller`（必需策略）进构造器 —— 编译期强制必填、不可后改 |
| **类体初始化 = 自产累积量** | `iterations` / `toolCalls` 不是输入，在类体初始化，不进构造器 |
| **出口归谁持有** | **归 `TurnFlow`** —— 它才是拥有 channel 的一侧；出口留在它自己手里，**不外借、不回填** |
| **发射不需要轮次** | `turn.flow.emit(event)` —— 出口的持有者就是流本身，发射方不必经手轮次 |
| `llmCaller` 的形态 | **单个 lambda、两个参数** `suspend (ChatRequest, TurnFlow) -> ChatResponse` —— 策略在**调用时**拿到本轮的流（流式分支要往里发 delta）；不用工厂、不用双重柯里化 |
| 晚绑定的数量 | **零**（从调用方视角）—— `query` / `llmCaller` 走构造器；出口由流在收集时装进**自己**；`iterations` / `toolCalls` 自产 |
| `turn.flow` 的 channel 模型 | **不动** —— `channelFlow` + LAZY producer + `attach` 三态 + `buffer(RENDEZVOUS)` 是反复调出来的取消/背压语义，换模型风险远大于收益 |
| `context` | 留在迭代局部（`buildContext(turn.iterations)` 派生），不进轮次 |
| agent 级字段 | 一律不动（`memory` / `toolRegistry` / `persona` / `hook` / `modalityAdapter` / `llmProvider` / `maxIterations`） |
| 公开 API | **不变** —— `Agent.run` / `runStream` / `steer` / `cancel` 签名与契约均不动 |
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
run() ──► AgentTurn(query, llmCaller) 建立
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
        check(sink == null) { "turn flow is already started" }
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
`SteerInbox` 仍持轮次取闸，于是依赖仍是单向的（轮次 → 信箱 / 轮次 → 流 ← 轮次）。

### `AgentTurn`（同文件）

```kotlin
internal class AgentTurn(
    /** 本轮的初始查询 —— 必需且不可变，故进构造器。 */
    val query: AgentQuery,

    /**
     * 本轮调模型的方式 —— **单个 lambda、两个参数**：请求 + 本轮的流。
     *
     * 流在**调用时**才交到策略手里：流式分支要把 delta 发进本轮出口，
     * 而出口此刻才装得上 —— 故不能是捕获出口的闭包（那会与装出口成环）。
     */
    private val llmCaller: suspend (ChatRequest, TurnFlow) -> ChatResponse,
) {
    private val gate = Semaphore(1)

    /** 本轮的流 —— 事件出口在它手里。 */
    val flow = TurnFlow()

    val steering = SteerInbox(this)

    /** 本轮迭代计数 —— 自产累积量，非输入，故不进构造器。 */
    var iterations: Int = 0

    /** 本轮工具调用记录 —— 同上。 */
    val toolCalls: MutableList<AgentResult.ToolCallRecord> = mutableListOf()

    /** 本轮调模型 —— 把本轮的流一并交给策略。 */
    suspend fun llmCall(request: ChatRequest): ChatResponse = llmCaller(request, flow)

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
    val turn = AgentTurn(query) { req, _ -> llmProvider.chat(req) }
    return runTurn(turn)
}

override fun runStream(query: AgentQuery): Flow<AgentEvent> {
    val turn = AgentTurn(query) { req, flow ->
        StreamingRunner(llmProvider).run(req) { flow.emit(AgentEvent.TextDelta(it)) }
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
        // 还槽（认人）：槽位若已是新一轮则空操作 —— 不得误杀下一轮。
        activeTurn.compareAndSet(turn, null)
        // 轮次收尾：关信箱
        turn.steering.close()
    }
}
```

**轮次在 `run` / `runStream` 里建**，`runTurn` 只管占槽与收尾 ——
建轮与占槽分开不影响语义：CAS 失败照样抛 "Concurrent run not supported"，
轮次建了却没占上，直接丢弃 —— 此时它既没装出口、也没起执行体，不漏资源。

`StreamingRunner` **仍是每轮新建的局部对象**，不提为 agent 字段 ——
它是本轮"怎么调模型"的实现细节，挂在 agent 上等于把一次性协作的东西抬成 agent 的常驻面。

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

`callTools` 保留 `response` / `allAsync` 两个局部量，`context` 在 `loopOnce` 内派生一次后按需传入。

`loop` 体内的局部量按归属上移：`iterations` / `toolCalls` 由局部变量改为轮次字段
（`turn.iterations`、`turn.toolCalls`），循环条件与计数改为 `turn.iterations`，
`onRunFailed` 的 context 由 `buildContext(turn.iterations)` 派生。

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

**新增**一条内部不变式：**执行体启动前出口必已装好**（由 `start` 内部的顺序保证；
未装即发射抛 ISE，不静默丢）。

---

## 风险与取舍

| 风险 | 评估 | 处置 |
|---|---|---|
| 出口未装即发射 → 事件丢失 | 中 | 装出口由 `TurnFlow.start` 自己做（调用方无从跳过）；未装即发射抛 ISE，不静默丢 |
| 出口多一跳导致还槽早于 Final 抵达 | 中 | 不改 channel 模型（见《出口放在哪一侧》），语义逐条对照上表 |
| 策略要拿流（多一个参数） | 低 | `turn.llmCall(request)` 一行封装，调用点无感；流是本轮唯一的事件出口，交给策略无越权风险 |
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
| `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentTurn.kt` | `TurnFlow` 增 `sink` / `emit`，`with` 改 `start`、去泛型（出口类型固定为 `AgentEvent`）；`AgentTurn` 构造器加 `query` / `llmCaller`，新增 `iterations` / `toolCalls` / `llmCall` |
| `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt` | `run` / `runStream` 建轮并只提供调模型策略（`llmCaller`）；`runTurn` 收轮次（占槽 + 收尾）；`loop` / `loopOnce` / `callTools` / `finalize` / `consumeSteering` / `llmCallWithContextOverflowHandle` 签名改为只传轮次；事件出口改走 `turn.flow.emit` |
| `agent/core/src/test/kotlin/io/github/yeyi/agent/CancelableTest.kt` | 回归用例须全绿（取消 / 重复收集 / 终局三选一） |
| `docs/superpowers/specs/2026-10-31-turn-first-class-design.md` | 落地后同步：类的职责一节补执行态归属说明 |

**不在改动范围**：公开 API（`Agent` / `Agent.Streamable` / `Agent.Steerable` / `Agent.Cancelable`）、
`TurnFlow` 的取消/登记语义、`SteerInbox`、`BossAgent` 及其 team 模块。
