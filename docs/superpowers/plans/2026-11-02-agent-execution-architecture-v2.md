# Agent 执行架构 v2 实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `run` 从「冷流 + 收集方驱动执行 + 取消走异常」演进为「启动即执行 + 广播共享 + 取消走 `AgentEvent.Canceled` 领域事件」。

**Architecture:** `ReActAgent` 持有私有 `CoroutineScope(SupervisorJob + Dispatcher)`，每次 `run` 在 scope 内 `launch` 执行体；事件走 `MutableSharedFlow(replay = Int.MAX_VALUE)` 全量回放广播；公共流用 `transformWhile` 在终局事件后完成；取消 emit 独立 `Canceled` 事件（`NonCancellable` 内）。

**Tech Stack:** Kotlin、kotlinx.coroutines（SharedFlow / transformWhile / SupervisorJob）、kotlin.test + kotlinx-coroutines-test（runTest / StandardTestDispatcher）。

**设计规格（唯一事实源）：** `docs/superpowers/specs/2026-11-02-agent-execution-architecture-v2-design.md`（下称 spec）。实现细节与本计划冲突时，以 spec §5-§10 为准。

## Global Constraints

- 只 commit 不 push（用户全局规范）。git 用绝对路径 `"D:/Program Files/Git/cmd/git.exe"`；多行 commit message 用 `-F` 文件。
- 提交格式 `<type>(<module>): <subject>`，module 取值：core 提交用 `agent`，team 提交用 `team`，demos 提交用 `demos`（spec §12 已定稿各提交 message，照抄）。
- 每次 commit 前先 `git diff --stat` 核实实际变更与 message 一致。
- 执行体 dispatcher 约束（spec §5.2）：并发度 > 1 或与调用线程不相交；生产恒 `Dispatchers.Default`；测试注入 `StandardTestDispatcher(testScheduler)` 是已知受控例外。
- 不加 backward-compat 垫片：旧路径（TurnFlow / RepeatedCollectionException / 迟收集兜底）删干净，不留 `@Deprecated` 过渡层。
- 测试命令（Windows bash，gradlew 在仓库根）：
  - core: `./gradlew :agent:core:test`
  - config: `./gradlew :agent:config:test`
  - team: `./gradlew :team:test`
  - demos 编译: `./gradlew :demos:agent:compileDebugKotlin :demos:team:compileDebugKotlin :demos:realtime:compileDebugKotlin`
  - 全量: `./gradlew test`
- 本计划「提交 N」对应 spec §12 的第 N 个提交；提交 8 的测试已并入各提交（见下文任务划分），最终提交数为 8 个（spec §12 的 9 号 docs 提交已由分支 `docs/agent-exec-architecture-v2` 上的 `2ff4bfef` 完成，不再重复）。

## 任务总览（与 spec §12 对齐）

spec §12 共 9 个提交，其中提交 9（docs）已在 spec 定稿时提交（`2ff4bfef`）。提交 2 与 8（测试）耦合，spec 明示「可将提交 8 并入提交 2」——本计划采用「断言迁移跟随各自提交、新增用例集中在提交 2」策略，各提交独立可构建、独立测试绿。

| 任务 | 提交 | 验证 |
|---|---|---|
| Task 1 | `feat(agent): 新增 Canceled 终局事件` | core 测试通过 |
| Task 2 | `refactor(agent): 回合执行体迁至 agent 私有 scope 并广播事件` | core 测试通过 |
| Task 3 | `feat(agent): ReActAgent 实现 Shutdownable` | core 测试通过 |
| Task 4 | `refactor(agent): 移除冷流兜底与重复收集防护` | 全量测试通过 |
| Task 5 | `fix(team): BossAgent 消费 Canceled 事件并在关闭时终止内层 agent` | team + config 测试通过 |
| Task 6 | `fix(team): Beast 声明为可关闭的一次性 agent 并处理 Pasture 的 Canceled 终局` | team 测试通过 |
| Task 7 | `refactor(demos): 聊天、团队与 S2S 消费端改为纯事件判定取消` | demos 编译通过 |
| Task 8 | `docs(agent): 删除 DESIGN.md 旧事实源` | — |

注：Task 2 的「新增广播/孤儿回合用例」即 spec §11.3 的前 7 条；Task 3 覆盖 §11.3 后 4 条（shutdown 相关）；Task 4 删除 §11.2 的 4 个用例。agent/core/DESIGN.md 的删除从 spec §12 提交 9 中拆出来并入本计划收尾（内容仍是 spec 定稿那一笔的补完）。

---

### Task 1: 新增 Canceled 终局事件

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentEvent.kt`
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentExtensions.kt`
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/Agent.kt`（KDoc 契约改写，代码零改动）

**Interfaces:**
- Produces: `AgentEvent.Canceled(public val cause: CancellationException) : AgentEvent` —— 后续所有任务的消费端判型依据。
- Produces: `awaitResult()` 新契约：`Canceled → throw terminal.cause`（载荷即 CancellationException，不包装）。

**说明：** 本提交只加类型与消费端适配，不改执行架构——`Canceled` 事件暂无产地（ReActAgent 仍走冷流取消异常），但 `awaitResult` 已把三终局过滤写全，`filter` 集合扩大不会改变现有行为（现状没有 Canceled 事件可过滤）。编译与全部既有测试必须保持绿。

- [ ] **Step 1: 在 AgentEvent.kt 增加事件类型与序列说明**

在 `AgentEvent.kt` 的 `Failed` 之后、`MemoryCompressing` 之前插入：

```kotlin
    /**
     * 终态事件：回合被取消后发出（如 [Agent.Cancelable] 打断 / [Agent.Shutdownable] 关闭）。
     *
     * 取消不是失败：失败的正确默认动作是重试/告警，取消是停止/提示打断。
     * 载荷用 [CancellationException] —— `awaitResult` 可直接抛出而不被协程框架
     * 误判为失败；message 承载取消原因（诊断用途，如 "Agent is shut down" 与用户打断的区分）。
     */
    public data class Canceled(public val cause: CancellationException) : AgentEvent
```

同时更新文件头 KDoc 的事件序列描述（`Failed` 后补 `Canceled`）：

```kotlin
/**
 * Agent 事件流模型。
 *
 * 所有事件在流式/非流式模式下行为一致，非流式模式的事件序列是流式模式的子集。
 * 流式模式额外提供 [TextDelta] 增量文本事件，用于实时预览。
 *
 * 事件序列：`Initial → [...增量/工具事件...] → Final | Failed | Canceled`（终局三选一）。
 *
 * **非流式模式（run）：**
 * ```
 * Initial → [ToolCallExplanation → ToolCallStart → ToolCallEnd]? → Final | Failed | Canceled
 * ```
 *
 * **流式模式（runStream）：**
 * ```
 * Initial → [TextDelta* → ToolCallExplanation → ToolCallStart → ToolCallEnd]? → TextDelta* → Final | Failed | Canceled
 * ```
 *
 * - 一次 run(Stream) 可能包含多个 [ToolCallExplanation] → [ToolCallStart] → [ToolCallEnd] 事件循环（多轮工具调用）
 * - 一个 [ToolCallExplanation] 后面可能跟着多个 [ToolCallStart] → [ToolCallEnd] 事件对（一次调用多个工具）
 */
```

并在 import 区加 `import kotlinx.coroutines.CancellationException`。

- [ ] **Step 2: 改写 awaitResult 为三终局**

`AgentExtensions.kt` 中 `awaitResult` 整体替换为：

```kotlin
/**
 * 等待并返回 agent 运行的最终结果。
 *
 * 终局三选一（与 [Agent.run] 的契约一致）：
 * - [AgentEvent.Final] → 返回其 [AgentEvent.Final.result]
 * - [AgentEvent.Failed] → 抛出其 [AgentEvent.Failed.cause]（原始异常，不重新包装）
 * - [AgentEvent.Canceled] → 抛出其 [AgentEvent.Canceled.cause]（载荷本就是
 *   [kotlinx.coroutines.CancellationException]，抛出不误判失败、不重新包装）——
 *   保持「取消即异常」的返回形态，衔接既有调用方心智。
 *
 * 其他 [AgentEvent] 子类型被忽略。
 * Flow 自身异常按 Flow 协议传播。
 */
public suspend fun Flow<AgentEvent>.awaitResult(): AgentResult {
    val terminal = filter { it is AgentEvent.Final || it is AgentEvent.Failed || it is AgentEvent.Canceled }.first()
    return when (terminal) {
        is AgentEvent.Final -> terminal.result
        is AgentEvent.Failed -> throw terminal.cause
        is AgentEvent.Canceled -> throw terminal.cause
        else -> error("unreachable: filter restricts to Final|Failed|Canceled, got ${terminal::class.simpleName}")
    }
}
```

- [ ] **Step 3: 改写 Agent.kt 契约 KDoc（代码不动）**

`Agent` 接口主 KDoc 终局段替换为：

```
 * [run] 通过 [AgentEvent] Flow 向调用方推送中间状态，终局**三选一**：
 * - [AgentEvent.Final] —— 正常终结，携带最终结果；
 * - [AgentEvent.Failed] —— 运行失败，携带原始异常；
 * - [AgentEvent.Canceled] —— 回合被取消（如：[Agent.Cancelable] 打断、[Agent.Shutdownable] 关闭），
 *   携带 [kotlinx.coroutines.CancellationException] 载荷。
 *
 * 三者互斥且必有其一：一次收集以终局事件收尾。
```

`run` 的 KDoc「轮次语义」段（第 34-38 行）替换为：

```
     * **轮次语义**：调用即启动回合并立即执行（非 suspend、不等收集）——返回的 [Flow] 只是
     * 观察窗口，订阅不驱动执行。同一回合的流可多次收集（广播回放：从 [AgentEvent.Initial]
     * 起的完整历史 + 终局）；取消订阅不终止回合（孤儿回合），终止回合须 [Agent.Cancelable.cancel]。
```

`Streamable.runStream` 的「轮次语义与 [run] 一致（只允许收集一次、不收集须 cancel 释放占用）」段替换为：

```
     * 轮次语义与 [run] 一致（调用即启动、广播回放、取消订阅不终止回合）——
     * 两者是同一个"轮次"的两种执行方式，不能对同一轮既 [run] 又 [runStream]。
```

`Cancelable.cancel` KDoc 中「进行中的 [Agent.run] flow 收集以 [CancellationException] 结束」及 `- 若在[cancel]之后被收集...` 两条替换为：

```
     * [cancel] 只作用于当前回合，不改变 Agent 自身状态：打断后 Agent 仍可再次 [Agent.run]。
     * 终止协议：在途回合以 [AgentEvent.Canceled] 终局事件收尾（载荷携带 [cause]），
     * 消费方按事件分支处理，不再 catch 取消异常。
```

`Shutdownable.shutdown` KDoc 中「终止协议统一：进行中的 [Agent.run] flow 收集以 [CancellationException] 结束（尚未被收集的 flow 在收集时同样以 [CancellationException] 结束，不静默零事件）」替换为：

```
     * 终止协议统一：进行中回合以 [AgentEvent.Canceled]（"Agent is shut down"）终局事件收尾；
```

`Cancelable` KDoc 中「**未收集轮次的释放义务**」整段删除（架构 2 无未收集轮次）。`cancel` 参数 KDoc 中「收集方见到的 [CancellationException] 会携带该原因」改为「[AgentEvent.Canceled] 载荷携带该原因」。

- [ ] **Step 4: 编译并跑 core 测试**

Run: `./gradlew :agent:core:test`
Expected: PASS（本提交不改行为，只加类型与文案）。

- [ ] **Step 5: Commit**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/AgentEvent.kt agent/core/src/main/kotlin/io/github/yeyi/agent/AgentExtensions.kt agent/core/src/main/kotlin/io/github/yeyi/agent/Agent.kt
git commit -m "feat(agent): 新增 Canceled 终局事件"
```

---

### Task 2: 执行体迁至 agent 私有 scope 并广播事件（核心提交）

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentTurn.kt`（删 TurnFlow / RepeatedCollectionException，events 换 SharedFlow）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt`（scope + runTurn + cancel + loop + finalize）
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/TurnFlowTest.kt`（删除该文件）
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/CancelableTest.kt`（断言迁移 + 删 4 用例 + dispatcher）
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/SteerableTest.kt`（构造点补 dispatcher = 12 处）
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/ReActAgentTest.kt`（构造点补 dispatcher = 23 处）
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentHookTest.kt`（构造点补 dispatcher = 17 处）
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/AgentResultExtensionsTest.kt`、`CancelableTest.kt`（agentOf 工厂）、`ReActAgentMultimodalTest.kt`（构造点补 dispatcher）

**Interfaces:**
- Consumes: `AgentEvent.Canceled`（Task 1）。
- Produces: `AgentTurn.events: MutableSharedFlow<AgentEvent>`（replay = Int.MAX_VALUE）、`ReActAgent` internal 构造参数 `dispatcher: CoroutineDispatcher = Dispatchers.Default`、`cancel(cause)` 返回 `turn.terminated`。
- 注意：本任务包含 Task 3（shutdown）的前置字段 `shutdownSignal` 吗？**不包含**——`runTurn` 里 `check(!shutdownSignal.isCompleted)` 由 Task 3 引入，本任务的 `runTurn` 不含该行。

**本任务同时完成 spec §11.2 的用例删除与 §11.3 新增用例的前 7 条（Cancelled 事件断言迁移 + 广播/孤儿/多订阅用例），确保提交后 core 测试绿。**

- [ ] **Step 1: 重写 AgentTurn.kt**

`AgentTurn` 类体的 `flow` 字段与 TurnFlow 类、RepeatedCollectionException 全删。重写后的完整文件（保留 `SteerInbox` 不动）：

```kotlin
package io.github.yeyi.agent

import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import kotlinx.coroutines.CompletableDeferred
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
     * 用 [Semaphore] 而非 [java.util.concurrent.locks.ReentrantLock]: 终局窗口会跨越挂起点 ('emit' 是 suspend),
     * 协程恢复后可能换线程; ReentrantLock 是线程亲和的, 换线程 unlock 会抛
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
```

（`SteerInbox` 类整体保留原样，不改动。）

- [ ] **Step 2: 改 ReActAgent —— scope 字段与构造参数**

构造器签名（`ReActAgent.kt:41-49`）改为：

```kotlin
public class ReActAgent internal constructor(
    private val persona: AgentPersona,
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry,
    memory: Memory,
    private val modalityAdapter: ModalityAdapter,
    private val maxRounds: Int,
    private val maxIterations: Int,
    private val hook: AgentHook = NoOpAgentHook,
    dispatcher: CoroutineDispatcher = Dispatchers.Default,
) : Agent, Agent.Streamable, Agent.Steerable, Agent.Cancelable {
    private val memory = RoundsBoundedMemory(RepairedMemory(memory), maxRounds, llmProvider)

    /**
     * agent 私有执行 scope —— 回合生命周期归 agent，订阅只是观察。
     *
     * SupervisorJob：单回合失败只杀本轮 job，不废 agent。dispatcher 是 internal 注入口，
     * 仅供同模块测试注入 StandardTestDispatcher 保 runTest 虚拟时间；生产恒 Dispatchers.Default。
     */
    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
```

import 区新增：`kotlinx.coroutines.CoroutineDispatcher`、`kotlinx.coroutines.CoroutineScope`、`kotlinx.coroutines.Dispatchers`、`kotlinx.coroutines.SupervisorJob`、`kotlinx.coroutines.flow.MutableSharedFlow`、`kotlinx.coroutines.flow.transformWhile`、`kotlinx.coroutines.launch`；删掉不再用的 `kotlinx.coroutines.flow.onCompletion`。

- [ ] **Step 3: 改 run / runStream / runTurn**

```kotlin
    override fun run(query: AgentQuery): Flow<AgentEvent> =
        runTurn(AgentTurn(query) { req -> llmProvider.chat(req) })

    override fun runStream(query: AgentQuery): Flow<AgentEvent> {
        val streaming = StreamingRunner(llmProvider)
        lateinit var turn: AgentTurn
        turn = AgentTurn(query) { req ->
            streaming.run(req) { turn.events.emit(AgentEvent.TextDelta(it)) }
        }
        return runTurn(turn)
    }

    /**
     * 回合的公共骨架：占活跃槽位 → 在 agent scope 内启动执行体 → 返回广播流。
     * 启动即执行，收尾在执行体 finally —— 与订阅彻底解耦（孤儿回合语义）。
     */
    private fun runTurn(turn: AgentTurn): Flow<AgentEvent> {
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

公共流的完成判定需要「终局族」扩展属性——sealed interface 无法事后加父接口，故用扩展属性（internal，不进公开 API）。在 `AgentEvent.kt` 末尾添加：

```kotlin
/** 终局事件族（Final / Failed / Canceled）—— core 内部判定用。 */
internal val AgentEvent.isTerminal: Boolean
    get() = this is Final || this is Failed || this is Canceled
```

`runTurn` 返回行改为：

```kotlin
        return turn.events.transformWhile { event -> emit(event); !event.isTerminal }
```

- [ ] **Step 4: 改 cancel / retireTurn**

`cancel`（替换 `ReActAgent.kt:103-121`）：

```kotlin
    override fun cancel(cause: String?): Job? {
        val turn = activeTurn.get() ?: return null // 无活跃轮次 → no-op（幂等）
        // 启动即执行：cancel 恒打断在途执行体。回合若恰好已自然终局（job 已完成），
        // cancel 是无害空操作——终局仍以 Final/Failed 呈现，不会伪造 Canceled。
        // 收尾句柄是 turn.terminated，不是 job：job 完成时订阅者可能还没收到终局，
        // terminated 在「还槽 + 关信箱」之后才完成——join 它才能安全开新轮。
        turn.job?.cancel(CancellationException(cause))
        return turn.terminated
    }
```

`retireTurn` 保持不变（还槽 + 关信箱 + terminated.complete）。其 KDoc 中「先还槽、后关信箱：恒有『信箱已关 ⟹ 槽位已空』」的论证仍成立，保留；涉及「终局路径（[finalize]）顺手在闸内调它」的段落改写为「收尾统一由执行体的 finally 调用（[finalize] 不再调它）」。

- [ ] **Step 5: AgentTurn 增补 job 字段**

在 `AgentTurn` 的 `terminated` 字段后加：

```kotlin
    /** 本轮执行体 job —— cancel() 的打断目标，runTurn 启动执行体时登记。 */
    var job: kotlinx.coroutines.Job? = null
```

并在 `runTurn` 中把 launch 结果登记到轮次：

```kotlin
        scope.launch {
            try { loop(turn) }
            catch (e: CancellationException) {
                withContext(NonCancellable) { turn.events.emit(AgentEvent.Canceled(e)) }
            }
            catch (e: Throwable) { turn.events.emit(AgentEvent.Failed(e)) }
            finally { retireTurn(turn) }
        }.also { turn.job = it }
```

- [ ] **Step 6: 改 loop / loopOnce / callTools / finalize 的发射点**

- `loop` 内 `turn.flow.emit(...)` → `turn.events.emit(...)`（Initial、MemoryCompressProxyHook 闭包、Failed 三处）。
- `loop` 的 catch 分支保持「失败事件化后正常返回」：现有实现 `catch (cause: Throwable)` 中 CancellationException 先 repairOrphans 再 `throw cause` —— **保留 throw**（外壳 catch 负责发 Canceled 事件）；非取消路径 emit(Failed) 后正常返回（现状已如此）。
- `loopOnce` / `callTools` / `finalize` 中所有 `turn.flow.emit` → `turn.events.emit`。
- `finalize` 的 `retireTurn(turn)` 调用**删除**（收尾统一在执行体外壳 finally；闸内只做裁决 + emit(Final)）。`finalize` 末尾 `return result` 保留。
- `runStream` 的 delta 回调（Step 3 已展示）用 `turn.events.emit(...)`。spec §7 说 StreamingRunner 的 delta 用 `tryEmit`——但 `MutableSharedFlow.emit` 在无限缓冲下不挂起、与 `tryEmit` 等价，且回调是 suspend 上下文，用 `emit` 类型更简（保持与 spec 代码一致用 `tryEmit` 亦可；本计划用 `emit`，两种皆正确）。

`loop` 完整改造后（并发/取消语义不变，仅发射口与 Initial 前置项调整）：

```kotlin
    private suspend fun loop(turn: AgentTurn) {
        try {
            turn.iterationContext = buildContext(turn.iterations)
            turn.events.emit(AgentEvent.Initial(turn.query))
            memory.attachHook(
                MemoryCompressProxyHook(hook) { turn.events.emit(it) },
                turn.iterationContext
            )
            modalityAdapter.archive(ChatMessage.User(turn.query.parts))
                .addToMemory(memory, turn.query.label)
            while (turn.iterations < maxIterations) {
                turn.iterations++
                loopOnce(turn)?.let { return }
            }
            throw AgentException.MaxIterations(maxIterations)
        } catch (cause: Throwable) {
            if (cause is CancellationException) {
                withContext(NonCancellable) {
                    RepairedMemory.repairOrphans(memory, RepairedMemory.CANCELLED)
                }
                throw cause
            }
            ChatMessage.Assistant(cause.message ?: cause.toString()).addToMemory(memory, "error")
            hook.safeInvoke { onRunFailed(turn.iterationContext, cause) }
            turn.events.emit(AgentEvent.Failed(cause))
        }
    }
```

`finalize` 改后（只删 retireTurn 调用与相关注释）：

```kotlin
    private suspend fun finalize(turn: AgentTurn, response: ChatResponse): AgentResult? {
        turn.lock()
        if (!turn.steering.isEmpty()) {
            turn.unlock()
            consumeSteering(turn)
            return null
        }
        val result = AgentResult(
            message = response.message,
            iterations = turn.iterations,
            toolCalls = turn.toolCalls.toList(),
            usage = response.usage,
        )
        hook.safeInvoke { onRunCompleted(turn.iterationContext, result) }
        try {
            turn.events.emit(AgentEvent.Final(result))
        } finally {
            turn.unlock()
        }
        return result
    }
```

- [ ] **Step 7: 删除 TurnFlowTest.kt**

`TurnFlowTest` 只测 TurnFlow 的未启动发射守卫——类已删，测试文件整体删除。

```bash
rm agent/core/src/test/kotlin/io/github/yeyi/agent/TurnFlowTest.kt
```

- [ ] **Step 8: CancelableTest 断言迁移 + 用例删除**

对 `CancelableTest.kt`：

a) `agentOf` 工厂补 dispatcher（构造参数列表末尾加 `dispatcher = StandardTestDispatcher(testScheduler)`），import `kotlinx.coroutines.test.StandardTestDispatcher`。

b) 断言迁移——每个用例的「`caught.await() is CancellationException`」改为「收集到的最后一个事件是 `AgentEvent.Canceled`」。以第一条为例：

```kotlin
    @Test
    fun `cancel interrupts in-flight run and the collector sees the cancellation`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val events = mutableListOf<AgentEvent>()

        val done = CompletableDeferred<Unit>()
        val job = launch {
            agent.run(AgentQuery.text("hi")).collect { events += it }
            done.complete(Unit)
        }
        llm.started.await()
        agent.cancel()
        job.join()

        // 终局是 Canceled 事件（不是取消异常，也不是静默零事件）。
        assertTrue(events.last() is AgentEvent.Canceled)
        // 打断的回合没有其他终态事件。
        assertTrue(events.none { it is AgentEvent.Final })
        assertTrue(events.none { it is AgentEvent.Failed })
        // 收集方协程本身未被取消。
        assertFalse(job.isCancelled)
        done.await()
    }
```

同型迁移以下用例（模式一致：删 `caught` Deferred、collect 正常收尾、断言 `events.last() is AgentEvent.Canceled` 或对 reason 断言 `(events.last() as AgentEvent.Canceled).cause.message` 含原因文本）：
- `cancel reason surfaces to the collector of an in-flight run`
- `cancel interrupts in-flight streaming run`
- `agent remains usable after a cancelled round`
- `joining the cancel handle makes the next round startable`

c) **删除以下 4 个用例**（spec §11.2，冷流兜底消失）：

```kotlin
    fun `cancel before the round starts abandons the round`(...)
    fun `cancel reason is carried by the late collection of an abandoned round`(...)
    fun `the abandoned round does not destroy the next round's mailbox`(...)
    fun `late collection of an abandoned round must not steal the running round's cancel slot`(...)
    fun `cancel releases the slot of a round that was never collected`(...)
    fun `the handle of an abandoned round is already completed`(...)
    fun `a rejected repeated collection must not release the running round's slot`(...)
```

（注：`cancel releases the slot of a round that was never collected` 与 `the handle of an abandoned round is already completed` 属「未收集轮次」家族——启动即执行后不存在「从未收集的轮次」，run 即执行。改为保留但重写：**删除**，其语义由新用例 `run without collection still executes and retires the slot` 覆盖。）

d) **重写/保留以下用例**（行为在新架构下语义变化）：
- `cancelling the collector ends the round with a cancellation and releases the slot` → 改名 `cancelling the collector only cancels the subscription`，断言改为：`job.cancel()` 后 collect 以 CancellationException 结束（收集方自身语义），但回合照常完成——重新订阅（对同一 flow 再 collect）或检查 agent 可直接开新回合（`agent.run("again").toList()` 得 Final）——**不再断言槽位由收集方释放**。注意：回合在 agent scope 全速跑完，槽位由执行体 finally 归还，随后新 run 必成功。
- `second cancel must not free the slot while the cancelled round is still unwinding` → 保留改名 `second cancel while unwinding does not free the slot early`：两次 cancel 后立刻 run 仍应撞 ISE（旧轮在 SlowUnwindLlm 的 300ms 收尾里）。该用例在新架构下仍成立——执行体收尾在 agent scope，`join()` 前槽位仍占。断言不变。
- `an exception thrown by the collector must not skip the round's release` → 保留，断言不变（消费方异常只终止该订阅）。

e) **新增用例**（spec §11.3 前 7 条，加在文件末尾）：

```kotlin
    // ===== 架构 2 新语义 =====

    /** run 后不 collect，回合照常执行、槽位照常归还。 */
    @Test
    fun `run without collection still executes and retires the slot`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = agentOf(provider)

        agent.run(AgentQuery.text("hi")) // 不 collect
        advanceUntilIdle()

        assertEquals(1, provider.recordedRequests.size, "run 即执行，无需订阅")
        // 终局已入回放：迟收集拿到 Initial → Final 完整序列。
        val events = agent.run(AgentQuery.text("hi again")).toList()
        assertEquals(1, events.filterIsInstance<AgentEvent.Final>().size)
    }

    /** 取消订阅不终止回合（孤儿回合）——回合仍完成，重新收集可取到完整历史与终局。 */
    @Test
    fun `unsubscribing does not terminate the orphan round`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val flow = agent.run(AgentQuery.text("hi"))
        val job = launch { runCatching { flow.collect { } } }
        llm.started.await()
        job.cancel() // 只取消订阅
        llm.release() // 释放 gate，回合全速跑完

        val events = mutableListOf<AgentEvent>()
        flow.collect { events += it } // 重新订阅：回放全历史 + 终局
        assertTrue(events.first() is AgentEvent.Initial)
        assertTrue(events.last() is AgentEvent.Final)
        job.join()
    }

    /** 同一条公共流第二次 collect：完整历史 + 终局，不重复执行。 */
    @Test
    fun `collecting the same flow twice replays history without re-execution`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = listOf(
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            )
        )
        val agent = agentOf(provider)
        val flow = agent.run(AgentQuery.text("hi"))

        val first = flow.toList()
        advanceUntilIdle()
        val second = flow.toList()

        assertEquals(first, second)
        assertEquals(1, provider.recordedRequests.size, "不得重复执行")
    }

    /** 两个订阅者各自收到完整序列 —— 广播不分流。 */
    @Test
    fun `two subscribers each receive the complete sequence`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val flow = agent.run(AgentQuery.text("hi"))
        val a = mutableListOf<AgentEvent>()
        val b = mutableListOf<AgentEvent>()
        val subA = launch { flow.collect { a += it } }
        llm.started.await()
        val subB = launch { flow.collect { b += it } } // 中途订阅
        llm.release()

        subA.join(); subB.join()
        assertEquals(a, b, "两个订阅者序列同构（b 为回放段 + live 段）")
        assertTrue(a.first() is AgentEvent.Initial)
        assertTrue(a.last() is AgentEvent.Final)
    }

    /** 迟到订阅必见 Initial —— 回放从回合第一个事件开始。 */
    @Test
    fun `late subscriber sees Initial first`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val flow = agent.run(AgentQuery.text("hi"))
        val early = launch { flow.collect { } }
        llm.started.await()
        llm.release()
        early.join() // 回合已终局

        val events = mutableListOf<AgentEvent>()
        flow.collect { events += it }
        assertTrue(events.first() is AgentEvent.Initial, "终局后订阅仍从 Initial 开始")
        assertTrue(events.last() is AgentEvent.Final)
    }

    /** steer()==false 后立刻 run 必定成功（顺序收集零交错）。 */
    @Test
    fun `run immediately after steer returns false always starts`() = runTest {
        val provider = FakeLlmProvider(
            nonStreamResponses = List(2) {
                ChatResponse(ChatMessage.Assistant(content = "ok"), finishReason = FinishReason.Stop)
            }
        )
        val agent = agentOf(provider)
        agent.run(AgentQuery.text("one")).toList() // 终局后 steer 必 false
        assertFalse(agent.steer(AgentQuery.text("late")))
        assertEquals(1, agent.run(AgentQuery.text("two")).toList().filterIsInstance<AgentEvent.Final>().size)
    }

    /** 终局唯一：取消与 Final 竞态下每个订阅者恰好看到一个终局事件。 */
    @Test
    fun `each subscriber sees exactly one terminal event`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val flow = agent.run(AgentQuery.text("hi"))
        val a = mutableListOf<AgentEvent>()
        val b = mutableListOf<AgentEvent>()
        val subA = launch { flow.collect { a += it } }
        val subB = launch { flow.collect { b += it } }
        llm.started.await()
        agent.cancel()
        subA.join(); subB.join()

        assertEquals(1, a.count { it.isTerminal })
        assertEquals(1, b.count { it.isTerminal })
    }
```

（`GatedLlm` 需补一个 `release()` 方法——原实现 gate 是 private。在 `GatedLlm` 内加 `fun release() = gate.complete(Unit)`。）

- [ ] **Step 9: SteerableTest 适配**

a) 12 处 `ReActAgent(...)` 构造点补 `dispatcher = StandardTestDispatcher(testScheduler)`（文件已 `@OptIn(ExperimentalCoroutinesApi::class)`，补 import `kotlinx.coroutines.test.StandardTestDispatcher`）。

b) `checkpoint 1 / checkpoint 2 / multiple steers / concurrent run / contract1 / contract3 / run call eagerly occupies slot` 等用例用 `backgroundScope.launch { agent.run(...).collect { } }` + `advanceUntilIdle()` 编排——**机制不变**（run 启动即执行，执行体在注入的 testScheduler 上跑，advanceUntilIdle 仍排干）。逐个跑测确认。

c) `final emission resumed on another thread does not fail the run`（runBlocking + RotatingDispatcher）：collect 挂起换线程的回归语义不变，保留原样（该用例走真实线程，不注入 test dispatcher——构造点不补参数，保持 `Dispatchers.Default` 默认）。

d) `steer racing with the final handover reports no active round`（runBlocking）：保留原样（真实线程编排）。

- [ ] **Step 10: 其余测试文件适配（机械补参）**

`ReActAgentTest.kt`（23 处）、`AgentHookTest.kt`（17 处）、`AgentResultExtensionsTest.kt`（1 处）、`ReActAgentMultimodalTest.kt`（1 处）：所有 `ReActAgent(...)` 构造参数列表末尾补 `dispatcher = StandardTestDispatcher(testScheduler)`；相应文件补 import。`CancelableTest.agentOf` 已在 Step 8 处理。

**补充说明（执行者按此判断）**：补参只对「runTest 内构造且依赖虚拟时间」的用例必需；对 runBlocking 真实线程用例（如 SteerableTest 的两个）**不加**。拿不准时先全加（runTest 场景），跑测后对 runBlocking 用例若变慢/挂起则撤掉该处。

- [ ] **Step 11: 跑 core 全量测试**

Run: `./gradlew :agent:core:test`
Expected: PASS。若 `runTest` 报 `UncompletedCoroutinesError`：检查该用例的 agent 构造是否漏补 dispatcher（执行体跑在真实 Default 池上）。若时序断言 flaky：确认 advanceUntilIdle 编排在 backgroundScope 中的 collect 是否被过早取消。

- [ ] **Step 12: 全量测试确认下游未破**

Run: `./gradlew :agent:config:test :agent:subagent:test`
Expected: PASS（config/subagent 经 spawn/awaitResult 路径，行为兼容——取消路径走 Canceled 事件 + awaitResult 抛出，对既有「catch 取消」的调用方同型）。

- [ ] **Step 13: Commit**

```bash
git add agent/core
git commit -m "refactor(agent): 回合执行体迁至 agent 私有 scope 并广播事件"
```

---

### Task 3: ReActAgent 实现 Shutdownable

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt`
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/CancelableTest.kt`（补 shutdown 用例）

**Interfaces:**
- Consumes: Task 2 的 scope / runTurn。
- Produces: `ReActAgent : Agent.Shutdownable`；`shutdown()` 幂等、之后 run 抛 ISE、活跃回合收 `Canceled("Agent is shut down")`。

- [ ] **Step 1: 实现 shutdown**

`ReActAgent` 接口列表加 `Agent.Shutdownable`。字段区（scope 之后）加：

```kotlin
    /** 关闭信号，兼作关闭状态：isCompleted 即"已关闭"，complete 原子且幂等。 */
    private val shutdownSignal = CompletableDeferred<Unit>()
```

`runTurn` 首行（CAS 之前）加：

```kotlin
        check(!shutdownSignal.isCompleted) { "Agent is shut down" }
```

方法区加：

```kotlin
    /**
     * 永久关闭 Agent —— 终态操作。取消 [scope]：活跃回合以 NonCancellable 发完
     * [AgentEvent.Canceled]（"Agent is shut down"）后退场，agent 私有执行域随之终止。
     * 幂等：重复调用无副作用。之后 [run] 抛 [IllegalStateException]。
     */
    override fun shutdown() {
        if (!shutdownSignal.complete(Unit)) return
        scope.cancel(CancellationException("Agent is shut down"))
    }
```

import 补 `kotlinx.coroutines.CompletableDeferred`、`kotlinx.coroutines.cancel`（scope.cancel 扩展）。

`scope.cancel(cause)` 会让执行体在挂起点抛出携带该 cause 的 CancellationException → 外壳 catch → emit `Canceled(cause)` → 消费方区分 shutdown 与用户打断。

- [ ] **Step 2: 补测试（spec §11.3 第 8 条）**

`CancelableTest.kt` 末尾加：

```kotlin
    /** shutdown 后：活跃回合收 Canceled("Agent is shut down")，run 抛 ISE，幂等。 */
    @Test
    fun `shutdown cancels the in-flight round with a shutdown-cause event and rejects further runs`() = runTest {
        val llm = GatedLlm()
        val agent = agentOf(llm)
        val events = mutableListOf<AgentEvent>()
        val job = launch { agent.run(AgentQuery.text("hi")).collect { events += it } }
        llm.started.await()

        agent.shutdown()
        agent.shutdown() // 幂等
        job.join()

        val last = events.last()
        assertTrue(last is AgentEvent.Canceled, "实际终局: ${events.map { it::class.simpleName }}")
        assertTrue("Agent is shut down" in (last as AgentEvent.Canceled).cause.message.orEmpty())
        val ex = assertFailsWith<IllegalStateException> { agent.run(AgentQuery.text("again")) }
        assertTrue(ex.message!!.contains("Agent is shut down"))
    }
```

- [ ] **Step 3: 跑 core 测试**

Run: `./gradlew :agent:core:test`
Expected: PASS。

- [ ] **Step 4: Commit**

```bash
git add agent/core
git commit -m "feat(agent): ReActAgent 实现 Shutdownable"
```

---

### Task 4: 移除冷流兜底与重复收集防护残留

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/Agent.kt`（KDoc 残留清理）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/AgentTurn.kt`（KDoc 残留清理）
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/ReActAgent.kt`（KDoc 残留清理）
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/SteerableTest.kt`（如残留引用）

**Interfaces:** 无新接口。本任务是清扫提交：Task 2 已删代码，此处删尽 KDoc 中对冷流语义的引用（「只允许收集一次」「未收集须 cancel 释放」「RepeatedCollectionException」「迟收集」「onCompletion 归还槽位」等表述）。

- [ ] **Step 1: 全仓 grep 残留**

```bash
grep -rn "RepeatedCollection\|TurnFlow\|Attach\.\|只允许收集一次\|迟收集\|未收集" --include=*.kt agent/ team/ demos/ gateway/ | grep -v "/build/"
```

Expected: 仅 KDoc 文案残留（代码引用应已在 Task 2 清零）。逐条改写为架构 2 表述：
- 「只允许收集一次」→「广播回放，可多次收集」
- 「未收集须 cancel 释放占用」→「run 即执行，无未收集轮次」
- 「收集以 CancellationException 结束」→「以 Canceled 事件收尾」
- `Agent.Cancelable.cancel` KDoc 中「打断后想立刻重开，要等 [cancel] 返回的句柄：打断只投递取消，旧轮的收尾（槽位归还、信箱关闭）在收集方侧进行」改为「旧轮的收尾在 agent 私有 scope 侧进行」。

- [ ] **Step 2: 跑全量测试**

Run: `./gradlew test`
Expected: PASS（本提交纯文案 + 已绿代码）。

- [ ] **Step 3: Commit**

```bash
git add -A
git commit -m "refactor(agent): 移除冷流兜底与重复收集防护"
```

---

### Task 5: SpawnedAgent / BossAgent 的关闭与取消契约迁移

**Files:**
- Modify: `agent/config/src/main/kotlin/io/github/yeyi/agent/config/AgentDefinitionExtensions.kt`
- Modify: `agent/config/src/test/kotlin/io/github/yeyi/agent/config/AgentScopeTest.kt`（8 个 SpawnedAgent 用例迁移）
- Modify: `team/src/main/kotlin/io/github/yeyi/agent/team/BossAgent.kt`
- Modify: `team/src/main/kotlin/io/github/yeyi/agent/team/CancelTaskTool.kt`（核对是否引用取消异常语义）

**Interfaces:**
- Consumes: `AgentEvent.Canceled`（Task 1）、`ReActAgent.shutdown()`（Task 3）。
- Produces: `SpawnedAgent.shutdown()` 走 `delegate.shutdown()`（MCP 时序修复）；BossAgent 关闭级联内层 agent；BossAgent 轮次按事件判取消。

- [ ] **Step 1: 改 SpawnedAgent.shutdown**

`AgentDefinitionExtensions.kt` 的 `shutdown()`（第 105-112 行）替换为：

```kotlin
    /** 永久关闭 Agent 并回收资源（MCP 子进程等）。幂等，可重复调用。 */
    override fun shutdown() {
        if (!shutdownSignal.complete(Unit)) return
        // delegate.shutdown()：scope.cancel() 让活跃回合以 NonCancellable 发完
        // Canceled 后退场 —— 先等执行域终止、再拆 MCP，杜绝「在途工具调用拿到
        // 已死 client」的竞态（cancel 只投递、收尾异步，立即 unregisterAll 会踩进窗口）。
        delegate.shutdown()
        mcpRegistry?.unregisterAll()
    }
```

同时改类 KDoc（第 50-55 行）中「终止进行中的 [run] flow（收集以 [CancellationException] 结束）」为「进行中回合以 [AgentEvent.Canceled] 收尾」。

`run` 方法内注释（第 69-77 行）中「shutdown 后迟收集的终止由 delegate 自身的 cancel-before-collect 保证：shutdown() 里的 delegate.cancel() 同步置 canceled 标志 → 迟收集以 CancellationException 收尾、执行体从不启动，无需在此再做 collect 时守卫」整段替换为：

```kotlin
        // 直接透传 delegate 的流：delegate.run() 启动即执行（eager 占据活跃槽位），
        // run 返回后尚未订阅的间隙里 steer() 不得误判空闲（否则调用方会按 KDoc
        // 模式触发并发 fallback run）。shutdown 后的拒绝由 delegate 的 shutdownSignal
        // 检查承担。
```

（`run`/`runStream`/`steer`/`cancel` 的 `checkShutdown()` 守卫全部保留。）

- [ ] **Step 2: 改 BossAgent.runUserRound**

`BossAgent.kt:228-248` 的 catch 分支改为事件分支（collect 内 when 补 Canceled）：

```kotlin
    private suspend fun runUserRound(userRound: UserRound) {
        try {
            innerAgent.runStream(AgentQuery.text(userRound.input))
                .collect { e ->
                    if (e is AgentEvent.Canceled) {
                        // 轮次被取消（shutdown）：以取消异常关闭 channel ——
                        // 收集方据此知道这一轮被终止，而非"截断但无终态"的静默收尾。
                        throw e.cause
                    }
                    userRound.channel.send(e)
                }
        } catch (cause: CancellationException) {
            // scope 取消（shutdown 级联）路径：channel 带 cause 关闭，
            // 收集端迭代器抛出该 cause —— 与事件路径同型。
            userRound.channel.close(cause)
            throw cause
        } finally {
            userRound.channel.close()
            shutdownHelper.remove(userRound)
            withContext(NonCancellable) { scheduler.roundFinished() }
        }
    }
```

（`e is AgentEvent.Canceled` 时直接 `throw e.cause`：走到外层 catch 是 CancellationException 路径，channel 带取消原因关闭，调用方对「取消」的呈现不变。）

- [ ] **Step 3: 改 BossAgent.runResultRound**

`BossAgent.kt:250-265` 的 collect 内补 Canceled 分支（不转发到 report 热流、调度器照常唤醒）：

```kotlin
    private suspend fun runResultRound(result: String) {
        try {
            innerAgent.runStream(AgentQuery.text(result, label = REPORT_LABEL)).collect { e ->
                // Canceled 不转发到 report 热流 —— 单轮取消不关闭热流，
                // 调度器在 finally 照常回到 WAITING。
                if (e !is AgentEvent.Canceled) (report as MutableSharedFlow).emit(e)
            }
        } finally {
            withContext(NonCancellable) { scheduler.roundFinished() }
        }
    }
```

- [ ] **Step 4: 改 BossAgent.shutdown**

`BossAgent.kt:190-194` 替换为：

```kotlin
    public override fun shutdown() {
        shutdownHelper.release()
        // 内层 agent 的执行域必须显式终止：架构 1 靠 scope.cancel() 的结构化并发
        // 隐式级联杀死 inner 执行体；架构 2 下 inner 回合跑在自己的私有 scope，
        // 该级联失效 —— 不显式 shutdown，boss 关闭后 inner 仍会烧 token 到自然终局。
        (innerAgent as? Agent.Shutdownable)?.shutdown()
        scope.cancel()
        onShutdown()
    }
```

import 补 `io.github.yeyi.agent.Agent`（已存在 `io.github.yeyi.agent.Agent` import，确认即可）。

- [ ] **Step 5: 迁移 AgentScopeTest 中的 SpawnedAgent 用例**

`AgentScopeTest.kt:389-584` 有 10 个 SpawnedAgent 用例，全部按架构 1 断言（收集以 `CancellationException` 收尾）。**拉起 Task 2 后有多少条会红，必须先跑一遍确认**（`./gradlew :agent:config:test`），再逐条迁移：

| 用例（行号） | 新契约下的处理 |
|---|---|
| `spawnedAgent is shutdownable and shutdown is terminal and idempotent`（389） | 保留；`run` 抛 ISE 的断言不变（SpawnedAgent 自身 `checkShutdown`）。 |
| `spawnedAgent shutdown releases mcp registry`（400） | 保留（幂等调用 `unregisterAll` 不抛即可）。 |
| `spawnedAgent shutdown terminates in-flight run flow`（417） | **改**：删 `caught` Deferred 与 try/catch，收集进 `events` 列表；断言 `events.last() is AgentEvent.Canceled` 且 `(events.last() as AgentEvent.Canceled).cause.message == "Agent is shut down"`。 |
| `spawnedAgent cancel interrupts in-flight run and stays usable`（444） | **改**：同上，断言 `events.last() is AgentEvent.Canceled`；`completed`/`job.isCancelled == false` 断言保留；随后 `spawned.shutdown()` → `run` 抛 ISE 不变。 |
| `spawnedAgent run forwards delegate events to collector`（477） | 保留（`toList()` 在终局后正常返回，事件数 2 即 Initial + Final，与架构 2 中继层「终局即完成」一致）。 |
| `spawnedAgent run collected after shutdown does not start delegate`（495） | **改/删**：启动即执行——`run()` 返回时 delegate 已开跑，「shutdown 后再收集不启动」不再成立。新断言：`spawned.run(...)` 后 `spawned.shutdown()`，再 `flow.toList()` 应正常返回（不抛）且 `events.last() is AgentEvent.Canceled`（回放出来的终局），`llm.calls == 1`（只跑一次）。用例名改为 `spawnedAgent run collected after shutdown replays canceled terminal`。 |
| `spawnedAgent runStream terminates in-flight stream flow`（513） | **改**：同 417，断言终局是 `Canceled`。 |
| `spawnedAgent runStream collected after shutdown does not start delegate`（539） | **改**：同 495（`llm.calls == 1` + 回放终局为 Canceled），用例名同步改。 |
| `spawnedAgent runStream and steer after shutdown throw`（557） | 保留（入口 `checkShutdown` 不变）。 |
| `spawnedAgent run without collect still occupies delegate slot`（571） | **改**：`StubLlm` 立即返回，架构 2 下执行体在后台 scope 异步跑，回合可能在 `steer` 前终局还槽 → `steer` 返回 false 而 flaky。改用 `GatedLlm(started, gate)`：`run()` 不 collect → `started.await()` → `assertTrue(steer(...))` → `gate.complete(Unit)` 收尾。

⚠ **执行者注意**：`runTest` 里 delegate 的执行体跑在 `Dispatchers.Default`（`as ReActAgent` 强制构造时无 dispatcher 注入口），与测试协程不在同一调度器。对 417/444/513/571 这类用 `started.await()` / gate 做真实挂起点同步的用例无碍；不要在这几个用例里依赖 `runTest` 的虚拟调度。

- [ ] **Step 6: 跑 team + config 测试**

Run: `./gradlew :team:test :agent:config:test`
Expected: PASS。重点关注：`BossAgentTest` 的 `shutdown cancels scope`；Pasture 集成用例（Beast 路径在 Task 6 才改，本提交 Pasture 的 collect 对 `Canceled` 会落 `else` 分支——`PastureTest` 现有取消用例走 `node.job.cancel()` 的收集协程取消路径，不产生 `Canceled` 事件；但若 BossAgent 关闭 → inner shutdown → Pasture 收到 `Canceled` 且暂时被当作 progress 事件——从 Task 6 起才落正确分支，本提交若有用例破，先读用例确认路径再修）。

- [ ] **Step 7: Commit**

```bash
git add team/src/main/kotlin/io/github/yeyi/agent/team/BossAgent.kt agent/config/src/main/kotlin/io/github/yeyi/agent/config/AgentDefinitionExtensions.kt agent/config/src/test
git commit -m "fix(team): BossAgent 消费 Canceled 事件并在关闭时终止内层 agent"
```

---

### Task 6: Beast 声明为可关闭的一次性 agent 并处理 Pasture 的 Canceled 终局

**Files:**
- Modify: `team/src/main/kotlin/io/github/yeyi/agent/team/Beast.kt`
- Modify: `team/src/main/kotlin/io/github/yeyi/agent/team/Pasture.kt`

**Interfaces:**
- Consumes: `Agent.Shutdownable`（Task 3 起 ReActAgent 实现）。
- Produces: `Beast : Agent, Agent.Shutdownable`（by inner，不提供 cancel）；`Pasture.dispatch` 的 `finally { beast.shutdown() }`。

- [ ] **Step 1: 重写 Beast.kt**

```kotlin
package io.github.yeyi.agent.team

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentPersona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.skill.skills
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.subagent.subagents
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.LazyToolRegistry
import io.github.yeyi.agent.tool.lazy_loading.lazyTools
import io.github.yeyi.agent.toolset.ToolsetRegistry
import io.github.yeyi.agent.toolset.toolsets
import kotlinx.coroutines.flow.Flow

/**
 * 专精 Worker Agent（内部抽象）——一次性 agent：每次 dispatch 新建，任务终局（含取消）即作废。
 *
 * 与 [BossAgent] 正交：Boss 负责编排，Beast 负责执行单个任务。实现上按 selection 组装独立的
 * 内部 ReActAgent 并委托（[run] 纯透传，不附加隐藏行为——保持 Agent 契约「取消订阅不终止回合」）。
 *
 * 生命周期钉死在订阅处：[Pasture.dispatch] 在收集结束的 finally 中调用 [shutdown]
 * （覆盖完成/异常/取消三条路径）。**不提供 [Agent.Cancelable]** —— Beast 的用途就是
 * 一回合消亡，整体作废即全部语义。运行中的协程是 GC root：不 shutdown，
 * 在途回合会在无观察者的情况下继续烧 token 到自然终局。
 */
internal interface Beast : Agent, Agent.Shutdownable

internal class Ox internal constructor(
    private val llmProvider: LlmProvider,
    private val persona: AgentPersona,
    private val toolRegistry: ToolRegistry?,
    private val lazyToolRegistry: LazyToolRegistry?,
    private val toolsetRegistry: ToolsetRegistry?,
    private val skillRegistry: SkillRegistry?,
    private val subagentRegistry: SubagentRegistry?,
    private val maxIterations: Int,
    private val maxRounds: Int,
) : Beast {
    private val inner: Agent = agent {
        persona(this@Ox.persona)
        llmProvider(llmProvider)
        memory(InMemoryMemory(), maxRounds)
        toolRegistry?.let { tools(it) }
        lazyToolRegistry?.let { lazyTools(it) }
        toolsetRegistry?.let { toolsets(it) }
        skillRegistry?.let { skills(it) }
        subagentRegistry?.let { subagents(it) }
        maxIterations(maxIterations)
    }

    override fun run(query: AgentQuery): Flow<AgentEvent> = inner.run(query)
    override fun shutdown() = (inner as? Agent.Shutdownable)?.shutdown() ?: Unit
}

internal class Horse internal constructor(
    private val llmProvider: LlmProvider,
    private val persona: AgentPersona,
    private val toolRegistry: ToolRegistry? = null,
    private val lazyToolRegistry: LazyToolRegistry? = null,
    private val toolsetRegistry: ToolsetRegistry? = null,
    private val maxIterations: Int,
    private val maxRounds: Int,
) : Beast {
    private val inner: Agent = agent {
        persona(this@Horse.persona)
        llmProvider(llmProvider)
        memory(InMemoryMemory(), maxRounds)
        toolRegistry?.let { tools(it) }
        lazyToolRegistry?.let { lazyTools(it) }
        toolsetRegistry?.let { toolsets(it) }
        maxIterations(maxIterations)
    }

    override fun run(query: AgentQuery): Flow<AgentEvent> = inner.run(query)
    override fun shutdown() = (inner as? Agent.Shutdownable)?.shutdown() ?: Unit
}
```

注意：`agent { }` 返回 `Agent`（静态类型），ReActAgent 是否实现 Shutdownable 对外不可见——用 `as?` 探测（ReActAgent 已实现，恒命中；`as?` 防 internal 类型直接依赖）。`import io.github.yeyi.agent.AgentQuery` 需补回（原文件未显式 import？核对——原文件有 `AgentQuery` 用法且同包不涉及，实际原文件无该 import，是同包。核对后按编译器提示补）。

- [ ] **Step 2: 改 Pasture.dispatch —— finally 关闭 beast + Canceled 分支**

`Pasture.kt` 的 `dispatch`（第 147-194 行）中 `scope.launch { ... }` 块改为：

```kotlin
        val job = scope.launch {
            try {
                var failed: AgentEvent.Failed? = null
                beast.run(AgentQuery.text(userInput)).collect { event ->
                    when (event) {
                        is AgentEvent.Final -> dagLock.withLock { dag[taskId]?.result = event }
                        is AgentEvent.Failed -> failed = event
                        // 取消终局：标 CANCELED（而非落进 else 被标 DONE——下游会拿到空结果）。
                        is AgentEvent.Canceled -> handleTerminal(taskId, event.cause)
                        else -> bulletinBoard.progressEvent(TaskUpdate(taskId, event))
                    }
                }
                handleTerminal(taskId, failed?.cause)
            } catch (t: Throwable) {
                handleTerminal(taskId, t)
                if (t is CancellationException) throw t
            } finally {
                // Beast 是一次性 agent：collect 结束（终局完成/订阅被取消/异常）即作废。
                // 「取消收集 job」由此恢复为「终止回合」的级联语义——
                // handleCancellation 的 node.job?.cancel() 掐掉订阅，本 finally 杀在途回合。
                beast.shutdown()
            }
        }
```

`handleCancellation` 与 `DagNode.job` **保持原样**（spec §10 明确不改）。

注意：`Canceled` 分支调用 `handleTerminal(taskId, event.cause)` 后，collect 正常走完（transformWhile 完成）→ 再执行 `handleTerminal(taskId, failed?.cause)`（failed 为 null → 标 DONE）——**handleTerminal 有终态幂等守卫**（DONE/FAILED/CANCELED 直接 return），先到的 CANCELED 生效，后到的 DONE 被忽略，不会翻状态。顺序安全。

- [ ] **Step 3: team 内 runTest 用例补 dispatcher（机械改动）**

Task 2 后，`ReActAgent` 的执行体跑在私有 scope（默认 `Dispatchers.Default`）。team 测试经 `Ox`/`Horse` → `agent { }` 间接构造，无法传 dispatcher（Beast 构造器签名不由本计划扩展——它属于 team 的内部装配细节，加参数会外扩到 BeastAssembler/测试夹具）。

**先跑一遍 `./gradlew :team:test` 观察实况**。按假设，`runBlocking` 用例（`PastureTest`、`PastureDagTest`、`BossAgentTest`、DAG 集成）不受影响——真实线程池本就与架构 2 同型。`runTest` 用例（`BeastTest`、`BossAgentBuilderTest`、`CancelTaskToolTest`、`PublishTaskTool*Test`、`BulletinBoardTest`）若因真实池出现 `UncompletedCoroutinesError` 或 flaky，按以下最小改动处理 `BeastTest`（唯一在 runTest 里直接跑 beast 的套件）：

思路：Beast 经 public `agent { }` 构造、无法注入 dispatcher，故 `runTest` 内用 `advanceUntilIdle()` 兜住异步执行体；`runBlocking` 用例保持原样。

即：**不修改 Beast 构造签名**，而是在 `BeastTest` 的 runTest 用例中改 `ox.run(...).collect { }` 为：

```kotlin
        val events = mutableListOf<AgentEvent>()
        ox.run(AgentQuery.text("do it")).collect { events.add(it) }
        advanceUntilIdle()
```

（collect 会等到 transformWhile 完成——终局事件到达即返回；`advanceUntilIdle` 随后排干执行体 finally，保证 `runTest` 结束前无未完成协程。若某用例本就会等终局，advanceUntilIdle 只是双保险。）

若 `BossAgentBuilderTest` / `CancelTaskToolTest` 等仍因真实池报错，先确认该用例是否真的构造了 ReActAgent（多数只测 builder/工具，不跑回合）；只有确实跑回合的用例才需要上述处理。

- [ ] **Step 4: 补 SpawnedAgent shutdown 时序测试（spec §11.3 第 9 条，落 agent:config）**

`agent/config/src/test/kotlin/io/github/yeyi/agent/config/` 下新增 `SpawnedAgentShutdownTest.kt`（`ReActAgent` 与 `SpawnedAgent` 同类放在 config 模块，shutdown 语义的测试归它）：

```kotlin
package io.github.yeyi.agent.config

import io.github.yeyi.agent.Agent
import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.AgentQuery
import io.github.yeyi.agent.llm.ChatMessage
import io.github.yeyi.agent.llm.ChatRequest
import io.github.yeyi.agent.llm.ChatResponse
import io.github.yeyi.agent.llm.ChatResponseEvent
import io.github.yeyi.agent.llm.FinishReason
import io.github.yeyi.agent.llm.LlmProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class SpawnedAgentShutdownTest {

    /** 首次 chat() 挂起 —— 构造「回合进行中」的时序。 */
    private class GatedLlm : LlmProvider {
        override val name: String = "gated"
        val started = CompletableDeferred<Unit>()
        private val gate = CompletableDeferred<Unit>()

        override suspend fun chat(request: ChatRequest): ChatResponse {
            started.complete(Unit)
            gate.await()
            return ChatResponse(ChatMessage.Assistant(content = "done"), finishReason = FinishReason.Stop)
        }

        override fun chatStream(request: ChatRequest): Flow<ChatResponseEvent> = flow {
            started.complete(Unit)
            gate.await()
            emit(ChatResponseEvent.ContentDelta("done"))
            emit(ChatResponseEvent.Done(usage = null, finishReason = FinishReason.Stop))
        }
    }

    @Test
    fun `spawned shutdown cancels the in-flight round and rejects further runs`() = runTest {
        val llm = GatedLlm()
        val definition = AgentDefinition(name = "t", provider = llm)
        val agent: SpawnedAgent = definition.spawn(AgentScope(memory = io.github.yeyi.agent.memory.InMemoryMemory()))

        val events = mutableListOf<AgentEvent>()
        val job = launch { agent.run(AgentQuery.text("hi")).collect { events += it } }
        llm.started.await()

        agent.shutdown()
        agent.shutdown() // 幂等
        job.join()

        val last = events.last()
        assertTrue(last is AgentEvent.Canceled, "实际终局: ${events.map { it::class.simpleName }}")
        assertTrue("Agent is shut down" in (last as AgentEvent.Canceled).cause.message.orEmpty())
        assertFailsWith<IllegalStateException> { agent.run(AgentQuery.text("again")) }
        assertFailsWith<IllegalStateException> { agent.steer(AgentQuery.text("late")) }
    }
}
```

**执行者注意**：`AgentDefinition(memory?)` 的 `provider` 字段是声明式 `AgentDefinition.Provider.Anthropic(...)` 一类的**配置描述**，不是 `LlmProvider` 实例（`AgentScopeTest` 第 81-87 行是现成范例）；LLM 实例经 `AgentScope.resolver()` 解析注入。`AgentScope(memory = InMemoryMemory())` 的用法见 `AgentScopeTest:128-132`。给 GatedLlm 接到 resolver 上的方式：照抄 `AgentScopeTest` 中替换 provider 的夹具写法（该文件里已有把自定 `LlmProvider` 注入 resolve 结果的先例；若无，则在 `AgentScopeTest` 里对应的 `resolver()` 覆写点接 GatedLlm）。测试意图固定：**shutdown 后活跃回合收 Canceled、随后 run/steer 抛 ISE、二次 shutdown 无副作用**。若 `AgentScope` 的 resolver 注入路径比预期复杂、无法在不改生产代码的前提下接 GatedLlm，则降级为在 `agent:core` 的 `SpawnedAgent` 所在模块测试（`SpawnedAgent` 在 `agent:config`，故备选是给 `SpawnedAgent` 加 internal 构造夹具——不推荐）。优先按现有夹具解决。

MCP 时序（`unregisterAll` 在回合退场之后）由 `delegate.shutdown()` 的同步性保证（`scope.cancel()` 同步标记取消，`unregisterAll` 的移除动作在下一行执行），代码顺序即契约，不另造 MCP 夹具（`McpRegistry` 非 open，无法子类化拦截）。

- [ ] **Step 5: 跑 team + config 测试**

Run: `./gradlew :team:test :agent:config:test`
Expected: PASS。重点用例：
- `PastureTest` 的 `cancel of running long task emits TaskUpdate(Failed(CancellationException))`：架构 2 下链路变为 `node.job.cancel() → collect 抛 CancellationException → catch → handleTerminal(taskId, t)（CANCELED）→ finally → beast.shutdown()`。断言「TaskUpdate 事件是 Failed 且 cause is CancellationException」——handleTerminal 对非 null throwable 产 `AgentEvent.Failed(throwable)`，cause 是 CancellationException，断言仍成立。
- `BeastTest` 各用例：`run(...).collect { }` 后（transformWhile 完成）新行为「finally 里 beast.shutdown()」发生在 Pasture 的 dispatch——**BeastTest 直接调 beast.run，不经 Pasture**，不受影响，仍绿。
- `BeastTest` 中 `Ox run cancels cleanly when scope cancels` 与 `Horse run emits Final...`：不变。

- [ ] **Step 6: Commit**

```bash
git add team/src/main/kotlin/io/github/yeyi/agent/team/Beast.kt team/src/main/kotlin/io/github/yeyi/agent/team/Pasture.kt team/src/test agent/config/src/test
git commit -m "fix(team): Beast 声明为可关闭的一次性 agent 并处理 Pasture 的 Canceled 终局"
```

---

### Task 7: demos 消费端改为纯事件判定取消

**Files:**
- Modify: `demos/agent/src/main/kotlin/io/github/yeyi/agent/demo/agent/vm/ChatViewModel.kt`
- Modify: `demos/agent/src/main/kotlin/io/github/yeyi/agent/demo/agent/vm/SessionViewModel.kt`
- Modify: `demos/team/src/main/kotlin/io/github/yeyi/agent/demo/team/vm/BossViewModel.kt`
- Modify: `demos/realtime/src/main/kotlin/io/github/yeyi/agent/demo/realtime/s2s/BossDelegation.kt`

**Interfaces:**
- Consumes: `AgentEvent.Canceled`、`Agent.Shutdownable`。
- 验证口径：demos 是 Android/compose 模块，本提交以**编译通过**为准（`compileDebugKotlin`），行为验证交给用户手动跑 demo。

- [ ] **Step 1: ChatViewModel —— Canceled 分支 + clearMessages 双动作**

`sendUserInput` 的 catch 块（第 130-133 行）删除（取消不再是异常）；`handleEvent` 的 when 补分支：

```kotlin
            is AgentEvent.Canceled -> {
                // 终局三选一里的第三种：回合被取消（用户打断 / 清空消息 / 关闭页面）——
                // 不是运行失败，渲染打断气泡。
                _messages.update {
                    it + UiMessage.Assistant("（已打断）", id = nextUiId())
                }
                _liveBubble.value = null
            }
```

`clearMessages`（第 100-108 行）改为：

```kotlin
    fun clearMessages() {
        runJob?.cancel() // 先掐订阅：防 shutdown 后迟到的 Canceled 把打断气泡写进刚清空的列表
        runJob = null
        _messages.value = emptyList()
        _liveBubble.value = null
        inProgressByCallId.clear()
        memory = io.github.yeyi.agent.memory.InMemoryMemory()
        agent = agentFactory(memory)
        // 旧 agent 永不再用（memory 已换）——语义是 agent 重开而非停一轮，
        // 用 shutdown 作废（杀掉在途回合），而非只取消订阅。
        (oldAgent as? Agent.Shutdownable)?.shutdown()
    }
```

其中 `oldAgent` 在重建前捕获：

```kotlin
    fun clearMessages() {
        runJob?.cancel()
        runJob = null
        val oldAgent = agent
        _messages.value = emptyList()
        _liveBubble.value = null
        inProgressByCallId.clear()
        memory = io.github.yeyi.agent.memory.InMemoryMemory()
        agent = agentFactory(memory)
        (oldAgent as? Agent.Shutdownable)?.shutdown()
    }
```

同时删 `sendUserInput` 中 `catch (e: CancellationException)` 分支与 import（若无他用）；`catch (t: Throwable)` 保留（Failed 之外的兜底）。

- [ ] **Step 2: SessionViewModel —— agent 单例化**

`sendMessageInternal` 中 `val agent = DemoAgentFactory.create(session.memory, hookPipeline)`（第 160 行）删除；字段区加：

```kotlin
    // 会话场景 agent 语义上就是长驻的：与 ChatViewModel 的单实例对齐，
    // 不再逐条消息新建（每次新建会在架构 2 下遗留仍持有在途回合的私有 scope）。
    private var agent: Agent? = null
```

`sendMessageInternal` 用：

```kotlin
                val agent = agent ?: DemoAgentFactory.create(session.memory, hookPipeline).also { agent = it }
```

import 补 `io.github.yeyi.agent.Agent`。when 分支补：

```kotlin
                        is io.github.yeyi.agent.AgentEvent.Canceled -> {
                            _uiState.value = _uiState.value.copy(
                                error = "已打断",
                                liveBubble = null,
                                isToolExecutionPending = false
                            )
                        }
```

`selectSession`（第 86-108 行）末尾补 agent 作废（切换会话 = 换 memory）：

```kotlin
                val oldAgent = agent
                agent = null
                (oldAgent as? Agent.Shutdownable)?.shutdown()
```

`deleteSession` 中删除当前会话的分支同样补（`currentSession` 置空时作废 agent）。

- [ ] **Step 3: BossViewModel —— Canceled 分支**

`onSend` 的 catch 块（第 238-243 行）删除；collect 内事件处理统一走 `handleAgentEvent`——查 `handleAgentEvent` 的 when，补：

```kotlin
            is AgentEvent.Canceled -> {
                // 终局三选一里的第三种：轮次被取消（session 重置 / boss 关闭）——
                // 不是运行失败，只把 loading 归位，不渲染错误气泡。
                _isLoading.value = false
            }
```

（`resetSession` 已调 `_boss.value?.shutdown()`，经 Task 5 的 BossAgent.shutdown 补丁后恢复完整级联语义，无需改动。）

- [ ] **Step 4: BossDelegation —— Canceled 分支**

`run` 方法（第 40-66 行）中删除 `catch (e: CancellationException)`；when 补：

```kotlin
                    is AgentEvent.Canceled ->
                        runEvents.emit(Failure("已取消"))
```

（原 catch 注释语义照搬：回报一条失败让 TTS 有话可说，而不是静默无回执。）

- [ ] **Step 5: 编译三个 demo 模块**

Run: `./gradlew :demos:agent:compileDebugKotlin :demos:team:compileDebugKotlin :demos:realtime:compileDebugKotlin`
Expected: BUILD SUCCESSFUL。

- [ ] **Step 6: Commit**

```bash
git add demos
git commit -m "refactor(demos): 聊天、团队与 S2S 消费端改为纯事件判定取消"
```

---

### Task 8: 删除 DESIGN.md 旧事实源

**Files:**
- Delete: `agent/core/DESIGN.md`

**Interfaces:** 无。spec §12 提交 9 的遗留项：设计事实源收敛到 `docs/superpowers/specs/2026-11-02-agent-execution-architecture-v2-design.md`，仓库内不再有第二份架构文档。

- [ ] **Step 1: 确认无引用**

```bash
grep -rn "DESIGN.md" --include=*.kt --include=*.md --include=*.kts . | grep -v "/build/" | grep -v "docs/superpowers"
```

Expected: 无代码/构建引用（README 等如有提及一并修正）。

- [ ] **Step 2: 删除并提交**

```bash
rm agent/core/DESIGN.md
git add -A
git commit -m "docs(agent): 删除架构 1 旧设计文档,事实源收敛到 spec"
```

- [ ] **Step 3: 全量验收**

Run: `./gradlew test`
Expected: PASS（对应 spec §13 验收 1）。

再核对 spec §13 验收 2/3：

```bash
grep -rn "TurnFlow\|RepeatedCollectionException\|Attach" --include=*.kt agent/ team/ demos/ gateway/ | grep -v "/build/"
grep -rn "catch (e: CancellationException)\|catch (cause: CancellationException)" --include=*.kt demos/ | grep -v "/build/"
```

Expected: 第一条零命中；第二条 demos 下零命中（team 内层 catch 保留是 scope 取消路径，属协议内）。

---

## Self-Review 结论

- **Spec 覆盖**：spec §5-§10 的所有变更面均映射到 Task 1-8；§11.3 新增用例分布在 Task 2（前 7 条）/ Task 3（shutdown 2 条）/ Task 6（Pasture 链路 2 条——以「跑既有 PastureTest 取消用例」覆盖，链路行为由 handleTerminal 幂等守卫保证，不新造集成夹具）；§11.2 删除映射 Task 2 Step 8c；§13 验收映射 Task 8 Step 3。
- **测试适配面（spec §11 未列，Task 5 Step 5 补齐）**：`AgentScopeTest.kt:389-584` 的 10 个 SpawnedAgent 用例全部按架构 1 断言，属 spec 未显式列举的隐式迁移面——4 个保留、4 个改断言为 `events.last() is AgentEvent.Canceled`、2 个「shutdown 后收集不启动」改为「`llm.calls == 1` + 回放终局 Canceled」。同理 `SteerableTest`/`ReActAgentTest`/`AgentHookTest` 的构造点补 `dispatcher` 参数属机械适配（Task 2 Step 4 已列）。
- **占位符**：已扫描 `TODO|TBD|placeholder`，仅命中本条说明自身；Task 2 Step 4 的 cancel 代码块为终态（turn.job 方案），无待补段落。
- **类型一致性**：`AgentTurn.events` / `turn.job` / `shutdownSignal` / `dispatcher` 构造参数、`Beast.shutdown()` by inner、`Pasture.dispatch` finally 调用，前后任务引用一致。
