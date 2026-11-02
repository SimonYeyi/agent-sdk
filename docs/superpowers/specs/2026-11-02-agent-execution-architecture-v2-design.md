# Agent 执行架构演进（架构 2）设计

- 日期：2026-11-02
- 状态：已确认，待实施
- 范围：`agent:core`、`agent:config`、`team`、`demos`（消费端适配）
- 取代：`agent/core/DESIGN.md`（本文档定稿后删除该文件）

## 1. 目标

本设计要把 `agent:core` 的回合执行模型从「冷流 + 收集方驱动 + 取消走异常」演进为「启动执行 + 广播共享 + 取消走领域事件」。核心目的只有三条，按优先级排列：

1. **取消作为领域事件发射**：取消不再以 collect 外抛 `CancellationException` 的形式呈现，而是作为终局事件 `AgentEvent.Canceled` 送达。
2. **简化收集延迟执行带来的复杂性**：消灭冷流架构下「轮次占位」「未收集需 cancel 兜底」「重复订阅异常」三类补丁。
3. **顺带消除 `steer()` 的闸门死锁**：执行体迁出收集方协程后，「持闸者恢复依赖被阻塞线程」的成环条件不复存在。

明确**不是**目标的：多订阅。机制上多订阅是广播的天然副作用，本设计不对它做额外的隔离、补发或订阅级背压——但语义必须完整定义（见 6.5），不允许「支持一半」。

### 1.1 现状与根因

当前执行体跑在收集方的 `channelFlow` 里（`AgentTurn.TurnFlow.start`），因此生命周期、取消传播与订阅语义全部被收集方绑架：

- 取消只能通过「打断收集方协程」表达，于是只能以异常呈现；
- 冷流的「不收集不执行」迫使引入 `Attach.Canceled`（迟收集）、`RepeatedCollectionException`（重复收集）、`canceled` 标志与 `sink` 等补丁；
- 收集方协程就是执行体协程，`steer()` 在主线程同步取闸、而持闸的 `finalize()` 也压在同一个线程栈上，构成死锁。

### 1.2 非目标

- 不做「反压节流执行体」：缓冲容量实际无限，慢订阅者不会拖慢回合（见 §6.4）；不引入订阅级隔离（每个订阅者仍共享同一广播缓冲）；
- 不把「调用方生命周期结束」自动转成回合终止：agent 私有 scope 与调用方 scope 解耦后，调用方退出（如 bossScope 关闭）不会级联终止回合——这不是遗漏，是「执行生命周期归 agent」的直接推论；消费端必须在弃用点显式 `shutdown()`（见 §10 Beast 行）；
- 不提供跨回合的历史回放：回放范围限于**单个回合的完整事件日志**（见 §6.4/§6.5）；
- 不改变 `Agent` 接口的公开方法签名（`run` / `runStream` 仍返回 `Flow<AgentEvent>`）；
- 不做「订阅归零自动取消回合」：机制上可监听 `MutableSharedFlow.subscriptionCount`，但（a）`run` 返回到调用方 `collect` 之间的零订阅窗口不可避免，conflated StateFlow 会丢失「曾经订阅过」的事实（漏判），唯一订阅者断开重连的瞬间 1→0→1 也会误杀；（b）其保护域（订阅过又全走）与弃用点显式 `cancel`/`shutdown` 完全重合，盲区（从未订阅）恰是 fire-and-forget 的设计用法——安全网无增量价值；（c）本质是「订阅驱动执行」的复活，与目标 2 相悖。孤儿回合的治理一律走弃用点显式终止。

## 2. 术语

| 术语 | 含义 |
|---|---|
| 回合（turn） | 一次 `run` / `runStream` 调用所启动的执行单元 |
| 执行体 | 在 agent 私有 scope 中运行回合逻辑的协程 |
| 槽位（activeTurn） | agent 上「单活跃回合」的唯一占位，`AtomicReference` |
| 闸门（gate） | 回合内的信号量，互斥「投递 steer」与「终局裁决」 |
| 终局事件 | `Final` / `Failed` / `Canceled` 三者之一，必有且仅有一个 |
| 公共流 | `run` / `runStream` 返回给调用方的 `Flow` |

## 3. 架构总览

```
run(query)
  ├─ CAS 占槽（失败即同步抛 IllegalStateException）
  ├─ scope.launch { 执行体 }          ← agent 私有 scope
  └─ return turn.events.transformWhile{...}   ← 公共流

执行体（agent 私有 scope）:
  try    loop(turn)                   成功：末尾 emit(Final)
  catch  CancellationException        NonCancellable 中 emit(Canceled)
  catch  Throwable                     emit(Failed)          ← 外壳兜底
  finally retireTurn(turn)            还槽 + 关信箱 + terminated.complete
```

```
             ┌──────────── agent 私有 scope ────────────┐
run ──▶ 执行体 ──emit──▶ turn.events (SharedFlow, replay=Int.MAX / 全回合日志)
                                         │
                    ┌────────────────────┼─────────────────────┐
               订阅者 A              订阅者 B               迟到订阅
           (transformWhile)      (transformWhile)     (完整回放 + 终局)
```

## 4. run 机制

```kotlin
public interface Agent {
    public fun run(query: AgentQuery): Flow<AgentEvent>   // 非 suspend：启动回合，返回广播流
}
```

- **非 suspend**：调用即启动执行。「suspend 等到执行完成」等价于 `run(q).toList()`；「等到执行开始」只消除微秒级竞态——该竞态本身也被完整回放彻底兜住（任何时候订阅都从 `Initial` 开始）。
- **启动即执行**：不存在「建轮不执行」，因此「未收集占槽」「迟收集」「重复收集」三类问题从根上消失。
- **并发 run 仍拒绝**：`activeTurn.compareAndSet(null, turn)` 失败时**同步抛** `IllegalStateException`（调用方错误，不进事件流）。
- **runStream 与 run 同机制**：共用 scope、取消、replay，差异仅在于配置的 `llmCall` 是流式（多 `TextDelta` 事件）还是批式。
- **公共流完成语义**：`transformWhile { emit(it); it !is Terminal }`——转发事件，遇到终局事件后让公共流完成。`collect` 随回合结束正常返回，与架构 1 的既有心智一致。

## 5. 执行体与 scope 模型

### 5.1 所有权

**scope 完全由 agent 内部决定；公开 API 不暴露任何注入点。**

```kotlin
public class ReActAgent internal constructor(
    // ...现有参数...
    dispatcher: CoroutineDispatcher = Dispatchers.Default,   // internal 注入口，仅供同模块测试
) : Agent, Agent.Streamable, Agent.Steerable, Agent.Cancelable, Agent.Shutdownable {
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
}
```

理由：

- 架构 2 的前提就是「执行生命周期归 agent」。若整个 scope 由外部注入，等于把「执行绑外部生命周期」换了个方向还回去——agent 生在请求级 scope 里就会死于请求结束，正是本次要拆掉的耦合。
- `SupervisorJob` 必须内部固定：单回合失败只杀本轮 job，不废 agent。这是「失败不消耗可用性」的前提，外部注入者不会替你想这一层。
- **dispatcher 是 internal 构造参数，不是 `AgentBuilder` 公开配置**：库用户无配置面，也就没有「配成单线程共享 dispatcher 复活死锁」的误用面；§5.2 约束从「约束所有调用方」收窄为「约束内部实现与测试」。生产路径恒为 `Dispatchers.Default`。

**测试必须能改调度器**：core 约 35 个 `awaitResult`/`toList` 用例依赖 `StandardTestDispatcher(testScheduler)` 注入保住 `runTest` 虚拟时间与 `advanceUntilIdle` 的确定性编排。若完全锁死（连 internal 注入口都不留）：

- **`runTest` 会失败，不只是变慢**：`runTest` 结束时校验「测试调度器上没有未完成协程」（`UncompletedCoroutinesError`）。执行体跑在真实池上时，主协程在回合完成前就结束测试作用域，异常必然抛出，套件直接变红。这点对 `awaitResult` 类用例尤其致命——它们没有显式等待点可以侥幸排队。
- **手工编排救不回来**：改用 `CompletableDeferred` 手工编排每个等待点，`SteerableTest` 现有 14 处 `advanceUntilIdle` 全部重写，且「检查点①在迭代头生效」这类**时序**断言只能靠 `Thread.sleep` 轮询，CI 慢机器上稳定 flaky。
- **退化为 `runBlocking` + 真实等待**：套件慢一个数量级，`SlowUnwindLlm` 每次真实等待 300ms，`CancelableTest` 十来个用例累计可观。

用整个测试套件的质量换一条用不到的纯粹性，不值——故保留 internal 注入口。

### 5.2 dispatcher 死锁约束

dispatcher 的并发度必须 > 1，或与调用线程不相交。**不得**配置并发度 = 1 且与调用方共享的单线程（典型：`Dispatchers.Main`）。默认 `Dispatchers.Default` 满足。违反此约束会让 §8.2 的死锁窗口重新打开。

> 测试注入的 `StandardTestDispatcher` 字面上违反本约束（单线程且与主协程共享），但测试编排保证 `steer()` 从不在终局闸被持有时调用（执行体停在 LLM gate 上，或 `advanceUntilIdle` 已排干 finalize），不会实际死锁。此为已知且受控的例外。

### 5.3 执行体结构

```kotlin
scope.launch {
    try { loop(turn) }
    catch (e: CancellationException) {
        withContext(NonCancellable) { turn.events.emit(AgentEvent.Canceled(e)) }
    }
    catch (e: Throwable) {
        turn.events.emit(AgentEvent.Failed(e))   // 外壳兜底：loop 常规失败已事件化，到这里的只有壳自身的意外
    }
    finally {
        retireTurn(turn)   // 还槽 + 关信箱 + terminated.complete
    }
}
```

- `loop` 的终局路径**不再 throw**：成功时末尾 `emit(Final)` 后正常返回；失败时 `emit(Failed)` 后同样正常返回（异常已事件化）。
- 外壳的 `catch (Throwable)` 只兜「`loop` 内部 catch 之后、`emit` 之前又抛出」这类意外（如 `NonCancellable` 收尾中的异常、执行体自身 bug）。**订阅者永远等不到终局**这种静默失败由此被排除。无限缓冲下 `emit` 不会因缓冲满而挂起或抛出，该兜底路径实际不可达，保留仅为结构性保证。
- 执行体不感知订阅者：无人订阅时 `emit` 立即返回，事件只进 replay 日志（全量保留，见 §6.4）。

### 5.4 退场时序

执行体的 `finally` 执行 `retireTurn`：还槽（认人 CAS）→ 关信箱 → `terminated.complete(Unit)`。

**不等待订阅者消费终局。** 因此 `cancel()` 返回句柄的语义是「槽位已还、信箱已关」，而非「所有订阅者已收到终局」。既有 KDoc 中「join 句柄后即可安全开新轮」的承诺保持不变；不新增任何送达等待机制。

## 6. 事件协议

### 6.1 终局三类型

```kotlin
public data class Final(public val result: AgentResult) : AgentEvent
public data class Failed(public val cause: Throwable) : AgentEvent
public data class Canceled(public val cause: CancellationException) : AgentEvent
```

事件序列不变：`Initial → [...增量/工具事件...] → Final | Failed | Canceled`。

### 6.2 为什么是独立的 Canceled

- **取消 ≠ 失败**：失败的正确默认动作是重试/告警，取消是停止/提示打断。若把取消嵌套进 `Failed`，未判类型的消费方会走错误的失败默认路径。
- **避免隐式嗅探**：靠 `cause is CancellationException` 判断与项目「显式字段而非隐式判断」的原则冲突（先例：`ToolCallExplanation.allAsync`）。
- **载荷用 `CancellationException`**：`awaitResult` 可直接抛出而不会被协程框架误判为失败；携带 message 与堆栈；实现零成本（执行体 catch 到的本就是取消异常）。

`cancel(cause: String?)` 签名保持字符串入参，内部构造 `CancellationException(cause)` 作为事件载荷；shutdown 与用户打断的区分由 message 承载。

### 6.3 取消语义

- **in-flight 打断**：`Cancelable.cancel(cause)` 取消回合 job → 执行体在 `NonCancellable` 中 emit `Canceled` → 执行体退场；返回收尾句柄。
- **取消不消耗 agent 可用性**：回合后照常执行后续回合。
- **shutdown**：见 §9。
- **收集方自身取消 = 取消订阅**：回合继续在 agent scope 中执行（孤儿回合），事件照常写入 replay 日志，事后重新订阅仍可取到完整历史与终局。collect 调用点抛出的 `CancellationException` 是收集方自身协程的取消信号，属于标准协程语义，**不在「终局全事件化」承诺内**。
- **丢弃一个 agent 时应 `shutdown()` 而非仅取消订阅**：一次性 agent（Beast 每轮新建，订阅处 `finally` 调）与重建式 agent（`ChatViewModel.clearMessages`）的正确收尾是 `Shutdownable.shutdown()`——`job.cancel()` 只掐订阅，回合照旧烧 token 到自然终局。`shutdown()` 级联 `scope.cancel()`，在途回合以 `NonCancellable` 发完 `Canceled` 后退场，是唯一能保证「不再执行」的动作。**终止动作永远显式写在订阅处/创建处，不藏在被调方的流实现里**——这一条是本节的契约面：`Agent.run` 的流不因订阅取消而终止回合。
- **终局唯一性**：单写者单序列，`emit(Final)` 之后执行体不再有挂起点。因此终局必然是序列中最后一个事件，且**所有订阅者看到同一条有序序列**——不存在跨订阅者分叉。cancel 与 Final 竞态下，要么本轮走到终局，要么被取消，全局一致。

### 6.4 背压与回放契约

- 缓冲：`MutableSharedFlow(replay = Int.MAX_VALUE, extraBufferCapacity = 0)`——**全历史回放 + 实际无限容量**（`replay + extra` 溢出时工厂 coerce 回 `Int.MAX_VALUE`；容量语义同 `Channel(UNLIMITED)`，投递模型仍是广播）。
- 效果一（不挂起）：`emit` 永不挂起、永不丢弃。执行体速度与订阅者速度**彻底解耦**——订阅者再慢、甚至冻结，回合都全速跑完、槽位照还。
- 效果二（完整回放）：事件序列是回合的 append-only 日志，replay 全量保留它——**任何时刻订阅都从 `Initial` 开始收到完整序列**。`Initial` 的到达由此成为形式化保证（不再依赖「LLM RTT 封门」的时序论证）；中途加入的订阅者从头拼出整段文本，语义自洽（事件是不可变数据，回放零副作用）；终局后订阅 / 二次 collect 拿到全历史 + 终局（`awaitResult` 只过滤终局，无感）。
- 占用是「回合事件总量」而非「容量预分配」：SharedFlow 缓冲是惰性倍增的环形数组（初始 2，写满翻倍；`Int.MAX_VALUE` 不预分配任何东西）。上界为单回合事件量——千级 delta（`TextDelta` 十字节级）× `maxIterations` 轮 ≈ **1 MB 内**，极端长任务 ≈ 十 MB；驻留期 = 回合存活期 + turn 引用释放（GC 兜底），对照同回合的 LLM token 成本可忽略。无订阅者时同样全量保留（replay 无限 ⇒ `dropOldestLocked` 永不触发），`emit` 仍立即返回。
- **接受的代价**：订阅者冻结（UI 线程死等 bug）时，回合会继续执行到自然终局（继续消耗 LLM token、执行工具副作用）而内存驻留为回合事件总量。「冻结的 UI 是否该拖停 agent」不是 SDK 该替调用方决定的事——要停是 `cancel()` 的职责。有界容量的「满则挂起」本质仍是观察者节流执行者，且恰是 §8.2 死锁窗口的成因，故弃用；终局后用 `MutableSharedFlow.resetReplayCache()` 裁剪到「Initial + 终局」可把驻留降到 O(1)——该 API 存在且线程安全（持实例锁，与 emit 互斥）——但它标注 `@ExperimentalCoroutinesApi`（官方声明可能移除/改名），且清空 replay 后新订阅者只能拿到重置后的事件，**破坏「任何时刻订阅从 `Initial` 开始」的保证**（终局后订阅会拿到零事件、`transformWhile` 直接完成，复活旧架构刚消灭的「静默零事件」坑）。为 GC 本可兜底的场景引入这两重代价，不值。
- 责任边界：核心保证「不丢、不弃、序列完整、不因消费慢而停、任何时刻订阅从 `Initial` 开始」；消费效率与消费方异常（冻结）是消费方责任，核心不做订阅级隔离。

### 6.5 订阅语义（完整定义）

多订阅是广播的天然行为。本设计虽不以多订阅为目标，但也不允许「支持一半」，故完整定义如下，并写入 `Agent.run` KDoc：

| 场景 | 契约 |
|---|---|
| 回合进行中多个订阅者并行订阅 | 广播不分流：每个订阅者独立收到**完整序列**（回放段 + 之后的 live 段），互不竞争 |
| 回合中途加入的订阅者 | 收到**从头开始的完整历史**（`Initial` 起）+ 之后的事件；replay 段与 live 段无交错（SharedFlow 同步快照） |
| 终局后订阅，或对同一条公共流第二次 collect | 收到**完整历史 + 终局**，`transformWhile` 遇终局完成该次收集；**不重复执行回合**。这替代了旧的 `RepeatedCollectionException` —— 重复收集由调用方错误变为合法读取 |
| 错过 `Initial` 的可能性 | **不存在**。replay 全量保留，任何订阅者序列首事件必为 `Initial`（形式化保证，非时序侥幸） |
| 取消单个订阅 | 只终止该订阅；回合照常执行（孤儿），其他订阅不受影响；事后重新订阅仍可取到完整历史 |
| 订阅者代码自身抛异常 | 只终止该订阅；槽位与信箱的退场在执行体 `finally` 中，与订阅完全解耦 |
| `steer() == false` 之后立刻 run | 新轮必定起得来（结构保证，与订阅者数量无关） |

**完整回放对单订阅者的零代码成本**：回放是 SharedFlow 的原生行为，单订阅者（run 后立即订阅的主场景）的事件流形态与 replay=1 无差别——消费顺序、事件内容、活跃消费期的内存水位都一样，无需任何消费端改动；内存代价（全量驻留，见 §6.4）由核心承受，不影响消费端写法。

**现有消费模式的实况**：demo 与 team 的全部消费端均为「单订阅者串行 collect」——`ChatViewModel` 单实例 agent + `isLoading`/`isProcessing` 挡并发；`SessionViewModel`、`Pasture`、`BossAgent`、`Subagent`、`gateway` 同一时刻最多一个收集方。多订阅当前**无实际受益者**；完整定义订阅语义只为让机制边界不含糊（哪些行为是契约、哪些不是），不为多订阅新增任何隔离或订阅级背压能力。

**不承诺的**：跨流的到达顺序。旧架构「Final 送达收集方后才放槽」的同步握手被广播缓冲取代，因此若调用方**同时** collect 旧轮与新轮两条流并合并，可能出现新轮 `Initial` 先于旧轮 `Final` 到达。SDK 只承诺：流内有序；顺序收集（collect 到终局再发下一条，为 awaitResult / UI / BossAgent / gateway 等全部现有消费端的模式）零交错。

## 7. 事件通路实现

```kotlin
// AgentTurn（简化后）
internal class AgentTurn(val query: AgentQuery, val llmCall: suspend (ChatRequest) -> ChatResponse) {
    val events = MutableSharedFlow<AgentEvent>(replay = Int.MAX_VALUE, extraBufferCapacity = 0)
    val steering = SteerInbox()                 // 保留：纯缓冲，不取闸
    private val gate = Semaphore(1)             // 保留：投递 vs 终局裁决互斥
    val terminated = CompletableDeferred<Unit>()// 保留：收尾句柄
    var iterations = 0
    val toolCalls = mutableListOf<AgentResult.ToolCallRecord>()
    lateinit var iterationContext: AgentContext
    fun lock() = gate.acquireUninterruptibly()
    fun unlock() = gate.release()
}
```

`ReActAgent.run` / `runStream`：

```kotlin
override fun run(query: AgentQuery): Flow<AgentEvent> = runTurn(AgentTurn(query) { llmProvider.chat(it) })
override fun runStream(query: AgentQuery): Flow<AgentEvent> {
    val streaming = StreamingRunner(llmProvider)
    lateinit var turn: AgentTurn
    turn = AgentTurn(query) { req -> streaming.run(req) { turn.events.tryEmit(TextDelta(it)) } }
    return runTurn(turn)
}

/**
 * 回合的公共骨架：占活跃槽位 → 在 agent scope 内启动执行体 → 返回广播流。
 * 与架构 1 同名方法语义对位：原来「交由可中断容器执行 + onCompletion 释放」
 * 的两段式收进同一处 —— 启动即执行，收尾在执行体 finally。
 */
private fun runTurn(turn: AgentTurn): Flow<AgentEvent> {
    check(!shutdownSignal.isCompleted) { "Agent is shut down" }
    check(activeTurn.compareAndSet(null, turn)) { "Concurrent run not supported: another run is active" }
    scope.launch {
        try { loop(turn) }
        catch (e: CancellationException) {
            withContext(NonCancellable) { turn.events.emit(AgentEvent.Canceled(e)) }
        }
        catch (e: Throwable) { turn.events.emit(AgentEvent.Failed(e)) }
        finally { retireTurn(turn) }
    }
    return turn.events.transformWhile { event -> emit(event); event !is Terminal }
}
```

不设独立的 `start` 方法——占槽、启动执行体、返回公共流三步本就是同一件事，拆开只是把调用点搬家（与「事件出口落在持有 channel 的一侧」的既有取舍同理）。

`loop` 内部所有 `turn.flow.emit(x)` 改为 `turn.events.emit(x)`；`loop` 不再在末尾 `return result` 供外层发布，而是在自身终局路径内发布终局并正常返回。`StreamingRunner` 的 delta 回调继续用 `tryEmit`（非挂起，不阻塞 LLM 回调路径）——无限缓冲下它事实上总成功，完整文本仍以 `Final` 为权威。

### 7.1 删除清单

| 删除项 | 位置 | 原因 |
|---|---|---|
| `TurnFlow` 类（含 `Attach` 枚举、`canceled`、`canceledCause`、`sink`、`attach`） | `AgentTurn.kt` | 冷流与延迟收集的产物 |
| `RepeatedCollectionException` | `AgentTurn.kt` | 重复收集合法化 |
| `runTurn` 中的 `onCompletion` 收尾与重复收集判定 | `ReActAgent.kt` | 收尾统一在执行体 `finally` |
| `cancel()` 中的「未收集轮次当场退役」分支 | `ReActAgent.kt` | 不存在未启动的回合 |
| `ActiveTurn` 的 LAZY `async` 启动 | — | 执行体直接 `launch` |
| `runTurn` 中的 `start { loop(turn) }` 委托 | `ReActAgent.kt` | 启动/收尾并回 `runTurn` 一处 |

**保留**：闸门（投递 vs 终局裁决仍需互斥）、`SteerInbox`、`retireTurn`、`terminated`、单活跃回合 CAS。

## 8. 调度与死锁

### 8.1 steer 死锁的消除

主线程上 `steer()` 同步 `acquireUninterruptibly()` 取闸；持闸方 `finalize` 也压在同一个线程栈上 → 死锁。执行体迁到 agent scope 后，持闸者可在池内其他线程恢复，成环条件消失。约束见 §5.2。

该迁移只能断开「同线程栈」这一种耦合；闸内 `emit(Final)` 挂起仍可能重新成环，由 §8.2 的无限缓冲彻底消除。

### 8.2 残留窗口（已完全消除）

死锁窗口的成立需要三个条件**同时**满足：

1. `steer()` 调用方线程 T 上同步取闸（同步 true/false 契约决定这一步不可省）；
2. 持闸的 finalize 正在做一次**会挂起**的 `emit(Final)`（缓冲满）；
3. 排干缓冲的那个订阅者的消费调度落在 T 上。

逐条可断性：

- **条件 1 不可断**：跳过闸（`tryAcquire`/超时）要返回 false，但此刻槽位未还，`false ⇒ run 必定起得来` 的承诺被打破（run 会撞 ISE）；异步投递则毁掉同步确认语义。这是 API 形状决定的。
- **条件 2 已断**：`replay = Int.MAX_VALUE` ⇒ 缓冲容量溢出后 coerce 为 `Int.MAX_VALUE` ⇒ `emit` 永不挂起 ⇒ 闸永不横跨挂起点，死锁与线程如何耦合**无关地**消失。这是本次采用的解法。
- **条件 3 不可靠**：`flowOn` 只挪一站，下游仍在收集方上下文消费；收集方选什么线程是调用方自由，SDK 管不到。

**已否决的替代解法**（记录以备将来复议）：

| 解法 | 否决原因 |
|---|---|
| `emit(Final)` 挪到闸外 | `steer()==false` 的承诺从「终局已入缓冲」退化为「终局已裁决」，旧 Final 可能仍在缓冲中，调用方立即 run 后两条流的合并收集可能看到新 `Initial` 先于旧 `Final` |
| 有界容量（4096 等） | 只把窗口推远不消除，且「满则挂起」本身就是留窗口的机制；代价见 §6.4 |
| 闸内只做裁决、投递交给独立的非阻塞发射器 | 引入额外协程与顺序保证成本，收益被 Int.MAX 方案完全覆盖 |

因此：**§8 不再存在残留死锁窗口**，Agent.Steerable/Agent.Cancelable 的 KDoc 无需再写「死锁兜底说明」，只需保留 §5.2 的 dispatcher 约束（它是双保险：即便将来有人把缓冲改回有界，条件 2 的窗口也不会与单线程调用方成环）。

## 9. 生命周期与关闭

`ReActAgent` **新实现 `Agent.Shutdownable`**（此前未实现）：

```kotlin
private val shutdownSignal = CompletableDeferred<Unit>()

override fun shutdown() {
    if (!shutdownSignal.complete(Unit)) return    // 幂等
    scope.cancel()                                 // 活跃回合以 NonCancellable 发 Canceled
}
```

- `run` / `runStream` / `steer` 在关闭后同步抛 `IllegalStateException`，与 `SpawnedAgent` 现有口径一致。
- shutdown 是**唯一**终止 scope 的路径。
- **`SpawnedAgent.shutdown()` 改为 `delegate.shutdown()`（时序修复）**：现实现「`delegate.cancel(msg)` 后立即 `mcpRegistry.unregisterAll()`」在架构 2 下有竞态——cancel 只投递、回合在 agent scope 异步收尾，MCP 子进程会在回合仍存活时被拆掉，在途工具调用拿到已死的 client。改走 `delegate.shutdown()`（内部 `scope.cancel()`，活跃回合以 `NonCancellable` 发完 `Canceled`）并等待收尾后再 `unregisterAll()`。
- **`Beast`（`Ox` / `Horse`）是同一模式的第二处**：它们每次 dispatch 新建 `ReActAgent`，声明为一次性 agent（`Beast : Agent.Shutdownable`，能力显式），由订阅处（Pasture）在收集结束的 `finally` 中显式 `shutdown()`（详见 §10）。

## 10. 消费端适配

| 消费端 | 位置 | 改动 |
|---|---|---|
| `awaitResult` | `agent:core AgentExtensions.kt` | 过滤三终局；`Canceled → throw cause`；`Failed → throw cause` 不变 |
| BossAgent 用户轮 | `team BossAgent.runUserRound` | `catch (CancellationException)` 改为读取 `is AgentEvent.Canceled` 分支（channel 以该 cause 关闭） |
| BossAgent 报告轮 | `team BossAgent.runResultRound` | `Canceled` 事件不转发到 `report` 热流（单轮取消不关闭热流），调度器照常唤醒 |
| BossAgent 关闭 | `team BossAgent.shutdown` | **新增** `(innerAgent as? Agent.Shutdownable)?.shutdown()`。架构 1 靠 `scope.cancel()` 隐式级联杀死 inner 执行体，架构 2 下该级联失效，必须显式关闭 |
| Pasture 任务收集 | `team Pasture.run` | when 补 `is AgentEvent.Canceled ->` 分支 → `handleTerminal(taskId, event.cause)`（现状落进 `else` 且 `failed` 为 null，取消会被标成 **DONE**，下游拿到空结果） |
| Pasture 订阅收尾 | `team Pasture.dispatch` | 收集协程加 `finally { beast.shutdown() }`：任务终局（collect 完成）→ agent 用毕即废；任务取消（`handleCancellation` 的 `node.job?.cancel()` → collect 被取消 → finally）→ 在途回合被杀，「取消收集 job」恢复为「终止回合」的架构 1 级联语义。`handleCancellation` 与 `DagNode.job` 均不改 |
| Beast 能力与生命周期 | `team Beast` / `Ox` / `Horse` | **`Beast : Agent, Agent.Shutdownable`（`by inner` 实现）；不提供 `cancel` 接口**——Beast 的用途就是一回合消亡，整体作废即全部语义。`inner` 从 `run` 内局部变量提升为字段（Beast 每次 dispatch 新建、只用一轮，一次性语义不变）；`run` 纯透传 `inner.run(query)`，不附加任何隐藏行为（保持 `Agent` 契约「取消订阅不终止回合」的一致性）。生命周期钉死在订阅处：Pasture `dispatch` 的 `finally { beast.shutdown() }`（见下一行）。运行中的协程是 GC root，在途回合不 shutdown 就会在无观察者的情况下继续烧 token 到自然终局——架构 1 靠 bossScope 级联杀掉它们，架构 2 下必须由本行恢复 |
| ChatViewModel | `demos:agent ChatViewModel` | catch 改 when `Canceled` 渲染打断气泡；**`clearMessages` 改为「先掐订阅再整体作废」**：`runJob?.cancel()`（掐断订阅，防 shutdown 后迟到的 `Canceled` 把打断气泡写进刚清空的消息列表）+ `(agent as? Agent.Shutdownable)?.shutdown()`（`clearMessages` 重建 memory 与 agent 实例，旧 agent 永不再用——语义是 agent 重开而非停一轮，用 shutdown 作废而非 cancel 打断） |
| SessionViewModel | `demos:agent SessionViewModel` | **`sendMessageInternal` 每次新建 agent 改为持有单例**：现「每条消息 `DemoAgentFactory.create(...)`」在架构 2 下每次遗留一个仍可能持有在途回合的私有 scope；会话场景 agent 语义上就是长驻的，与 ChatViewModel 的 `private var agent` 对齐为单实例，不再需要逐条收尾 |
| BossViewModel | `demos:team BossViewModel` | `catch (CancellationException)` 分支改 when `Canceled`（现状取消会被当成「无事件正常结束」，loading 悬挂）；onCleared 的 `shutdown()` 因 §10 BossAgent 关闭补丁而恢复完整语义 |
| BossDelegation | `demos:realtime s2s` | `catch (CancellationException)` → `is AgentEvent.Canceled ->` 回报「已取消」 |
| gateway `DefaultAgentRunner` | `gateway:app` + `gateway:jvm` | 不变（走 `awaitResult`；两处同名类均依赖 core，提交 1 一起覆盖） |
| `Subagent` | `agent:subagent` | 不变（走 `awaitResult`） |

**BossAgent 的 scope 划分**：bossScope 维持现状，管理调度与看板协程；inner agent 的私有 scope 管理回合执行。两者职责分离，不合并。

## 11. 测试

### 11.1 断言迁移

`CancelableTest` 中所有「收集以 `CancellationException` 结束」的断言改为「流以 `Canceled` 事件收尾」（`events.last() is AgentEvent.Canceled`）；`cancel()` 返回句柄相关用例保留。

### 11.2 删除的用例

| 用例 | 原因 |
|---|---|
| `cancel before the round starts abandons the round` | 不存在未启动回合 |
| `cancel reason is carried by the late collection of an abandoned round` | 同上 |
| `late collection of an abandoned round must not steal the running round's cancel slot` | 同上 |
| `a rejected repeated collection must not release the running round's slot` | 重复收集合法化 |

### 11.3 新增用例

- 未收集也执行：`run` 后不 collect，回合照常完成，槽位照常归还；
- 取消订阅不终止回合：collector 取消后，回合仍完成，重新订阅可取到完整历史与终局；
- 同一条公共流第二次 collect 取到完整历史 + 终局，且不重复执行（LLM 调用次数不变）；
- 两个订阅者各自收到完整序列（同一轮事件不分流）；
- **迟到订阅必见 `Initial`**：回合跑到中途或已终局才订阅，序列首事件为 `Initial`，且序列与先订阅者同构（回放段 + live 段的拼接无交错、无重复）；
- 终局唯一：任何取消/终局竞态下每个订阅者恰好看到一个终局事件；
- `steer()==false` 后立刻 run 必定成功（顺序收集零交错）；
- `ReActAgent.shutdown()` 后 `run` 抛 ISE，活跃回合收到 `Canceled("Agent is shut down")`。
- `SpawnedAgent.shutdown()`：活跃回合收到 `Canceled`，且 `unregisterAll()` 在回合退场之后执行（MCP 不会被中途拆掉）。
- `Pasture` 取消链路（现有 `handleCancellation` 不改）：RUNNING 任务 `node.job?.cancel()` → dispatch 的 `finally` → `beast.shutdown()`，在途回合被杀；节点终态为 `CANCELED`（不是 `DONE`），且取消后延迟到达的 `Canceled` 终局被 `handleTerminal` 幂等忽略。
- `Beast` 的 inner 生命周期：`collect` 正常完成后 `beast.shutdown()` 被调用；`collect` 被取消（`handleCancellation` 或 bossScope 取消）时同样触发，在途回合被杀。断言点：shutdown 后 inner `run` 抛 ISE，且被中断的回合不以 `Final` 写回 `node.result`。

### 11.4 测试调度器（迁到私有 scope 的必付代价）

agent 私有 scope 不再继承 `runTest` 的测试调度器。所有 `ReActAgent` 构造点补充 `dispatcher = StandardTestDispatcher(testScheduler)`（约 60 处，机械改动）。`SteerableTest` 中依赖 `advanceUntilIdle` 的编排由此保持确定性；`GatedLlm` / `SlowUnwindLlm` 类「挂在 Deferred 上」的用例模式不变；`runTest` 结束时「无未完成协程」的校验也依赖它，否则套件直接变红（见 §5.1）。

## 12. 实施顺序（多步原子提交）

| # | 提交 | 内容 | 验证 |
|---|---|---|---|
| 1 | `feat(agent): 新增 Canceled 终局事件` | `AgentEvent.Canceled` + `awaitResult` 三终局适配 + `Agent` 家族 KDoc 契约改写 | core 测试通过 |
| 2 | `refactor(agent): 回合执行体迁至 agent 私有 scope 并广播事件` | `AgentTurn` 重写（删 `TurnFlow` / `RepeatedCollectionException`）、`ReActAgent` 私有 scope + internal dispatcher 参数 + `runTurn`（占槽 + `launch` 执行体外壳 + 返回广播流）；测试构造点同步补 dispatcher | core 测试通过 |
| 3 | `feat(agent): ReActAgent 实现 Shutdownable` | 关闭信号 + `scope.cancel()` + 关闭后 ISE | core 测试通过 |
| 4 | `refactor(agent): 移除冷流兜底与重复收集防护` | 删迟收集/重复收集相关 KDoc、补偿代码与测试 | 全量测试通过 |
| 5 | `fix(team): BossAgent 消费 Canceled 事件并在关闭时终止内层 agent` | `runUserRound` / `runResultRound` / `shutdown`；`SpawnedAgent.shutdown` 改 `delegate.shutdown()` | team + config 测试通过 |
| 6 | `fix(team): Beast 声明为可关闭的一次性 agent 并处理 Pasture 的 Canceled 终局` | `Beast : Agent.Shutdownable`（`Ox`/`Horse` 改持 inner 字段 + `by inner` 透传，不提供 `cancel`）；`Pasture.dispatch` 加 `finally { beast.shutdown() }`、`Pasture.run` when 补 `Canceled` 分支，`handleCancellation` 与 `DagNode.job` 保持原样 | team 测试通过 |
| 7 | `refactor(demos): 聊天、团队与 S2S 消费端改为纯事件判定取消` | `ChatViewModel`（`Canceled` 分支渲染打断气泡；`clearMessages` 掐订阅 + `shutdown` 重开）/ `SessionViewModel`（agent 单例化）/ `BossViewModel` / `BossDelegation` | demos 编译通过 |
| 8 | `test(agent): 迁移取消断言并补充广播/孤儿回合用例` | §11 全部 | 全量测试通过 |
| 9 | `docs(agent): 定稿架构 2 设计文档` | 本文件入库、删除 `agent/core/DESIGN.md` | — |

提交 2 与 8 存在耦合（新实现需要新测试才绿），实施时可将提交 8 并入提交 2，或以「每个提交独立可构建」为准调整顺序。提交 5/6/7 相互独立，可并行。

## 13. 验收标准

1. `agent:core`、`agent:config`、`team`、`agent:subagent`、`demos:agent`、`demos:realtime` 全量测试通过。
2. 全仓库不再引用 `TurnFlow`、`RepeatedCollectionException`、`Attach`；`Beast` 的 inner agent 在 `collect` 结束（含取消）后必然被 shutdown；`Beast` 不新增 `cancel` 接口。
3. 所有 `run` / `runStream` 消费点在取消场景下通过 `AgentEvent.Canceled` 分支处理，不再依赖 `catch (CancellationException)` 判断回合终局；任务/回合的终止走 `Cancelable.cancel` / `Shutdownable.shutdown`。唯一允许以 `job.cancel()` 终止回合的路径是 Pasture 对 Beast 的订阅——其终止动作显式写在 `dispatch` 的 `finally { beast.shutdown() }` 中，属能力接口的显式调用，而非流内的隐藏转译。
4. `agent.core.DESIGN.md` 已删除，设计中不再存在第二份事实源。

## 14. 开放风险

| 风险 | 影响 | 处置 |
|---|---|---|
| 无限缓冲 + 全量回放驻留 | 长流式回合 + 消费方冻结或无人收集 → 回合完整事件日志驻留至引用释放 | 上界为单回合事件量（万级 delta ≈ 1 MB 内，极端十 MB）；GC 兜底；冻结时正确处置是调用方 `cancel()`，非 SDK 代决（见 §6.4） |
| agent 私有 scope 不受调用方生命周期约束 | 调用方退出后孤儿回合继续执行 | 由 `shutdown()` / `cancel()` 显式终止；消费端已按此适配（Beast 在订阅处 `finally` 调 `shutdown()`，仅声明能力不会自动触发） |
| 一次性 agent 依赖订阅处收尾 | 新消费方 `collect` 后忘调 `shutdown()`，则 inner 回合变永久孤儿 | 能力已在接口上声明（`Beast : Agent.Shutdownable`），漏调仍会编译通过；当前唯一消费方 Pasture 在 `finally` 中调用，覆盖完成/异常/取消三条路径。新增消费方须遵循同一模式 |
| 多订阅与订阅取消语义无真实消费者 | 文档承诺的能力未经实战验证 | 已在 §6.5 明示「当前无受益者」；不为它加隔离/补发/背压，消费端维持串行 collect |
| 跨流到达顺序不保证 | 同时合并新旧两条流的消费者可能看到 Initial 先于 Final | 明确写入 KDoc；现有消费端均非此模式 |
| 测试需注入 dispatcher | 约 60 处构造点机械改动 | 提交 2 内完成 |
| `handleCancellation` 与收集协程的竞态 | 取消收集 job 后节点已标 CANCELED，若 shutdown 生效前回合恰好走到终局，收集协程仍可能再走一次 `handleTerminal` | `handleTerminal` 已有终态幂等守卫（DONE/FAILED/CANCELED 直接 return），无需新增代码，测试覆盖 |
