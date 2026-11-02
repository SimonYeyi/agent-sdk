# Agent 执行架构演进设计（架构 2）

## 1. 概述

本文档定义 Agent 执行架构从「架构 1」演进到「架构 2」的完整设计。核心变化是把 `run` 从「冷流 + 收集方驱动执行 + 取消走异常」演进为「启动执行 + 广播共享 + 取消走领域事件」。

| | 架构 1（现状） | 架构 2（目标） |
|---|---|---|
| `run()` 语义 | 返回冷流，建轮即占槽，不收集不执行 | 非 suspend 启动回合，立即执行 |
| 执行体归属 | 收集方 scope（结构化并发绑定收集方） | agent 私有 scope（生命周期归 agent） |
| 多订阅 | 不支持，重复收集=重复执行 | 广播共享，一次执行多点观察，不分流 |
| 未收集轮次 | 占槽不还（需 cancel 兜底） | 不存在——run 即启动 |
| 取消呈现 | collect 外抛 CancellationException | 独立 `Canceled` 领域事件 |
| 消费心智 | 事件 + 异常双路径 | 纯事件，无 try/catch |

## 2. 设计目标

1. **消费端统一心智**：一切结果都是事件（`Final` / `Failed` / `Canceled`），消费方无异常分支。
2. **取消是业务结果而非控制流**：取消由独立能力接口 `Agent.Cancelable` 产生，对应独立领域事件，与失败区分。
3. **取消可记录可转发**：作为协议一员，可持久化、日志、多订阅者共享。
4. **执行生命周期归 agent**：订阅只是观察，订阅者不驱动执行、取消订阅不终止执行。
5. **调用方低侵入**：`run` / `runStream` 接口签名不变（均返回 `Flow<AgentEvent>`），调用方主体代码无需改动；消费端唯一改动是把「collect 外 try-catch 取消异常」改为「when 判断 `Canceled` 事件」。`awaitResult` 作为兼容既有心智的**异常形态壳**保持抛 `CancellationException`（见第 12 节），不在纯事件适配范围内，其消费方无需改动。

## 3. run 机制

```kotlin
public interface Agent {
    public fun run(query: AgentQuery): Flow<AgentEvent>   // 非 suspend：启动回合，返回广播流
}
```

决策点：

- **非 suspend**。「suspend 等到执行完成」等价于 `run(q).toList()`，不需要挂起形态；「suspend 等到执行开始」收益仅是消除微秒竞态，被 `replay=1` 兜住。非 suspend 保持 API 形状不变、无挂起成本、`run`/订阅/`cancel`/`shutdown` 相互解耦。
- **`runStream` 与 `run` 统一机制**：同走广播流实现（agent 私有 scope / `replay=1` / `Canceled` 事件），差异仅在 LLM 流式与否——流式模式多 `TextDelta` 增量事件，事件序列是 run 的超集，`run` 序列是 `runStream` 序列的子集。
- **启动即执行**。「用户既然 run 就代表需要」，不提供「建轮不执行」能力。占槽/还槽由执行体生命周期自然管理，`Attach.Canceled` 迟收集分支与 `RepeatedCollectionException` 兜底消失。
- **公共流完成语义**：`run` 返回的公共流在终局事件后**完成**（`collect` 随回合结束正常返回）。广播流本身无完成态，故实现为「广播执行 + 每订阅者一层中继」：中继转发事件，遇到 `Final`/`Failed`/`Canceled` 后完成自身。保持架构 1「collect 随 Final 返回」的既有语义，调用方主体流程无需改写；迟订阅者经 `replay=1` 拿到缓存的终局后，中继发出并立即完成。取消订阅只取消中继（订阅），不终止执行（孤儿回合）。
- **单活跃回合保留**（防记忆冲突与事件交错）：CAS 不变，并发 `run` 仍拒绝。

## 4. 执行体与 scope 模型

- agent 持有私有 `CoroutineScope(SupervisorJob + Dispatcher)`，每次 `run` 在 scope 内 `launch` 一个独立回合 job。
- 回合 job 结构：

```kotlin
scope.launch {
    try { 回合执行; emit(Final) }
    catch (e: CancellationException) { withContext(NonCancellable) { emit(Canceled(e)) } }
    catch (e: Throwable) { emit(Failed(e)) }
    finally { 还槽; 收尾 }
}
```

- **孤儿回合是设计语义**：订阅不驱动执行、取消订阅不终止执行，显式 `cancel()` 才终止。
- **线程迁移约束变更**：执行体从「调用方协程」迁到 agent scope，打破旧硬约束「run() 保持冷流驱动、执行在调用方协程、不迁移线程」。这是本演进对旧约束的正式替代。
- **同线程 steer 死锁消除**：架构 1 中执行体绑收集方协程，`steer()` 在主线程同步 `acquireUninterruptibly()` 取闸时，若闸正被同在主线程上的 finalize 持有（`emit(Final)` 挂起点窗口），会阻塞唯一能放闸的线程 → 死锁。架构 2 下执行体迁到 agent scope，闸持有者不再依赖调用线程恢复，死锁消失。**实施约束：死锁的本质是「持闸者恢复必须依赖被阻塞的线程」，故 agent scope 的 dispatcher 须保证并发度 > 1 或与调用线程不相交——最稳是专用 dispatcher（独立线程或池）；不得配到并发度 = 1 且与调用方共享的单线程（如 `Dispatchers.Main`）上。多线程池即使与调用线程重叠也无碍（持闸者可在池内其他线程恢复），仅需避免「整池被并发 steer 占满」的边缘情形。**

## 5. 事件协议

事件序列不变：`Initial → [...增量/工具事件...] → Final | Failed | Canceled`。

终局三类型：

```kotlin
public data class Final(public val result: AgentResult) : AgentEvent
public data class Failed(public val cause: Throwable) : AgentEvent
public data class Canceled(public val cause: CancellationException) : AgentEvent   // 新增
```

决策点：**独立 `Canceled` 而非嵌套 Failed**。

- 取消≠失败，默认行为不同：失败合理动作是重试/告警，取消是停止/提示打断（用户主动打断，盲重试往往错）。嵌套 Failed 后不判类型的消费方会走错误的失败默认路径。
- 判 `cause is CancellationException` 是隐式嗅探，与项目一贯的「显式字段而非隐式判断」原则冲突（先例：`ToolCallExplanation.allAsync`）。
- 独立能力接口（`Agent.Cancelable`）产生独立领域事件，保持相对独立。
- 载荷用 `CancellationException` 而非裸字符串：可被 `awaitResult` 重新抛出而不被协程框架误判为失败；携带 message（原因文本）与堆栈；实现零成本（执行体 catch 到的本就是取消异常）。

`cancel(cause: String?)` 签名保持字符串入参（调用方 `agent.cancel("用户打断")` 最友好），内部构造 `CancellationException(cause)` 作为事件载荷。领域区分（shutdown vs 用户打断）由 message 文本承载。

## 6. 取消语义

- **in-flight 打断**：`Cancelable.cancel(cause)` 取消回合 job → 执行体 catch 后在 `NonCancellable` 中 `emit(Canceled(e))` → 广播流完成；返回收尾句柄 Job。
- **取消不消耗 agent 可用性**：回合后照常执行后续回合。
- **shutdown**：`Shutdownable.shutdown` 取消 scope → 活跃回合发 `Canceled("Agent is shut down")` → scope 终止。
- **收集方自身取消 = 取消订阅，不是取消回合**：回合继续在 agent 私有 scope 执行（孤儿回合语义，见第 4 节），终局事件照常 emit 进 `replay=1` 缓存，事后重新订阅仍可取到终局。collect 调用点抛出的 `CancellationException` 是收集方自身协程的取消信号（标准协程语义，任何挂起点同理），与回合终局无关，不在 core「终局全事件化」承诺内，无须 try/catch。
- **双终局排除**：`emit(Final)` 之后无挂起点，shutdown/cancel 的取消不会造成同一订阅者 `Final`+`Canceled` 双收（取消只会在挂起点生效）。cancel 与 Final 投递竞态的极端情况下，跨订阅者序列可能轻微分叉（部分收到 `Final`、部分收到 `Canceled`）——罕见且对 awaitResult/UI 均无害，接受为契约。

## 7. 订阅与回放策略

决策点：

- **广播不分流**：`MutableSharedFlow` 是广播语义，每个订阅者独立、完整收到每一个事件，互不竞争。`Channel` 才是竞争消费（一个事件一个消费者）。多订阅者不会导致任何一方丢 delta 或序列不完整。
- **不可回放（不完整回放）**：订阅者只应收到「订阅时刻之后」的事件。完整回放与流式 delta 根本矛盾（delta 是增量，回放无意义）。
- **`replay = 1` 是边界防丢，不是历史回放**：
  - 首帧竞态：回合第一个事件永远是 `Initial`（状态事件，幂等），`replay=1` 兜住「立即订阅仍错过首事件」的微秒窗口，重放 `Initial` 无害。
  - 终局迟到：终局是最后一个事件，`replay=1` 必然缓存，迟到订阅者也能拿到 `Final` / `Canceled`（`awaitResult` 类「只要终局」消费的最大受益者）。
  - 途中订阅副作用：若订阅落在 delta 序列中间，会多收最近一个事件；若为 `TextDelta` 会重复渲染一小段增量——罕见用法，接受为契约，不做按类型选择性 replay（复杂度不值）。
- **订阅语义定稿**：订阅时刻起的事件 + replay 边界事件；迟到订阅者错过中间 delta 是契约而非缺陷。

## 8. 背压契约

决策点：

- **缓冲内零丢失**：`MutableSharedFlow` 缓冲（`replay + extraBufferCapacity`）满时 `emit` 挂起等待消费，**阻塞而非丢弃**。
- **责任边界**：
  - 核心承诺：缓冲范围内事件不丢、广播序列完整、缓冲满时阻塞而非丢弃。
  - 消费方责任：自己的消费效率。UI 慢是 UI 的问题（快速转发、渲染队列），核心不为它兜底。
  - 核心不零缓冲：给 `replay=1 + 有界 extraBufferCapacity`，让消费方有追赶空间。
- **无人订阅**：无缓冲需求，`emit` 立即返回，执行体全速跑，事件只进 `replay=1`。
- **慢订阅者拉慢全体（共享缓冲耦合）**：广播流缓冲是所有订阅者共享的，`emit` 阻塞等待最慢者消费，快订阅者也连带延迟。核心不做订阅级隔离——这是「阻塞不丢弃」的必然代价，接受为契约（与架构 1 相比仍是改善：有追赶空间、无人订阅全速）。
- **对比现状**：架构 1 的 `channelFlow.buffer(Channel.RENDEZVOUS)` 是零缓冲同步握手，UI 慢同样拖慢执行体且更严重（无追赶空间）。架构 2 是改善：缓冲内异步追赶、无人订阅全速执行。
- **实现约束**：不使用丢弃策略（破坏 UI 完整性）。

## 9. 消费端适配

| 消费端 | 架构 1 行为 | 架构 2 适配 |
|---|---|---|
| `awaitResult` | filter Final/Failed + throw Failed.cause | filter 三终局；`Canceled` → `throw cause`（载荷是 CancellationException，抛出不误判失败）；`Failed` → throw cause 不变 |
| BossAgent | collect + catch 取消异常 | when 判 `Canceled` 分支（唤醒续轮等） |
| UI | collect 外围 try/catch | 纯事件，when 分支渲染打断气泡 |
| 打断当前回合（会话继续） | `Job.cancel` 收集协程 = 取消回合（执行体绑收集方 scope，结构化并发连带终止） | 拆分：`Job.cancel` 仅取消订阅（孤儿回合继续执行、槽位照占）；终止回合须 `Cancelable.cancel(cause)`，订阅自行收尾 |
| 终结 agent（会话/应用结束） | `Shutdownable.shutdown()` 终止执行、agent 作废 | `shutdown()` 语义不变（scope 取消、活跃回合发 `Canceled`、幂等）；`Job.cancel` 不再能终止执行，只能取消订阅 |
| SpawnedAgent | shutdown → delegate.cancel | 透传，语义不变 |
| gateway app | `run().awaitResult()` | 不变（awaitResult 内部适配） |

## 10. 测试迁移

- `CancelableTest`：断言从 `assertFailsWith<CancellationException>` / `caught is CancellationException` 改为「流以 `Canceled` 事件收尾」（in-flight 与取消前未订阅两条路径）。
- 删除迟收集 / `RepeatedCollectionException` 相关测试（冷流兜底消失），新增多订阅（一次执行多点观察、不分流）与孤儿回合（订阅不驱动执行、取消订阅不终止执行）测试。
- 事件序列/时序测试语义不变，只改终局断言面。

## 11. 变更面清单与实施顺序

变更面：

1. `AgentEvent`：新增 `Canceled(cause: CancellationException)`
2. `Agent.kt`：`Agent.Cancelable` KDoc 更新（取消结果 = Canceled 事件）、顶层能力清单、`run`/`runStream` KDoc 轮次语义改写（启动即执行、可多订阅、孤儿回合、公共流终局即完成）
3. `AgentTurn` / `ReActAgent`：执行体归属 agent scope、取消发事件、公共流中继层、删冷流兜底（迟收集 / RepeatedCollection）
4. `AgentDefinitionExtensions.kt`（SpawnedAgent）：shutdown 语义
5. `BossAgent`：消费端 `Canceled` 分支
6. UI 层：纯事件消费
7. `AgentExtensions.kt`（awaitResult）：三终局适配
8. `CancelableTest`：断言迁移

实施顺序：核心协议（事件 + scope + 取消）→ SpawnedAgent → 消费端（awaitResult / BossAgent / UI）→ 测试迁移。

## 12. 决策收口

- **`runStream` 同步迁移到广播机制**：与 `run` 统一，同走广播流实现（agent 私有 scope / `replay=1` / `Canceled` 事件），差异仅在 LLM 流式与否。`run` 与 `runStream` 共用同一套 scope / 取消 / replay 机制。
- **`awaitResult` 对 `Canceled` 抛 `CancellationException`**：与 `Failed` 分支处理方式完全一致——`Final` → 返回 `result`；`Failed` → `throw cause`；`Canceled` → `throw cause`（载荷本就是 `CancellationException`，抛出不误判失败、不重新包装）。保持「取消即异常」返回形态，衔接既有调用方心智。
