# 轮次一等公民（Turn）—— 设计

## 背景与问题

`Agent.Cancelable` 落地后（`9480e57a` feat Cancelable、`f082c0d2` fix），cancel 生命周期有两处未闭环。审查两笔提交时用两个构造用例复现（`CancelAuditTest`，均以挂死告终）。

### 现状结构

cancel 相关的跨轮状态有**两处**，分居两个类：

| 状态 | 位置 | 性质 |
|---|---|---|
| 槽位（当前活跃轮次） | `SteerInbox.channelRef` | agent 级单槽 |
| 在途执行体登记 | `CancelableFlow.active` | **全局单槽** |

两者靠"恰好是同一个"来对应 —— 信箱归轮次 A，执行体登记却可能已经是轮次 B 的。

### 缺口 A：迟收集的废弃轮次会顶掉运行中轮次的登记

`CancelableFlow.with` 用**无条件** `active.set(producer)` 登记执行体。`active` 是全局单槽，任何一轮都能覆盖它。

```
1. round1 run() 后不收集 → cancel() → 无在途执行体 → 兜底销毁信箱、槽位归还
2. round2 收集并运行 → active = producer2
3. round1 此时才被收集 → active.set(producer1) 顶掉 producer2 的登记
   → round1 随即因信箱已关抛取消异常 → finally 把 active 清成 null
4. cancel() → active 为 null → 判定「无在途执行体」→ 走无认人兜底销毁
   → 关掉的是 round2 的信箱、槽位也被还回去
```

后果：

- **round2 永远 cancel 不掉** —— 它的登记已丢失，此后每次 `cancel()` 都重复走那条误判分支；
- **槽位提前释放** → 可以开出第三轮，与仍在运行的 round2 双跑，破坏「至多一个活跃轮次」。

### 缺口 B：第二次 `cancel()` 在旧轮收尾期间就把槽位放掉

`CancelableFlow.cancel()` 用 `active.getAndSet(null)?.cancel()` —— **在执行体真正结束前就把登记摘掉**。

`Agent.Cancelable` 的 KDoc 承诺幂等，但第二次 `cancel()` 因此判定为"无在途执行体"，走无认人兜底销毁 → 槽位在旧轮还在收尾时（执行体被取消后可能跑一段 `NonCancellable` 收尾）就被释放 → 新一轮可以与**正在死去的旧轮**并发。

### 共同根因

`cancel()` 的兜底分支（**无认人**地销毁信箱、归还槽位）是个副作用很大的动作，却建立在一个会误判的「当前无在途执行体」判断上：

- 缺口 A 让登记被别的轮次顶掉 → 判断错；
- 缺口 B 让登记被提前摘掉 → 判断错。

只要错一次，就会误杀/误放别人的槽位。

### 附带发现

- 同一 Flow 被 collect 两次 → 同样顶掉登记，且两个 loop 并发写同一份 memory（今天就有，`active.set` 静默覆盖）。
- `steer()` 读槽位与投递之间轮次可能刚好终结 → 返回 false，而此刻可能已有新一轮活跃 → 违反「false ⟹ 可安全开新轮」。
- 失败路径的 `emit(Failed)` 不持闸 → 「Failed 已发」到「槽位归还」之间 `steer()` 可能投递成功而指令随轮次消亡（与取消路径同型）。

---

## 核心决策

| 决策点 | 结论 |
|---|---|
| 轮次身份 | 引入 `Turn` —— 轮次身份，持有本轮的转向信箱与可取消流 |
| 类划分 | `CancelableFlow` 专做可取消流（执行体登记 / 打断 / 「本轮已被取消」标志）；`SteerInbox` 专做转向消息收纳（纯信箱）；终局闸归 `Turn`；`Turn` 持有前两者 |
| 执行体登记 | 从全局单槽下沉到**每轮自己的** `CancelableFlow` —— 不再有可被顶掉的跨轮登记 |
| 槽位 | 保留 agent 级单槽 `activeTurn: AtomicReference<Turn?>`（槽位按定义属于持有它的 agent）；**槽位归 agent、轮次不持有槽位** |
| 终局闸 | 归 `Turn` —— 终局路径要调用它，`turn.lock()` 的语义才准确；信箱的投递 / 排空也要用它，于是信箱持有 `Turn` 引用 |
| 闸的定位 | **轮次锁**，不是信箱锁 —— 它保护的是"本轮的终局裁决"（判空 → 发 Final → 关信箱 → 还槽）与"投递 steering"的互斥；信箱本身（`Channel`）已是线程安全的，闸不是为它加的 |
| 成员暴露 | `Turn.steering` / `Turn.flow` 直接公开，**不铺转发方法** —— 转发只是把调用点搬家，没有多出语义 |
| 依赖形状 | **单向**：`SteerInbox` 持 `Turn` 取闸；`CancelableFlow` 不再持 `Turn` —— 它要问的「本轮是否已被取消」已下沉为自身的标志，与执行体登记同锁 |
| 终态真相源 | **各自的状态各自持有** —— 终局闸在 `Turn`；「本轮已被取消」标志在 `CancelableFlow`（与执行体登记同一把锁 ⇒ 登记与打断之间的判定无竞争） |
| 作废 vs 终局 | 作废 = 置 `CancelableFlow.canceled`（打断时即置）+ 关信箱（`turn.steering.close()`）；**没有 `Turn.close()`** —— 终局路径只关信箱，那里执行体正是在跑的自己，打断自己会把成功轮次变成取消轮次 |
| 兜底销毁 | **删除无认人销毁路径** —— 结构上不再存在能误杀别人槽位的动作 |
| `steer()` 语义 | 投递失败即重读槽位重试；只有确认「此刻无活跃轮次」才返回 false |
| 一轮至多执行一次 | 执行体登记（登记后永不清空）—— 重复 collect 抛 `IllegalStateException`（跑不起来的调用错误，不是运行时错误） |
| 公开契约 | **不变** —— `Agent.Cancelable.cancel()` 仍无参、仍打断持槽位的当前轮 |
| 类名 | `SteerInbox` / `CancelableFlow` 保持原名，只改语义 |
| `Turn` id | 暂不引入 |

---

## 架构分层

```
ReActAgent
 ├─ activeTurn : AtomicReference<Turn?>   ← agent 级：当前活跃轮次（槽位归 agent）
 └─ Turn                                 ← 每轮：轮次身份
      ├─ gate        : Semaphore(1)       ← 终局闸（轮次锁）
      ├─ flow        : CancelableFlow     ← 本轮的可取消流（执行体登记 / 打断 / 已取消标志）
      └─ steering    : SteerInbox         ← 转向消息收纳（纯信箱，持 Turn 引用以便取闸）
```

### 为什么闸随轮走

agent 至多一个活跃轮次，且**跨轮的闸临界区永不重叠** —— 因为槽位的释放永远发生在终态事件之后：

| 路径 | 顺序 |
|---|---|
| 成功 | `emit(Final)` → 还槽 → 关信箱 → 放闸 |
| 失败 | `emit(Failed)` → 流结束 → `onCompletion` 还槽 → 关闭 |
| 取消 | 打断 → 流以取消异常结束 → `onCompletion` 还槽 → 关闭 |
| 从未被收集的轮次被 cancel | 置已取消标志 + 关信箱 → 还槽 → 放闸 |

新轮要建得起来（CAS `null → turn`）必须旧轮已还槽 → 旧轮的闸内临界区已经结束 → 两轮的闸永不争用。所以「闸归轮次」与「闸归 agent」等价。

### 锁序

两把锁互不嵌套：

- `Turn.gate`（`Semaphore`）—— 转向投递与终局裁决的互斥；
- `CancelableFlow.lock`（`synchronized`）—— 执行体登记 / 打断 / 已取消标志。

登记路径不碰闸，闸内不碰登记 → 无环路。

### 闸的定位：轮次锁，不是信箱锁

闸保护的是**本轮的终局裁决**（判空 → 发 Final → 关信箱 → 还槽）与**投递 steering** 两者的互斥 —— 目的是让「终局已经发出 Final」与「此刻没有活跃轮次」对 `steer()` 表现为同一个原子事实。信箱本身（`Channel`）已经是线程安全的，闸不为它而设。

因此闸必须留在 `Turn`（轮次级）：它守的是轮次的终局裁决，跨轮争用没有语义；而闸随轮走之后，**跨轮争用自然消失** —— 至多一个活跃轮次 ⇒ 同一时刻至多一把闸被持有。

若把它降格为"只锁信箱"，则「终态事件已发完」与「槽位已空」不再互斥，`steer()` 必然要在两者之间取其一：取"信箱可投"就可能撞上 Final 已发的轮次（消息随轮消亡），取"槽位为空"就可能拒掉一个仍活跃轮次的合法投递。两者都会破坏公开契约。**故维持轮次锁。**

### 闸不可重入：两处死锁形状

`Semaphore` 的 permit 与线程无关（可由任意线程 release），代价是**不可重入**。代码自身没有嵌套取闸的路径（`SteerInbox.add` / `drain` / `finalize` / `cancel()` 兜底互不嵌套），但外部调用会踩到两个形状：

| 形状 | 触发 | 说明 |
|---|---|---|
| 自死锁 | `onRunCompleted` 回调内**同步**调 `steer()` | 回调运行在闸内 → `steer()` 取闸 → 等自己放闸 |
| 跨线程死锁 | 收集方在消费 `Final` 时**同步**调 `steer()` | 生产者持闸等 `Final` 的背压交接（`RENDEZVOUS`），收集方等 `steer()` 的闸 |

两者都是**已存在的使用约束**（闸一直在 `finalize` 路径上），不是本次重构引入的。处置：写进 KDoc 的硬约束，不改结构 —— 把 `onRunCompleted` 移出闸会改变它的语义（钩子可能触发两次，或在非终局轮次触发），代价大于收益。

---

## 类的职责

### Turn —— 轮次身份

```kotlin
    /**
     * 一轮 run —— 轮次身份，也是本轮状态的唯一所有者。
     *
     * 持有终局闸：闸保护的是"本轮的终局裁决"（判空 → 发 Final → 关信箱 → 还槽），
     * 终局路径调的就是 `turn.lock()` —— 语义准确；信箱的投递 / 排空也要用它，
     * 于是信箱持有本轮的引用。
     *
     * 两个成员直接公开：调用方用它们的机械能力（投递 / 排空 / 判空 / 关闭、登记 / 打断），
     * 不在 Turn 上再铺一层转发方法 —— 转发只是把调用点搬家，没有多出语义。
     *
     * 槽位归 agent（`activeTurn`），轮次不持有槽位 —— 归还槽位由 agent 在终局 / 收尾处
     * 以 CAS 认人归还，轮次只提供自己的身份用于认人。
     */
    private class Turn {
        /**
         * 终局闸门 —— 互斥"抢占 Final"与"投递 steering"两个动作.
         *
         * 用 [Semaphore] 而非 [java.util.concurrent.locks.ReentrantLock]: 终局窗口会跨越挂起点 ('emit' 是 suspend),
         * 协程恢复后可能换线程; ReentrantLock 是线程亲和的, 换线程 unlock 会抛
         * IllegalMonitorStateException —— 表现为 agent 在已发出 Final 之后又报 Failed。
         * Semaphore 的 permit 与线程无关, release 可由任意线程执行。
         *
         * 代价: 不可重入 —— collector / AgentHook 回调内同步调用 steer() 会死锁
         * (本就是硬约束, 见 [steer] 的 KDoc)。
         */
        private val gate = Semaphore(1)
        val flow = CancelableFlow()
        val steering = SteerInbox(this)   // 构造期传 this 是安全的：信箱只在调用期用它取闸

        fun lock() = gate.acquireUninterruptibly()
        fun unlock() = gate.release()
    }
```

### SteerInbox —— 转向消息收纳（纯信箱，取闸用 Turn）

```kotlin
    /**
     * 转向消息收纳 —— 本轮的信箱，只做机械操作。
     *
     * 锁不在这里，而在 [Turn]：闸保护的是"本轮的终局裁决"，终局路径调的是 `turn.lock()`。
     * 本类的投递 / 排空也要用它，故持有本轮引用 —— 双向依赖，语义闭合。
     */
    private class SteerInbox(private val turn: Turn) {
        private val inbox = Channel<AgentQuery>(Channel.UNLIMITED)

        /** 投递在途指令。闸内执行，与终局裁决互斥。 */
        fun add(query: AgentQuery): Boolean {
            turn.lock()
            try { return inbox.trySend(query).isSuccess } finally { turn.unlock() }
        }

        /** 排空在途指令。闸内执行。 */
        fun drain(): List<AgentQuery> {
            turn.lock()
            try {
                return buildList { while (true) { add(inbox.tryReceive().getOrNull() ?: return@buildList) } }
            } finally { turn.unlock() }
        }

        /** 是否还有待消费的转向消息。 */
        @OptIn(ExperimentalCoroutinesApi::class)
        fun isEmpty(): Boolean = inbox.isEmpty

        /** 关闭信箱（幂等）。 */
        fun close() = inbox.close()
    }
```

### CancelableFlow —— 可取消流（执行体登记 / 打断）

```kotlin
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
    private class CancelableFlow {
        private enum class Attach { Ok, Canceled, Repeated }

        private val lock = Any()
        private var job: Job? = null
        private var canceled = false

        fun <T> with(body: suspend (emit: suspend (T) -> Unit) -> Unit): Flow<T> = channelFlow {
            // LAZY 启动：执行体在 await() 之前不运行 —— 一旦开始收集，执行体必已登记进本轮，
            // 此后的 cancel() 不会落空。
            val producer = async(start = CoroutineStart.LAZY) { body { send(it) } }
            when (attach(producer)) {
                Attach.Ok -> Unit
                // 本轮已被取消作废、且从未被收集 → 不再执行；以取消异常收尾，
                // 收集方不会拿到"零事件 + 正常结束"的静默出口。
                Attach.Canceled -> throw CancellationException("flow is canceled")
                // 同一轮被重复收集（含跑完后再收集）—— 跑不起来的调用错误，不是运行时错误。
                Attach.Repeated -> throw IllegalStateException("a turn flow may only be collected once")
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
         * @return true = 取消成功，打断执行；false = 取消失败，执行还未开始
         */
        fun cancel(): Boolean = synchronized(lock) {
            canceled = true
            return job?.cancel()?.let { true } ?: false
        }
    }
```

要点：

- **登记与「已取消」标志同锁** —— `attach` 与 `cancel` 的判定因此没有竞争窗口（标志若在 `Turn` 上、在锁外读，cancel/collect 竞态下会误判）；
- **登记永不清空** —— 收尾期间重复 `cancel()` 仍判定"该轮已收集"，不会误走兜底分支把槽位提前还掉；
- **`cancel()` 的返回值只回答"有没有执行体"** —— `job == null` ⟺ 本轮从未被收集 ⟹ 没有 `onCompletion` 会来 ⟹ 须由 agent 兜底还槽；`job != null` ⟹ 打断已投递（对已结束的 job 是无害空操作）⟹ 槽位由 `onCompletion` 归还。

### 轮次的三种状态与 `cancel()` 的处置

登记永不清空 ⇒ `job` 的取值把轮次划成三段，`cancel()` 对每段的处置唯一：

| 状态 | `job` | `cancel()` 返回 | 槽位由谁归还 |
|---|---|---|---|
| 从未被收集 | null | false | **cancel() 兜底**（没有 onCompletion 会来） |
| 收尾中（执行体已被打断，跑着 NonCancellable 尾巴） | producer | true | onCompletion（cancel 不碰） |
| 已结束（正常跑完或已被取消） | producer（已完成） | true | onCompletion（早已归还） |

第二行是缺口 B 的靶心：登记没被摘掉 ⇒ 收尾期间重复 `cancel()` 判"该轮已收集" ⇒ 不走兜底 ⇒ 槽位不会被提前放掉。

第一行是缺口 A 之外的另一条必须兜底的路径：从未收集 ⇒ 该轮永远不会有 onCompletion ⇒ 只能由 `cancel()` 认人还槽。

第三行说明为什么**不必**区分"在途 / 已结束"：对已完成的 job 调 `cancel()` 是无害的空操作，两种情形都可以安全地走"打断"路径。

无论落在哪一段，`cancel()` 都会把 `canceled` 标志置上 —— 该标志只用于"迟收集是否被拒"，与打断本身是否成功无关。

---

## 五条路径

### run（建轮）

```kotlin
    private fun runTurn(body: suspend (turn: Turn, emit: suspend (AgentEvent) -> Unit) -> Unit): Flow<AgentEvent> {
        val turn = Turn()
        if (!activeTurn.compareAndSet(null, turn)) error("Concurrent run not supported: another run is active")
        return turn.flow.with { emit -> body(turn, emit) }
            .onCompletion {
                // 还槽（认人）：槽位若已是新一轮则空操作 —— 不得误杀下一轮。
                activeTurn.compareAndSet(turn, null)
                // 轮次收尾：关信箱
                turn.steering.close()
            }
    }
```

### collect（执行）

`flow.with` → `attach` 登记（被拒时按 `Canceled` / `Repeated` 分别抛取消异常 / 非法状态）→ `await` → 执行体结束（登记**不清空**）。

### cancel（打断）

```kotlin
    override fun cancel() {
        val turn = activeTurn.get() ?: return          // 无活跃轮次 → no-op（幂等）
        // true  ⟺ 该轮已被收集 ⟹ 打断已投递，槽位由该轮流的 onCompletion 认人归还。
        // false ⟺ 该轮从未被收集 ⟹ 没有 onCompletion 会来，槽位必须在此归还。
        if (turn.flow.cancel()) return
        // 作废与还槽一起入闸：闸把「关信箱 + 还槽」对 steer() 变成原子的一步 ——
        // 后来的 steer() 拿到闸时信箱已关、槽位已空 → 投递失败 → 重读槽位 → 空 → 返回 false，
        // 既不死循环，也满足「false ⟹ 可开新轮」。
        // 该轮从未被收集 ⇒ 没有执行体 ⇒ 闸必空闲。
        turn.lock()
        try {
            turn.steering.close()                         // 关信箱
            activeTurn.compareAndSet(turn, null)          // 还槽（认人）
        } finally {
            turn.unlock()
        }
    }
```

「作废」的两半分别在两处：`turn.flow.cancel()` 内部把 `canceled` 标志置上（迟收集由此被拒），此处关信箱并认人还槽。
```

### steer（投递）

```kotlin
    override fun steer(query: AgentQuery): Boolean {
        // 读槽位与投递之间轮次可能已终结（信箱已关）→ 投递失败时重读槽位再试；
        // 只有确认「此刻无活跃轮次」才返回 false —— 契约：false ⟹ 调用方可安全开新轮。
        // 终止性：trySend 只在信箱已关时失败，而信箱已关的轮次必定已不在槽位里 → 每轮迭代必然推进。
        while (true) {
            val turn = activeTurn.get() ?: return false
            if (turn.steering.add(query)) return true
        }
    }
```

### finalize（终局）

```kotlin
        turn.lock()
        if (!turn.steering.isEmpty()) {
            turn.unlock()
            consumeSteering(turn)      // 闸内 drain
            return null                // 抢占 Final，强制再跑一轮
        }
        // …（略：构造 AgentResult、onRunCompleted 回调）
        try {
            emit(AgentEvent.Final(result))
            // 终局持闸：还槽 + 关信箱一起做完，闸外才可能看到「槽位已空 + 信箱已关」。
            // 先还槽位、后关信箱 —— 保证恒有「信箱已关 ⟹ 槽位已空」。
            // 关的是信箱而不是打断执行体：此刻执行体正是在跑的那个自身，打断自己会
            // 把成功轮次变成取消轮次。
            activeTurn.compareAndSet(turn, null)   // 还槽（认人）
            turn.steering.close()                    // 关信箱（幂等）
        } finally {
            turn.unlock()
        }
```

### 循环检查点与消费

```kotlin
    // loopOnce：迭代头注入
    if (!turn.steering.isEmpty()) consumeSteering(turn)

    // consumeSteering
    private suspend fun consumeSteering(turn: Turn) {
        turn.steering.drain().forEach { query -> /* 注入 memory */ }
    }
```

`loop` / `loopOnce` / `finalize` / `consumeSteering` 全部改用 `turn` 参数，不再经任何中间门面。

---

## 不变式

| # | 不变式 | 由什么保证 |
|---|---|---|
| 1 | 至多一个活跃轮次 | `activeTurn` 单槽 CAS |
| 2 | `steer()` 返回 false ⟹ 此刻无活跃轮次，可安全开新轮 | 重读重试（steer 路径）+ 「信箱已关 ⟹ 已不在槽位」 |
| 3 | 一次 collect 必以 Final / Failed / CancellationException 之一结束 | `finalize` / `loop` 的 catch / `attach` 拒绝 → 取消异常。**例外**：重复收集是调用方错误 → `IllegalStateException` |
| 4 | 信箱已关 ⟹ 已不在槽位 | 终局路径「先 CAS 还槽、后关信箱」；`cancel()` 兜底路径反过来「先关信箱后还槽」—— 两者都在同一闸内完成，对 `steer()` 是原子的一步（`steer` 的投递要取闸，看不到中间态） |
| 5 | 槽位释放总在终态事件之后 | 成功：emit(Final) → 还槽；失败：emit(Failed) → onCompletion 还槽；取消：流结束后 onCompletion 还槽 |
| 6 | 一轮至多执行一次 | `attach` 的执行体登记（登记后永不清空）—— 重复 collect 一律 `IllegalStateException` |
| 7 | `cancel()` 打断当前持槽位的轮次；重复调用幂等；收尾期间不提前还槽 | 登记永不清空 —— `cancel()` 只打断、不摘登记；只有"从未被收集"的轮次才由 `cancel()` 兜底还槽 |
| 8 | `cancel()` 之后被收集的轮次必以取消异常结束（不执行） | `turn.flow.cancel()` 即置 `canceled` 标志 —— 迟收集命中 `attach` 的 `Canceled` 分支 |

不变式 5 同时保证「闸的临界区永不跨轮重叠」。

不变式 4 的「同闸内完成」是 `steer()` 不死循环的关键：闸把「关信箱 + 还槽」变成对 `steer()` 原子的一步 —— 它拿到闸时信箱已关、槽位已空，投递失败 → 重读槽位 → 空 → 返回 false，既不死循环，也满足不变式 2。

### 两处「关闭 + 还槽」的次序为何相反

| 路径 | 次序 | 理由 |
|---|---|---|
| `cancel()` 兜底（从未被收集） | **先作废（置 `canceled` 标志 + 关信箱），后还槽** | 迟收集是否被拒，取决于 `canceled` 标志 —— 标志必须先于还槽置上，否则还槽与置标志之间被收集的轮次会看到「未取消 + 未收集」而**照常执行**（违反 `Agent.Cancelable` 的 KDoc）。关信箱与还槽在同一闸内完成，对 `steer()` 不可见 |
| `onCompletion`（流已终止） | **先还槽，后关信箱** | 已收集的轮次迟收集由 `Repeated` 拦下，`canceled` 标志用不上 —— 不必为它破例；保持「先还槽、后关信箱」以保住不变式 4，让 `steer()` 的重试循环必然推进 |

---

## 行为变化

| 场景 | 现状 | 改后 |
|---|---|---|
| 废弃轮迟收集 | 顶掉运行中轮的登记 → 该轮**永远 cancel 不掉**，槽位提前释放（可双跑） | 登记被拒（`canceled` 标志）→ 抛取消异常，运行中轮照常可打断 |
| 从未被收集的轮次被 cancel 后再 collect | 靠信箱已关拦下（`isClosedForSend` 检查） | `cancel()` 置 `canceled` 标志 → `attach` 的 `Canceled` 分支拦下 → 取消异常。**不变**，但不再依赖 delicate API |
| 收尾期间再次 `cancel()` | 提前释放槽位 → 旧轮未死、新轮可进（可双跑） | 保持占用，到 `onCompletion` 才还 |
| `cancel()` 时无活跃轮 | 走无认人销毁（理论上会关掉新一轮的信箱） | 真 no-op |
| 同一流 collect 两次 | 静默顶掉登记，两个 loop 并发写 memory | 第二次抛 `IllegalStateException` |
| 轮次跑完后再 collect 一次 | 静默重跑整个 loop（重复 Final、重复写 memory） | 抛 `IllegalStateException` |
| `steer()` 撞上轮次终结的窗口 | 可能返回 false 而此刻有活跃轮（违反不变式 2） | 重读重试，确认无活跃轮才返回 false |
| 被 cancel 后立刻 `run()` | 从未被收集被 cancel → 可开新轮；运行中被 cancel → 抛并发异常 | **不变** |
| 背压、终局协议、`steer` 语义、公开契约 | — | **全部不变** |

---

## 改动清单

| 文件 | 改动 |
|---|---|
| `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt` | 新增 `Turn`（终局闸 + 两个成员）；`SteerInbox` 改为每轮一份的纯信箱（持 `Turn` 引用取闸）；`CancelableFlow` 改为每轮一份（登记下沉，消灭全局单槽；`canceled` 标志与登记同锁）；agent 级加 `activeTurn`；`runTurn`/`cancel`/`steer`/`loop`/`loopOnce`/`finalize`/`consumeSteering` 改用 `turn`；作废 = `cancel()` 内的置 `canceled` 标志 + 关信箱（入闸），终局路径只关信箱；`isClosedForSend` 移除，`SteerInbox.isEmpty` 改用 `@OptIn(ExperimentalCoroutinesApi::class)` |
| `agent/core/src/test/kotlin/io/github/yeyi/agent/CancelableTest.kt` | 并入两个回归用例：迟收集的废弃轮次不得夺走运行中轮的 cancel 登记；收尾期间的第二次 cancel 不得释放槽位 |
| `agent/core/src/test/kotlin/io/github/yeyi/agent/CancelAuditTest.kt` | 删除（用例并入 `CancelableTest`） |

---

## 验证

- `./gradlew :agent:core:test` —— 现有 `CancelableTest` / steer 相关用例保持绿 + 两个新回归用例通过；
- `./gradlew :agent:config:test` —— `SpawnedAgent` 相关的 shutdown / cancel 用例保持绿；
- `./gradlew :team:test` —— team 模块独立实现，不受影响，确认无回归。

---

## 范围外

- `cancel()` 点名指定轮次（需公开句柄 API）；
- memory 的轮次隔离（取消轮收尾时写 memory，可能与新轮交错）；
- `SpawnedAgent.shutdown()` 不等待在途轮次就回收 MCP 资源；
- `team/BossAgent` 的静默 shutdown；
- 失败路径 `emit(Failed)` 不持闸导致的那道窗口（按「steer true 只表示被接收」可接受）；
- 闸不可重入带来的两处死锁形状（`onRunCompleted` 回调内同步 `steer()`；收集方消费 `Final` 时同步 `steer()`）—— 已作为硬约束写进 KDoc，不改结构；
- 两条 KDoc 文案（`SpawnedAgent.cancel()` 的 ISE 与「幂等」表述、`Agent.Cancelable` 中间那句）。
