# Team 模块 DAG 任务依赖

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 让 `PublishTaskTool` 支持 per-task `depends_on` 声明 DAG 依赖,`Pasture` 接管全局 DAG 调度,自动派发后置任务。Boss LLM 的一次 publish_task 调用关联一个 `roundId`(对应用户的一轮对话);同 roundId 内所有 task 完成时 BossAgent 触发续轮,带 round summary 作为 LLM input。一次性声明多步计划,根治异步场景下「Boss 续轮忘记发后置任务」的可靠性问题,同时屏蔽中间步骤噪音。

**Background:** 当前 `publish_task` 只支持并发独立 task。DAG 链式 `a → b` 必须拆成:round 1 `publish_task([a])` → Final → task a 完成 → handlePending 续轮 → round 2 Boss LLM 调用 `publish_task([b])`。两个问题:(1) 续轮依赖 LLM 决策,Boss 可能「忘了」原多步计划,不发后置任务;(2) 即便发了,Boss 也会被中间步骤结果噪音干扰。

**Architecture:**

- **类型分层**:`PublishEvent`(LLM 端发布:TaskAssignments + Cancellation)走 `publishEvents` 流;`ProgressEvent`(Pasture 端进度:TaskUpdate)走 `progressEvents` 流。统一流 `events: SharedFlow<BulletinEvent>` 供 BossAgent / UI 订阅全量。`TaskAssignment` 退化为普通 data class(非 event),只描述 task 本身
- **新事件 `TaskAssignments(tasks)`**: 一次 `publish_task` 调用 → 一个 `TaskAssignments` 事件,整批原子 publish。**事件本身不带 roundId** —— roundId 是 BossAgent 内部视角(『这次 publish 属于哪个用户轮次』),由 BossAgent 收到 `TaskAssignments` 时用自己 `run()` 时生成的 `currentRoundId` 自行关联
- **`knownTaskIds` 由 `PublishTaskTool` 内部维护**: 类内 `MutableSet<String>` + `Mutex`,publish 成功后登记新 task_id。**BulletinBoard 不维护任何业务 set**(Board 是事件总线基础设施,不该知道 `TaskAssignments.tasks[*].taskId` 的字段语义 —— 让 Board 维护业务 set 等于让公交车帮别人记快递)
- **`PublishTaskTool` = 透传层**: schema 校验 + task_id 唯一性 + dependsOn 引用校验 + intra-batch 环检测 → 透传 publish `TaskAssignments(tasks)`。**不计算叶子、不标 upstream、不织 DAG,不需要 roundIdProvider / 外部 knownTaskIds 回调**
- **`Pasture` = 全局 DAG 调度器**: 维护 `Map<taskId, DagNode>`,收到 `TaskAssignments` 整批注册,按 dependsOn 拓扑 dispatch。每个 task terminal 时 emit `TaskUpdate(taskId, event)`(不带 roundId,**Pasture 不感知 round 概念**)。cascade 失败时链式推进
- **`BossAgent` 维护 round 状态**: `currentRoundId`(每轮 run() 时更新);`TaskState` 加 `roundId` 字段;attach 收到 `TaskAssignments` 时用 `currentRoundId` 关联 task
- **`BossAgent` 续轮触发**: 收到 `TaskUpdate` 时,锁内查同 roundId 的所有 task 是否 terminal → 都 terminal 时触发 `handlePending(roundSummary = formatRoundSummary(roundId))`。`formatRoundSummary` 由 BossAgent 从 `tasks[taskId].events` 聚合(不依赖 Pasture 提供 summary)
- **UI 按 roundId 分组**: UI 不直接订阅 events 推断 roundId —— BossAgent 通过 `continuations` 流(已经实现)驱动通知,UI 也可以独立订阅 `BulletinBoard.events` 全量流,从 `TaskAssignments` 注册顺序 + `TaskUpdate` 拿 task progress。同 roundId 内按 LLM 声明顺序(TaskAssignments 数组顺序)显示,UI 用 `dependsOn` 字段可视化链路拓扑

**关键设计决策:**

1. **类型分层 = 类型名表达语义** —— `PublishEvent` 是 LLM 端发布(用户/Boss LLM 视角),`ProgressEvent` 是 Pasture 端进度(系统视角)。**命名错了类型就不清晰,语义就被误解**。BulletinBoard 内部 `publishEvents: SharedFlow<PublishEvent>` + `progressEvents: SharedFlow<ProgressEvent>` 两个独立流,类型参数直接表达语义
2. **roundId = 用户的一轮对话** —— `run()` 时生成 `currentRoundId`,后续 `publish_task` 调用关联到当前 round(可在同 round 内多次 publish_task 形成多批 task)。跨 round 累积:round 2 的 task 引用 round 1 的 task_id → `PublishTaskTool.knownTaskIds` 已包含,校验通过 → Pasture 调度时 round 1 task 已 DONE → round 2 task 立即 dispatch(无需 round 概念,纯 DAG 调度)
3. **roundId 由 BossAgent 内部管理,事件不带 roundId** —— `PublishTaskTool` 不接收 `roundIdProvider` lambda,roundId 完全在 BossAgent 内部。`Agent.run(input)` 接口不变,`ToolContext` 不扩展,事件总线也不引入新字段。**关键洞察:roundId 是『BossAgent 视角的元数据』,不属于事件本身的业务载荷** —— 把 roundId 放进事件会让事件总线承载过多 BossAgent 视角信息,污染事件语义
3a. **task_id 由程序生成,LLM 只提供 ref** —— LLM 在每次 publish_task 内提供 `ref`(本批唯一的人话短字符串,比如 "lookup"/"summary"),程序生成 UUID 作为 `task_id`。**关键洞察:让 LLM 生成 + 校验全局唯一 id 是不必要的负担** —— (1) 消耗 token;(2) LLM 必须自己维护名字拼写一致性(同批 + 跨次);(3) 校验失败需要重试。**`ref` 是 LLM 端的临时概念**,程序在 `execute()` 内把 `depends_on` 中的 ref 解析成 task_id,LLM 跨次引用时用 task_id(从之前 roundSummary 拿)。下游(Pasture / BossAgent)只看到 `task_id`,完全不知道 ref 的存在。
4. **knownTaskIds 由 PublishTaskTool 内部维护,BulletinBoard 不掺和业务关注** —— `PublishTaskTool` 类内 `private val knownTaskIds: MutableSet<String>` + `Mutex`,publish 成功后登记新 task_id。**BulletinBoard 不维护任何已知 task_id** —— Board 是事件总线基础设施,不该知道 `TaskAssignments.tasks[*].taskId` 的字段语义。让 Board 维护业务 set 等于让公交车帮别人记快递,会污染事件总线的纯粹性,也会让未来新增事件类型时面临『要不要也维护自己的业务 set』的困扰。**权威源在业务侧而非基础设施侧**。
5. **Pasture 完全不感知 roundId** —— `TaskUpdate(taskId, event)` 不带 roundId,Pasture 只管 DAG 调度。`roundId` 字段由 BossAgent 内部维护,存于 `TaskState` 中,从 `attach` handler 里用 `currentRoundId` 关联
6. **roundSummary 由 BossAgent 自己聚合** —— `formatRoundSummary(roundId)` 从 `tasks[taskId].events` 提取每个 task 的 terminal event 格式化。不依赖 Pasture 提供(避免 Pasture 与 BossAgent 视角耦合)
7. **续轮触发唯一 = round 内所有 task terminal** —— `TaskUpdate` 进来只累加 events + 检查 round 完成,只有 round 完成才触发 `handlePending(roundSummary)`。上游 task 完成时不触发续轮(避免多次续轮 + 中间结果噪音)
8. **emit 决策 race-free** —— BulletinBoard 单线程顺序消费,Pasture collect handler 与 dispatch catch 是同一 actor。BossAgent 检查 round 完成时锁内查 `rounds` map,不会被并发 TaskAssignments 干扰
9. **环检测只 intra-batch** —— 跨 batch 引用已发布的 task,数学上不可能成环。PublishTaskTool 只对当前 tasks 数组做 DFS 环检测
10. **cancel cascade = 下游传播 + 单根调用** —— `cancel_task(taskId)` → Pasture 把该 task 标 CANCELED(RUNNING 时 `job.cancel()`,PENDING/READY 时直接标),沿依赖图下游传播(所有 dependsOn 包含此 taskId 的 task,直接或传递)同样标 CANCELED/FAILED。每个 cascade fail 的 task 都 emit `TaskUpdate`,BossAgent 检查所属 round 是否都 terminal → 是则触发续轮带失败 summary

    **cascade 精确语义**:
    - **只向下传播**:cancelled task 的下游(被它 depends_on 的所有 task)会被取消;**上游不动**(被取消任务自己的依赖继续跑 —— 它们可能还被其他分支需要)
    - **状态无关**:cascade 看的是静态依赖图,不检查被取消 task 自身状态;已 done 的 task 不再 cascade(no-op 由 Pasture `runningJobs` 清理逻辑兜底)
    - **单根调用**:同 round 内多个独立 DAG 根,LLM 调多次 `cancel_task` 即可(每次 tool_use 取消一个根),不需要批量数组支持 —— cascade 已经处理下游

    **`CancelTaskTool.kt:description` 字段改写(给 LLM 看)**:
    ```
    cancel_task(task_id: str)

    Cancels a running task. Cancellation propagates to all tasks that depends_on it
    (directly or transitively). Upstream tasks (the cancelled task's own dependencies)
    are NOT affected — they may still be needed by other branches.

    Rules:
    - You only need to cancel the ROOT of a dependency chain. Cascade handles the rest.
    - Cancelling an already-completed task is a safe no-op for that task, but cascade
      still fires for any dependents that are still running.
    - Cascade propagates DOWNSTREAM ONLY. Cancelling B in A→B→C stops B and C; A continues.

    Example:
      Chain: weather (running) → send_email (waiting on weather)
      Call: cancel_task(weather_task_id)
      Result: weather is cancelled, send_email is cascade-cancelled automatically.
      You do NOT need to also call cancel_task(send_email_task_id).
    ```

**Tech Stack:** Kotlin + kotlinx.coroutines + kotlinx.serialization (json)

**Spec 参考:** `docs/superpowers/specs/2026-07-13-team-module-design.md`, `docs/superpowers/specs/2026-07-15-team-boss-and-agent-impl.md`, `docs/superpowers/plans/2026-07-17-team-attach-observe-refactor.md`

---

## 文件改动清单

| 路径 | 改动 |
|---|---|
| `team/src/main/kotlin/io/github/yeyi/agent/team/BulletinBoard.kt` | 新增 `TaskAssignments(tasks)` 扁平 data class(**不带 roundId**);`TaskAssignment` 加 `dependsOn` 字段,从 `PublishEvent` 实现中移除退化为非 event;**Board 本身零改动**(三流派生 + subscriptionCount + publish/progress 方法不变) |
| `team/src/main/kotlin/io/github/yeyi/agent/team/PublishTaskTool.kt` | schema 加 `ref`(LLM 提供,本批唯一) + `depends_on`(可选,引用 ref 本批或 task_id 跨批);execute 改为纯透传 + ref → UUID task_id 解析 + 一次性 publish `TaskAssignments(tasks)`(不带 roundId);**类内**新增 `knownTaskIds: MutableSet<String>` + `knownTaskIdsLock: Mutex` + `internal fun knownTaskIdsSnapshot()`,publish 成功后登记新 task_id;**构造签名零变化**;删除原 UUID 生成(task_id 改为程序在 execute 内生成,LLM 只提供 ref) |
| `team/src/main/kotlin/io/github/yeyi/agent/team/BeastAssembler.kt` | 新文件,根据 `Selection` 列表组装 `Beast`(Horse/Ox) |
| `team/src/main/kotlin/io/github/yeyi/agent/team/Pasture.kt` | 替换 `runningJobs` 派发为 DAG 调度器;observe 改订阅 `publishEvents` 处理 `TaskAssignments` + `Cancellation`;注入 `BeastAssembler` 依赖;每个 task terminal emit `TaskUpdate`;cascade 链式推进;**不**新增 `knownTaskIds()` 暴露(权威源在 `PublishTaskTool` 侧,Pasture 自己用 `dag.keys` 即可) |
| `team/src/main/kotlin/io/github/yeyi/agent/team/BossAgent.kt` | 新增 `currentRoundId` 字段;`TaskState` 加 `roundId` 字段;`run()` 每轮生成 currentRoundId;`attach` 收到 `TaskAssignments` 时用 `currentRoundId`(内部状态)关联 task,**不读 event.roundId**(事件不带);新增 `handleTaskUpdate` 的 round 完成判断 + `formatRoundSummary`;`handlePending` 扩展 `roundSummary` 参数 |
| `team/src/main/kotlin/io/github/yeyi/agent/team/BossAgentBuilder.kt` | **PublishTaskTool 构造完全不变**;**唯一改动**:`baseRole` 第 3 条后追加第 4 条 (DAG 用法摘要) |
| `team/src/main/kotlin/io/github/yeyi/agent/team/CancelTaskTool.kt` | `description` 字段改写(详见设计决策 #10 的 `cancel_task` 工具描述);execute 逻辑不变(已正确处理 PENDING/READY/RUNNING/DONE 分支 + cascade) |
| `team/src/test/kotlin/io/github/yeyi/agent/team/PublishTaskToolDagTest.kt` | 新文件,覆盖 ref 解析 + intra-call ref 唯一性 + dependsOn 引用(ref 本批 + task_id 跨批混用) + 环检测 + 验证 emit 的 `TaskAssignments` 不带 roundId + summary 返回 task_id 给 LLM |
| `team/src/test/kotlin/io/github/yeyi/agent/team/PastureDagTest.kt` | 新文件,覆盖 DAG 调度 + 跨 round 引用 + cascade + TaskUpdate emit + Pasture 内部 dag 状态观察(internal getter) |
| `team/src/test/kotlin/io/github/yeyi/agent/team/BossAgentDagIntegrationTest.kt` | 新文件,覆盖 Boss 只在 round 完成时续轮 + round summary 聚合 + 跨 round 累积 + roundId 由 BossAgent.run() 时生成 |

`CancelTaskTool` 仅 `description` 字段改写(详见设计决策 #10),execute 逻辑不变(已正确处理 PENDING/READY/RUNNING/DONE 分支 + cascade);Beast / Selection / BossAgent 主流程(state machine / decisionLock / runPendingRound / handlePending 现有逻辑)不动,仅扩展 `handlePending` 签名加 `roundSummary` 参数。

---

## Task 1: `BulletinBoard` 事件类型扩展(Board 本身零改动)

**当前 BulletinBoard 类型结构已正确分层**:`BulletinEvent` / `PublishEvent` / `ProgressEvent` 已存在,`publishEvents` / `progressEvents` / `events` 三流已正确派生。**本次 Board 本身零改动 —— BulletinBoard 是纯事件总线,不应背负任何具体业务的元数据状态**(已知 task_id 由 `PublishTaskTool` 自己维护,BulletinBoard 不掺和业务关注)。**只新增事件类型**:

- [ ] `BulletinBoard.kt:20-25` 给 `TaskAssignment` 加 `dependsOn: List<String>` 字段(无默认值),**并让它从 `PublishEvent` 实现中移除**(退化为非 event 的普通 data class):
  ```kotlin
  internal data class TaskAssignment(
      internal val taskId: String,
      internal val selections: List<Selection>,
      internal val task: String,
      internal val context: String?,
      internal val dependsOn: List<String>,
  )
  ```
  KDoc:「单 task 描述,不含任何调度标记(visibility/group/upstream 等)。`taskId` 由 PublishTaskTool 程序生成(UUID,LLM 不提供);`dependsOn` 是 task_id 列表,引用同次 publish 或之前 publish 的 task;空列表 = 无依赖,可立即 dispatch。**本类型已从 `PublishEvent` 实现中移除**(退化为非 event),作为 `TaskAssignments` 事件的载荷元素存在,不再单独发布。**注意:`ref`(LLM 提供的逻辑名)是 PublishTaskTool.execute 内部的临时概念,解析后丢弃,不在此类型出现**。」
- [ ] `BulletinBoard.kt` 在 `PublishEvent` 旁边新增 `TaskAssignments` data class(扁平风格,跟现有 `Cancellation` 保持一致):
  ```kotlin
  internal sealed interface PublishEvent : BulletinEvent

  internal data class TaskAssignments(
      internal val tasks: List<TaskAssignment>,
  ) : PublishEvent
  ```
  KDoc:「LLM 端发布的事件,整批 publish 入口。`tasks` 是本次 publish 的 task 列表。**注意:roundId 不在事件里** —— BossAgent 收到 `TaskAssignments` 时用自己 `run()` 时生成的 `currentRoundId` 关联 task(roundId 是 BossAgent 视角的元数据,不属于事件业务载荷,污染事件语义)。**单数 `TaskAssignment` 已从 `PublishEvent` 实现中移除** —— 整批发布统一通过 `TaskAssignments`。」

**BulletinBoard 本身完全不变**:`Cancellation`(已存在)、`TaskUpdate`(已存在)、sealed interface 结构、`events` / `publishEvents` / `progressEvents` 三流派生机制、`subscriptionCount`、`publishEvent` / `progressEvent` 方法签名 —— 全部已正确,**本次零改动**。

**为什么不把 knownTaskIds 放进 BulletinBoard**:BulletinBoard 是「事件总线」基础设施,只负责转发 + 订阅计数,不应该关心具体事件类型的字段语义(`TaskAssignments.tasks[*].taskId`)。让 BulletinBoard 维护 knownTaskIds 等于让公交车帮别人记快递 —— 事件总线承接过多 BossAgent/PublishTaskTool 视角的业务关注,污染 Board 的纯粹性,也会让未来新增事件类型时面临『要不要也维护自己的业务 set』的困扰。已知 task_id 由 `PublishTaskTool` 自行维护(`internal val knownTaskIds: Set<String>` + 锁),权威源在业务侧而非基础设施侧。

## Task 2: `PublishTaskTool` 透传改造

职责收敛:**只校验 + 透传**。不计算叶子、不标 upstream、不织 DAG。**`task_id` 由程序生成 UUID(LLM 不提供)**,LLM 只提供 `ref`(本批内唯一的 symbolic name,人话短字符串)。`depends_on` 既支持引用 `ref`(本批)也支持引用 `task_id`(跨批,从之前 roundSummary 拿)。程序负责 `ref → task_id` 解析,下游只看到 `task_id`。**不再需要 roundIdProvider / 外部 knownTaskIds 回调** —— roundId 由 BossAgent 内部维护(attach 时自行关联),knownTaskIds 由 PublishTaskTool 内部维护(权威源在发布工具侧,不让 BulletinBoard 背负业务关注)。

- [ ] `PublishTaskTool.kt:94-138` `SCHEMA_JSON` task item 改写 —— 把 `task_id` 替换为 `ref`(LLM 提供):
  ```json
  {
    "type": "object",
    "properties": {
      "ref": {
        "type": "string",
        "description": "Symbolic name you provide, unique within this publish_task call. Use it in depends_on to reference another task in the same call. Cross-call references use the task_id you receive in the previous round's summary."
      },
      "selections": { /* 保持原样 */ },
      "task": { /* 保持原样 */ },
      "context": { /* 保持原样 */ },
      "depends_on": {
        "type": "array",
        "items": { "type": "string" },
        "description": "Optional list of references. Each entry is either a `ref` from this same call or a `task_id` from a previously published task. Empty/missing = no dependencies, dispatched immediately."
      }
    },
    "required": ["ref", "selections", "task"]
  }
  ```
- [ ] `PublishTaskTool.kt:16-19` 构造签名**零变化**(原本就没有 roundIdProvider / knownTaskIds 外部回调)。在类内新增 knownTaskIds 状态字段(权威源在发布工具内部,不外露):
  ```kotlin
  internal class PublishTaskTool internal constructor(
      private val bulletinBoard: BulletinBoard,
      private val capabilitiesByType: Map<String, List<NamedCapability>>,
  ) : Tool {
      // knownTaskIds: 历史 task_id (UUID, 程序生成) —— 校验跨批 task_id 引用 + 登记新 task_id.
      // 不让 BulletinBoard 背负业务关注 (BulletinBoard 是事件总线, 不该知道 TaskAssignments 的字段语义).
      // 单实例 (一个 BossAgent 一个 PublishTaskTool) + 锁保护即可.
      private val knownTaskIds: MutableSet<String> = mutableSetOf()
      private val knownTaskIdsLock: Mutex = Mutex()

      // 测试可见 snapshot —— 同包测试不绕弯, 直接读.
      internal fun knownTaskIdsSnapshot(): Set<String> = knownTaskIdsLock.withLock { knownTaskIds.toSet() }
  ```
  KDoc:「**跨轮追加意图**通过 `task_id` 表达,不通过 ref —— roundSummary 每轮返回 task_id 给 LLM,LLM 在下一轮 `depends_on` 里写 task_id 即可精确引用历史 task。**ref 严格限定为「本批内唯一符号名」**,跨轮没有稳定保证,所以不维护 refHistory(latest-wins 会让同名 ref 跨轮歧义,精确引用必须用 task_id)。」
- [ ] `PublishTaskTool.kt:23-43` `description` 在「Pass an array of independent tasks to run them concurrently」处替换为:
  ```
  Pass an array of tasks. Each task declares a `ref` (your short symbolic name, unique within this call)
  and optionally lists references in `depends_on` to form a DAG. References in `depends_on` rules:
    - Same publish_task call: use `ref` (your symbolic name from another task in this same call).
      This is the ONLY option within one call because task_id is assigned AFTER publish completes.
    - Different publish_task call (cross-round): MUST use `task_id` (UUID). Refs are NOT stable
      across calls — the same ref name may be reused, mapped to different tasks, or absent.
      Always read the prior round's summary to get task_ids, then list them in `depends_on`.
    - Mixing `ref` (same call) and `task_id` (cross-call) in the same `depends_on` array is allowed.
  Tasks without dependencies run concurrently; a task with `depends_on` waits for all referenced
  tasks to finish, then runs with their final results prepended to its context. For a chain A→B,
  put both in one call:
    tasks=[{ref:"lookup",...}, {ref:"summary", depends_on:["lookup"],...}]
  Each publish_task call belongs to the current round — extend a chain across rounds by listing
  earlier task_ids in depends_on. The boss only sees a round summary when all tasks in the round
  complete — intermediate task results are not individually reported.
  ```
- [ ] `PublishTaskTool.kt:49-91` `execute` 重写为「**三 pass 手动解析**」结构 —— 沿用现有 `JsonObject` 字段提取风格,只新增 `ref` / `depends_on` 字段处理 + ref → UUID 解析 + 环检测。**不引入 `@Serializable` DTO、不引入 `Json {}` 配置、不修改 `parametersSchema` 的现有 `$$"$ENUM"` 占位模式,也不引入中间类型**(`TaskAssignment` 自己当占位容器):
  ```kotlin
  override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult {
      val tasksArray = arguments.jsonObject["tasks"] as? JsonArray
          ?: return ToolExecutionResult.error("Missing 'tasks' array")
      if (tasksArray.isEmpty()) return ToolExecutionResult.error("'tasks' must not be empty")

      // === Pass 1: 解析每个 task 到 TaskAssignment (taskId 暂存 ref, dependsOn 暂存原始字符串) ===
      val placeholder = tasksArray.mapIndexed { idx, el ->
          val obj = el.jsonObject
          val ref = obj.str("ref") ?: return ToolExecutionResult.error("Missing 'ref' in task #$idx")
          if (ref.isBlank()) return ToolExecutionResult.error("'ref' must not be empty in task #$idx")
          val task = obj.str("task") ?: return ToolExecutionResult.error("Missing 'task' in task '$ref'")
          val context = obj["context"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
          val selsArr = obj["selections"] as? JsonArray
              ?: return ToolExecutionResult.error("Missing 'selections' in task '$ref'")
          if (selsArr.isEmpty()) return ToolExecutionResult.error("'selections' must not be empty in task '$ref'")
          val selections = selsArr.map { selEl ->
              val selObj = selEl.jsonObject
              val type = selObj.str("type") ?: return ToolExecutionResult.error("Missing 'type' in selection of task '$ref'")
              val name = selObj.str("name") ?: return ToolExecutionResult.error("Missing 'name' in selection of task '$ref'")
              Selection.FACTORIES[type]?.invoke(name)
                  ?: return ToolExecutionResult.error("Unknown selection type '$type' in task '$ref'")
          }
          val deps = (obj["depends_on"] as? JsonArray)?.map { it.jsonPrimitive.content } ?: emptyList()
          // 占位: taskId = ref (Pass 2 会 copy 成 UUID), dependsOn 暂存原始字符串列表 (Pass 2 会 copy 成 task_id 列表)
          TaskAssignment(taskId = ref, selections = selections, task = task, context = context, dependsOn = deps)
      }

      // intra-call ref 唯一性 (LLM 在同批内不能用相同 ref; 此时 taskId 还是 ref,直接 groupBy)
      val dupRefs = placeholder.groupBy { it.taskId }.filterValues { it.size > 1 }.keys
      if (dupRefs.isNotEmpty()) return ToolExecutionResult.error("Duplicate ref in this call: $dupRefs")

      // === Pass 2: 分配 UUID + 解析 depends_on (ref 本批 → UUID, task_id 跨批 → 直接复用 knownTaskIds) ===
      val refToUuid = placeholder.associate { it.taskId to UUID.randomUUID().toString() }
      val existing = knownTaskIdsLock.withLock { knownTaskIds.toSet() }
      val resolved = placeholder.map { t ->
          val resolvedDeps = t.dependsOn.map { dep ->
              refToUuid[dep] ?: dep.takeIf { it in existing }
                  ?: return ToolExecutionResult.error(
                      "Unknown depends_on reference '$dep' in task '${t.taskId}'. " +
                      "Within one publish_task call use `ref`; across calls use `task_id` (UUID) " +
                      "from the previous round's summary. Registered task_ids: ${existing.sorted()}"
                  )
          }
          // copy 终结: taskId 换 UUID, dependsOn 换解析后的 task_id 列表
          t.copy(taskId = refToUuid[t.taskId]!!, dependsOn = resolvedDeps)
      }

      // === Pass 3: intra-call 环检测 + publish + 登记 knownTaskIds ===
      detectIntraCycle(resolved)?.let { return ToolExecutionResult.error("Cycle detected involving task '$it'") }
      bulletinBoard.publishEvent(TaskAssignments(resolved))
      knownTaskIdsLock.withLock { resolved.forEach { knownTaskIds.add(it.taskId) } }

      // === Summary: 返回 task_id 给 LLM 后续轮次引用 ===
      val summary = resolved.map { task ->
          val selStr = task.selections.joinToString("+") { "${it.type}(${it.name})" }
          "- ${task.taskId} → $selStr"
      }
      return ToolExecutionResult("Assigned ${resolved.size} task(s):\n${summary.joinToString("\n")}")
  }
  ```
  KDoc:「**三 pass 设计** —— Pass 1 解析每个 task 到 `TaskAssignment`(taskId 暂存 `ref`、dependsOn 暂存原始字符串);Pass 2 用 `copy(taskId = UUID, dependsOn = resolvedDeps)` 一次性终结;Pass 3 环检测 + publish + 副作用登记。**`TaskAssignment` 自己当占位容器,不引入中间 data class** —— 目标类型在第一阶段就能容纳中间状态,第二阶段用 `copy()` 替换占位字段。沿用现有 JsonObject 手动解析风格,所有失败立即 error 返回,不污染后续状态。」
- [ ] 新增 `private fun JsonObject.str(field: String): String? = this[field]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content` —— 收敛 `?.jsonPrimitive?.content` + JsonNull 判断的重复写法(原代码每个字段都重复写)。**这是现有 JsonObject 操作的 helper 扩展,不是新结构**。
- [ ] 新增 `private fun detectIntraCycle(tasks: List<TaskAssignment>): String?` —— DFS 找环,基于 task_id 依赖图(Pass 2 解析后的 dependsOn 全是 task_id)。
- [ ] 加 import:`kotlinx.coroutines.sync.Mutex` + `kotlinx.coroutines.sync.withLock` (供 `knownTaskIdsLock.withLock { ... }` 用)

  KDoc:「**校验语义** —— ① intra-call `ref` 非空且唯一; ② `selections` 数组非空 + 每个 selection type 在 `Selection.FACTORIES` 中; ③ `depends_on` 每项要么是本批 `ref`(程序解析为 task_id)要么是已注册 `task_id`(在 knownTaskIds 中); ④ intra-call 环检测。**`task_id` 由程序 UUID 生成,LLM 不提供** —— LLM 只提供本批唯一的 `ref`,跨次引用用 task_id(从之前 roundSummary 拿)。所有校验失败的请求整体返回 error,**不**触发 partial publish。校验通过后才 publish + 登记 task_id。」

## Task 3: `BeastAssembler` Beast 组装 + `Pasture` DAG 调度器

将 beast 组装逻辑从 Pasture 分离到独立类 `BeastAssembler`，Pasture 只负责 DAG 调度。

### BeastAssembler

新增 `BeastAssembler.kt`，负责根据 `Selection` 列表组装 `Beast`（Horse 或 Ox）：

- [ ] 新文件 `BeastAssembler.kt`:
  ```kotlin
  internal class BeastAssembler(
      private val llmProvider: LlmProvider,
      private val toolRegistry: ToolRegistry?,
      private val skillRegistry: SkillRegistry?,
      private val subagentRegistry: SubagentRegistry?,
      private val toolsetRegistry: ToolsetRegistry?,
      private val baseRole: String,
      private val maxIterations: Int,
      private val maxRounds: Int,
  ) {
      suspend fun assemble(selections: List<Selection>): Beast {
          return try {
              assembleHorse(selections)
          } catch (_: IllegalStateException) {
              buildOx()
          }
      }
  
      private suspend fun assembleHorse(selections: List<Selection>): Horse {
          if (selections.isEmpty()) error("assembleHorse: selections is empty")
          if (selections.any { it is Selection.Subagent }) {
              error("assembleHorse: subagent not supported")
          }
          
          val skillTexts = mutableListOf<String>()
          val tools = mutableListOf<Tool>()
  
          for (s in selections) {
              when (s) {
                  is Selection.Skill -> {
                      val skill = skillRegistry?.all()?.firstOrNull { it.name == s.name }
                          ?: error("assembleHorse: skill not found: ${s.name}")
                      val text = skill.load()
                      skillTexts += text
                      skillRegistry.allTools().forEach { tool ->
                          val pattern = Regex("\\b" + Regex.escape(tool.name) + "\\b")
                          if (pattern.containsMatchIn(text)) tools += tool
                      }
                  }
                  is Selection.Toolset -> {
                      val toolset = toolsetRegistry?.all()?.firstOrNull { it.name == s.name }
                          ?: error("assembleHorse: toolset not found: ${s.name}")
                      tools += toolset.all()
                  }
                  is Selection.Tool -> {
                      val tool = toolRegistry?.all()?.firstOrNull { it.name == s.name }
                          ?: error("assembleHorse: tool not found: ${s.name}")
                      tools += tool
                  }
                  is Selection.Subagent -> { /* unreachable */
                  }
              }
          }
  
          val persona = Persona(
              buildString {
                  append(baseRole)
                  skillTexts.forEach { append("\n\n").append(it) }
              }
          )
  
          return Horse(llmProvider, persona, tools, maxIterations, maxRounds)
      }
  
      private fun buildOx(): Ox = Ox(
          llmProvider = llmProvider,
          persona = Persona(baseRole),
          toolRegistry = toolRegistry,
          skillRegistry = skillRegistry,
          subagentRegistry = subagentRegistry,
          toolsetRegistry = toolsetRegistry,
          maxIterations = maxIterations,
          maxRounds = maxRounds,
      )
  }
  ```

### Pasture DAG 调度器

替换 `runningJobs` 简单派发为 DAG tracker。`TaskUpdate` 透明 emit 所有 task(包括 upstream)。cascade 链式推进。**完全不感知 roundId**。

- [ ] `Pasture.kt` 构造函数改为注入 `BeastAssembler`:
  ```kotlin
  internal class Pasture(
      private val assembler: BeastAssembler,
      private val scope: CoroutineScope,
  )
  ```
  删掉 `llmProvider` / `toolRegistry` / `skillRegistry` / `subagentRegistry` / `toolsetRegistry` / `maxIterations` / `maxRounds` / `baseRole` 参数。
- [ ] `Pasture.kt:34-35` 删除 `runningJobs: MutableMap<String, Job>` 与 `jobsLock: Mutex`(被 DAG 状态取代)
- [ ] `Pasture.kt` 新增内部状态:
  ```kotlin
  private val dag: MutableMap<String, DagNode> = mutableMapOf()
  private val dagLock: Mutex = Mutex()

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

  private enum class Status { PENDING, READY, RUNNING, DONE, FAILED, CANCELED }
  ```
- [ ] **不需要新增** `internal fun knownTaskIds()` —— knownTaskIds 由 PublishTaskTool 内部维护(权威源在发布工具侧),Pasture 自己用 `dag.keys` 即可,不暴露给外部。**避免『BulletinBoard 不维护业务 set 但 Pasture 维护』的不对称设计** —— 业务 set 应该收敛到唯一所有者(`PublishTaskTool`)
- [ ] `Pasture.observe` 的 collect handler 改订阅 `publishEvents`,处理 `TaskAssignments` + `Cancellation`:
  ```kotlin
  is TaskAssignments -> handleTaskAssignments(event)
  is Cancellation -> handleCancellation(event)
  ```
  (注意:订阅 `publishEvents: SharedFlow<PublishEvent>`,类型安全)
- [ ] 实现 `private suspend fun handleTaskAssignments(group: TaskAssignments)`:
  ```kotlin
  private suspend fun handleTaskAssignments(group: TaskAssignments) {
      // 整批注册到全局 DAG (锁内). 批次只是 publish 边界, 不是调度边界 —— DAG 是全局的.
      // Pasture 完全不感知 roundId, 不存不读.
      val newNodes = dagLock.withLock {
          group.tasks.map { task ->
              val node = DagNode(
                  assignment = task,
                  status = if (task.dependsOn.isEmpty()) Status.READY else Status.PENDING,
              )
              dag[task.taskId] = node
              node
              // 注: 当前批内 task 互引已在 PublishTaskTool 入口校验过, 此处不重复校验.
          }
      }
      // Dispatch READY; 推进 PENDING (跨 batch 引用可能上游已 DONE)
      for (node in newNodes) {
          when (node.status) {
              Status.READY -> dispatch(node)
              Status.PENDING -> tryAdvancePending(node.assignment.taskId)
          }
      }
  }
  ```
- [ ] 实现 `private suspend fun tryAdvancePending(taskId: String)`:
  ```kotlin
  private suspend fun tryAdvancePending(taskId: String) {
      val toDispatch = dagLock.withLock {
          val node = dag[taskId] ?: return
          val upstream = node.assignment.dependsOn.mapNotNull { dag[it] }
          when {
              upstream.any { it.status == Status.FAILED || it.status == Status.CANCELED } -> {
                  val n = dag[taskId]!!
                  dag[taskId] = n.also {
                      it.status = Status.FAILED
                      it.failureMessage = "Upstream task failed"
                  }
                  null
              }
              upstream.all { it.status == Status.DONE } -> {
                  val results = upstream.associate { it.assignment.taskId to (it.result ?: "") }
                  val n = dag[taskId]!!
                  dag[taskId] = n.also {
                      it.status = Status.READY
                      it.upstreamResults.putAll(results)
                  }
                  dag[taskId]!!
              }
              else -> null  // 还有上游 PENDING/RUNNING, 等 cascade 触发
          }
      }
      toDispatch?.let { dispatch(it) }
      if (toDispatch == null && dagLock.withLock { dag[taskId]?.status == Status.FAILED }) {
          cascade(taskId)
      }
  }
  ```
- [ ] 实现 `private suspend fun dispatch(node: DagNode)`:
  ```kotlin
  private suspend fun dispatch(node: DagNode) {
      val taskId = node.assignment.taskId

      // 1) 合并 upstream results 到 context
      val mergedContext = buildString {
          node.upstreamResults.forEach { (id, result) ->
              append("[$id]\n$result\n\n")
          }
          node.assignment.context?.let { append(it) }
      }.takeIf { it.isNotEmpty() }

      val userInput = if (mergedContext == null) node.assignment.task
                      else "$mergedContext\n\n${node.assignment.task}"

      // 2) assemble beast
      val beast: Beast = withContext(Dispatchers.IO) { assembler.assemble(node.assignment.selections) }

      // 3) launch job
      val job = scope.launch {
          try {
              beast.run(userInput) { event ->
                  if (event is AgentEvent.Final) {
                      dagLock.withLock { dag[taskId]?.let { it.result = event.content ?: "" } }
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

      job.invokeOnCompletion { runBlocking { cascade(taskId) } }
  }
  ```
- [ ] 实现 `private suspend fun handleTerminal(taskId: String, isSuccess: Boolean, throwable: Throwable? = null)`:
  ```kotlin
  private suspend fun handleTerminal(taskId: String, isSuccess: Boolean, throwable: Throwable? = null) {
      val node = dagLock.withLock { dag[taskId] } ?: return
      val newStatus = when {
          isSuccess -> Status.DONE
          throwable is CancellationException -> Status.CANCELED
          else -> Status.FAILED
      }
      dagLock.withLock {
          val n = dag[taskId]!!
          dag[taskId] = n.also {
              it.status = newStatus
              if (!isSuccess) it.failureMessage = throwable?.message ?: throwable?.toString() ?: "Unknown failure"
          }
      }

      // emit TaskUpdate (透明, 所有 task 包括 upstream 都 emit, 不带 roundId)
      val event = if (isSuccess) AgentEvent.Final(node.result ?: "")
                  else AgentEvent.Failed(throwable ?: IllegalStateException(node.failureMessage))
      bulletinBoard.progressEvent(TaskUpdate(taskId, event))
      // BossAgent 收到后自己判断所属 round 是否完成 → 触发续轮
  }
  ```
- [ ] 实现 `private suspend fun cascade(completedTaskId: String)`:
  ```kotlin
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
  ```
- [ ] 实现 `private suspend fun handleCancellation(e: Cancellation)`:
  ```kotlin
  private suspend fun handleCancellation(e: Cancellation) {
      val node = dagLock.withLock { dag[e.taskId] } ?: return
      when (node.status) {
          Status.DONE, Status.FAILED, Status.CANCELED -> return  // idempotent
          Status.PENDING, Status.READY -> {
              dagLock.withLock { dag[e.taskId] = node.also { it.status = Status.CANCELED } }
              handleTerminal(e.taskId, isSuccess = false, throwable = CancellationException("task canceled"))
              cascade(e.taskId)
          }
          Status.RUNNING -> node.job?.cancel()
      }
  }
  ```
- [ ] `Pasture` 删掉 `assembleHorse` / `buildOx` / `baseRole` 等 beast 组装相关代码,改用注入的 `BeastAssembler`

## Task 4: `BossAgent` 维护 round 状态 + 续轮触发

`TaskState` 加 `roundId` 字段,`pendingResultEvents` 改为 `Channel<String>` 存 round summary 字符串。`handleTaskUpdate` 检查 round 完成时把 summary 压入 channel,调用普通 `handlePending()` 触发续轮。删掉 COLLECTING 和 `hasActiveTasks()`。

- [ ] `BossAgent.kt` 新增 `currentRoundId` 字段（用于 attach 时关联 task 到当前 round）:
  ```kotlin
  private var currentRoundId: String = ""
  ```
- [ ] `BossAgent.kt` `run()` 每轮生成 `currentRoundId`:
  ```kotlin
  override fun run(input: String): Flow<AgentEvent> {
      if (!scope.isActive) {
          return flow { emit(AgentEvent.Failed(IllegalStateException("Agent is shut down"))) }
      }

      currentRoundId = java.util.UUID.randomUUID().toString()  // 每轮一个新 roundId
      val round = UserRound(input, Channel(Channel.UNLIMITED))
      scope.launch { handlePending(round = round) }
      return flow { for (e in round.channel) emit(e) }
  }
  ```
  KDoc:「每轮生成新的 `currentRoundId`,LLM 响应 `publish_task` 时 attach 收到的 `TaskAssignments` 会关联到这个 roundId。」
- [ ] `BossAgent.kt` `UserRound` 不变（不需要 roundId 字段，roundId 存在 BossAgent 内部状态）
- [ ] `BossAgent.kt` `attach` 改订阅 `events` 统一流(因类型是 `Any`,用 `is` 类型检查)。**关键:收到 `TaskAssignments` 时用 `currentRoundId`(BossAgent 内部状态)关联 task,不是从 `event.roundId` 读 —— `TaskAssignments` 事件本身不带 roundId**:
  ```kotlin
  internal suspend fun attach(bb: BulletinBoard) {
      check(!::bulletinBoard.isInitialized) { "BossAgent.attach() must be called only once" }
      bulletinBoard = bb
      val subscribed = CompletableDeferred<Unit>()
      scope.launch {
          bulletinBoard.events
              .onSubscription { subscribed.complete(Unit) }
              .collect { event ->
                  when (event) {
                      is TaskAssignments -> {
                          // BossAgent 内部 currentRoundId, 自行关联这批 task 到当前 round.
                          // 一次 publish_task 调用可以属于当前 round 的多次调用之一.
                          val roundId = currentRoundId
                          tasksLock.withLock {
                              for (task in event.tasks) {
                                  tasks[task.taskId] = TaskState(task.selections, task.task, roundId, task.dependsOn)
                              }
                          }
                      }
                      is TaskUpdate -> handleTaskUpdate(event)
                      else -> Unit  // Cancellation 不关心
                  }
              }
      }
      subscribed.await()
  }
  ```
  KDoc 更新:「订阅 `events` 统一流。`TaskAssignments` 注册所有 task 到 map + 用 BossAgent 内部 `currentRoundId` 关联(不依赖事件本身带 roundId —— roundId 是 BossAgent 视角的『这次 publish 属于哪个用户轮次』);`TaskUpdate` 累加 events + 检查 round 完成;`Cancellation` 不关心。」
- [ ] `TaskState` data class 加 `roundId: String` + `dependsOn: List<String>` 字段:
  ```kotlin
  internal class TaskState(
      val selections: List<Selection>,
      val task: String,
      val roundId: String,
      val dependsOn: List<String> = emptyList(),
      val events: MutableList<AgentEvent> = mutableListOf(),
  ) {
      val terminal: Boolean
          get() = events.lastOrNull() is AgentEvent.Final || events.lastOrNull() is AgentEvent.Failed
  }
  ```
- [ ] 改造 `handleTaskUpdate`(累加 events + 检查 round 完成 → 把 summary 压入 channel):
  ```kotlin
  private suspend fun handleTaskUpdate(update: TaskUpdate) {
      val roundId: String
      tasksLock.withLock {
          val task = tasks[update.taskId] ?: return  // unknown task_id 忽略
          task.events += update.event
          if (!task.terminal) return  // 非终态, 只更新状态
          roundId = task.roundId
      }
      // 检查 round 内所有 task 是否都 terminal
      val allDone = tasksLock.withLock {
          tasks.values.filter { it.roundId == roundId }.all { it.terminal }
      }
      if (allDone) {
          val summary = formatRoundSummary(roundId) ?: return
          pendingResultEvents.trySend(summary)  // 压入 round summary
          handlePending()  // 触发续轮
      }
  }
  ```
- [ ] 改造 `pendingResultEvents` channel 类型存 round summary 字符串:
  ```kotlin
  private val pendingResultEvents: Channel<String> = Channel(capacity = Channel.UNLIMITED)
  ```
- [ ] 新增 `private fun formatRoundSummary(roundId: String): String?`:
  ```kotlin
  private fun formatRoundSummary(roundId: String): String? = tasksLock.withLock {
      val roundTasks = tasks.entries.filter { it.value.roundId == roundId }
      if (roundTasks.isEmpty()) return@withLock null
      buildString {
          append("Tasks completed:\n")
          for ((taskId, task) in roundTasks) {
              val lastEvent = task.events.lastOrNull()
              val marker = when (lastEvent) {
                  is AgentEvent.Final -> "✓ done"
                  is AgentEvent.Failed -> "✗ failed"
                  else -> "?"
              }
              val detail = lastEvent?.let { ": $it" } ?: ""
              append("- $taskId $marker$detail\n")
          }
      }
  }
  ```
  KDoc:「从 `tasks[taskId].events` 聚合 round 内每个 task 的 terminal event 格式化。BossAgent 拥有完整 task 状态视图,不依赖 Pasture 提供 summary。返回 null 表示 round 内无 task。」
- [ ] 改造 `handlePending` 简化逻辑（删掉 roundSummary 参数、删掉 COLLECTING）:
  ```kotlin
  private suspend fun handlePending(
      round: UserRound? = null,
      postRound: Boolean = false,
  ) {
      decisionLock.withLock {
          // 1) run() 投递: 闲时启动, 忙时挂起到字段
          if (round != null) {
              when {
                  _state.value in setOf(BossState.WAITING, BossState.INPUTTING) -> {
                      _state.value = BossState.RUNNING
                      scope.launch { runPendingRound(round) }
                  }
                  else -> {
                      pendingUserRound?.let { supersedeRound(it) }
                      pendingUserRound = round
                  }
              }
              return@withLock
          }

          // 2) 外部触发撞忙 bail
          if (!postRound && _state.value == BossState.RUNNING) return@withLock

          // 3) postRound 接班 或 外部撞闲: 决策
          val pendingRound = pendingUserRound
          pendingUserRound = null
          val roundSummary = pendingResultEvents.tryReceive().getOrNull()

          when {
              pendingRound != null || roundSummary != null -> {
                  _state.value = BossState.RUNNING
                  scope.launch { runPendingRound(pendingRound, roundSummary) }
              }
              else -> if (_state.value != BossState.WAITING) {
                  _state.value = BossState.WAITING
              }
          }
      }
  }
  ```
- [ ] 改造 `runPendingRound` 接收 `roundSummary: String?`:
  ```kotlin
  private suspend fun runPendingRound(round: UserRound? = null, roundSummary: String? = null) {
      try {
          val merged = drainPendingWith(round?.input, roundSummary)
          if (merged != null) {
              innerAgent.run(merged).collect { e ->
                  if (round == null) continuationsEmitter.emit(e)
                  else round.channel.send(e)
              }
          }
          handlePending(postRound = true)
      } finally {
          round?.channel?.close()
      }
  }
  ```
- [ ] 改造 `drainPendingWith` 只接收 `roundSummary` 字符串（删掉 TaskUpdate 相关逻辑）:
  ```kotlin
  private fun drainPendingWith(input: String?, roundSummary: String?): String? {
      if (input == null && roundSummary == null) return null
      return buildString {
          input?.let { append(it); if (roundSummary != null) append("\n\n") }
          roundSummary?.let { append(it) }
      }
  }
  ```
- [ ] 删掉 `hasActiveTasks()`、`runPendingRoundWithCollecting()`、`formatTaskResults()`、`pendingResultEvents` 原来是 `Channel<TaskUpdate>` 改为 `Channel<String>`

## Task 5: `BossAgentBuilder` 接线

Pasture 构造器改为注入 BeastAssembler，BossAgentBuilder 需先创建 BeastAssembler：

- [ ] `BossAgentBuilder.kt` 先创建 `BeastAssembler` 再创建 `Pasture`:
  ```kotlin
  val assembler = BeastAssembler(
      llmProvider = llm,
      toolRegistry = delegatedToolRegistry0,
      skillRegistry = skillRegistry0,
      subagentRegistry = subagentRegistry0,
      toolsetRegistry = toolsetRegistry0,
      baseRole = "You are a helpful worker. Complete the given task and return the result.",
      maxIterations = maxIterations0,
      maxRounds = maxRounds0,
  )
  val pasture = Pasture(
      assembler = assembler,
      scope = bossScope,
  )
  ```

- [ ] **不需要改**: `BossAgentBuilder.kt:127` 的 `val publishTask = PublishTaskTool(bulletinBoard, capabilitiesByType)` 保持不变
- [ ] **不需要改**: 构造顺序(pasture → buildBoss → observe + attach)保持不变
- [ ] **不需要改**: lambda 注入链路 — 因为本计划刻意消除了对外部 lambda 的依赖
- [ ] `BossAgentBuilder.kt:31-36` `baseRole` 在第 3 条后追加一段 (DAG 用法摘要):
  ```
  4. Chain dependent tasks in one publish_task call: pass multiple tasks with `ref` (your short symbolic name). Later tasks list earlier refs in `depends_on` to form a DAG. To extend a chain across rounds, reference an earlier task_id (from the previous round's summary) in depends_on. The boss only sees a round summary when all tasks in the round complete — intermediate task results are not individually reported.
  ```

## Task 6: 测试 — `PublishTaskToolDagTest`

新文件 `team/src/test/kotlin/io/github/yeyi/agent/team/PublishTaskToolDagTest.kt`,覆盖 schema 解析 + 校验 + 透传 publish。每个 case 验证 BulletinBoard 收到的 `TaskAssignments` 事件载荷(整批 List<TaskAssignment> + roundId)。

- [ ] **happy path (intra-call ref)**: `tasks=[{ref:"lookup",...}, {ref:"summary", depends_on:["lookup"],...}]` → publish `TaskAssignments([lookup_ta, summary_ta])`,其中 `summary_ta.dependsOn = [lookup_ta.taskId]`(ref 解析成 UUID task_id)
- [ ] **parallel roots**: `tasks=[{ref:"a",...}, {ref:"b",...}]` (无依赖) → publish `TaskAssignments([a_ta, b_ta])`,两个 task_id 都是新生成 UUID,无 dependsOn
- [ ] **intra-publish cycle**: `a dep b, b dep a` → 返回 error, 不分配任何 task_id
- [ ] **self-loop**: `a dep a` → 返回 error(注意:`a dep "a"` 是 ref 引用同 ref,ref 解析后是自环)
- [ ] **missing depends_on ref**: `a dep "x"`, x 不在本批 refs 也不在 knownTaskIds → 返回 error
- [ ] **intra-call ref dup**: `tasks=[{ref:"a",...}, {ref:"a",...}]` → 返回 error (Duplicate ref)
- [ ] **cross-publish 引用 (task_id)**: 先调一次 `publishTask.execute(tasks=[{ref:"x",...}])` 让 knownTaskIds 累积 `x_ta.taskId`, 再调 `publishTask.execute(tasks=[{ref:"a", depends_on:[<x_ta.taskId>],...}])` → 接受,验证 emit 的 `a_ta.dependsOn = [<x_ta.taskId>]`
- [ ] **depends_on 混用 ref + task_id**: 同次调用内 `a dep ["lookup", <x_ta.taskId>]`(本批 ref + 跨批 task_id 混用)→ 接受
- [ ] **空 depends_on 数组**: 等价于无依赖, 行为同未填
- [ ] **summary 返回 task_id 给 LLM**: 验证 execute 返回的 ToolExecutionResult 包含每个 task 的 task_id(UUID)和 ref 映射,LLM 后续轮次可拿 task_id 引用
- [ ] **TaskAssignments 不带 roundId**: 验证 emit 的 `TaskAssignments` 没有 roundId 字段(roundId 由 BossAgent 内部管理,不在事件里)
- [ ] **knownTaskIdsSnapshot 行为**: publish 成功后 `publishTask.knownTaskIdsSnapshot()` 包含新 task_id(程序生成的 UUID);校验失败不污染 snapshot

每个 case 用 `kotlinx.coroutines.test.runTest` 或 `runBlocking`, 真实 `BulletinBoard` + spy collector 验证 emit 的事件类型是 `TaskAssignments`,并对比 spy 收集的 task_id 与 knownTaskIdsSnapshot 一致。跨 round 测试通过「先调一次 `publishTask.execute(...)` 累积 knownTaskIds,再从响应里拿 task_id」准备前置状态(走完整 publish 路径,更接近真实使用)。

## Task 7: 测试 — `PastureDagTest`

新文件 `team/src/test/kotlin/io/github/yeyi/agent/team/PastureDagTest.kt`,覆盖 DAG 调度 + TaskUpdate emit + cascade + 跨 round 引用。**不验证 roundId**(Pasture 不感知)。

- [ ] **链式**: publish `[a, b dep a]`, 验证 a 完成前 b 不 dispatch, a 完成 → b dispatch 时 context 含 a 的结果, **a 和 b 都 emit `TaskUpdate`**(透明, Pasture 不区分)
- [ ] **diamond**: publish `[a, b dep a, c dep a, d dep b,c]`, 验证 dispatch 顺序: a 先, b/c 并发, d 最后; **四个 task 都 emit TaskUpdate**
- [ ] **parallel roots**: publish `[a, b]` (无依赖), 验证两者都 dispatch 并发, **两者都 emit TaskUpdate**
- [ ] **上游结果合并格式**: 验证 b 的 context 形如 `"[a]\n${aResult}\n\n${b.context || b.task}"`
- [ ] **cancel upstream cascade**: publish `[a, b dep a, c dep b]`, cancel(a) → a → CANCELED, b → FAILED (cascade), c → FAILED (cascade), **三个都 emit TaskUpdate**(transparent)
- [ ] **cancel PENDING/READY**: publish `[a, b dep a]`, cancel(b)(b 此时 PENDING), 验证 b → CANCELED emit TaskUpdate, a 仍正常完成
- [ ] **跨 round 引用**: 第一次 publish `[a]`, a 跑完; 第二次 publish `[b dep a]`, 验证 b 在 a 完成后 dispatch(`dependsOn` 引用通过 `PublishTaskTool.knownTaskIds` 校验通过,Pasture 内部 `tryAdvancePending` 推进)
- [ ] **Pasture 调度状态可观察**: publish `[a]`,验证 `pasture.dag` 包含 a 节点(`internal` getter 或测试 in same module 可见 dag map);不暴露外部 `knownTaskIds()` API(权威源在 PublishTaskTool 侧)

## Task 8: 测试 — `BossAgentDagIntegrationTest`

新文件 `team/src/test/kotlin/io/github/yeyi/agent/team/BossAgentDagIntegrationTest.kt`,覆盖 Boss 只在 round 完成时续轮 + round summary 聚合。

- [ ] **单 task round**: Boss publish `[weather]` → round 完成 → Boss 续轮看到 `formatRoundSummary` 的内容
- [ ] **DAG 不提前续轮**: Boss publish `[a, b dep a]`, a 跑完时 Boss **不续轮**(只累加 events + 检查 round 不完成), b 跑完时 round 完成 → Boss 续轮看到 summary
- [ ] **summary 聚合**: 验证 `formatRoundSummary` 包含 round 内每个 task 的 taskId + status (✓ done / ✗ failed) + detail
- [ ] **跨 round 累积**: round 1 publish `[a]` → round 完成 → 续轮; round 2 (用户输入) → publish `[b dep a]` → 加进 round 2(新 roundId) → b 完成 → round 2 完成 → 续轮看到新 summary(round 2 的 summary)
- [ ] **DAG 失败 cascade 端到端**: Boss publish `[a, b dep a, c dep b]`, a 的 LLM 模拟抛异常 → b/c cascade failed → round 完成(三个都 terminal)→ Boss 续轮看到失败 summary
- [ ] **diamond 并发**: 4-task DAG, 验证 Pasture 调度并发正确, round 最终完成 → Boss 续轮看到 summary 包含四个 task
- [ ] **LLM input 包含 roundSummary**: 验证续轮 round 中 Boss LLM 收到的 input 包含 `formatRoundSummary` 的字符串(round summary),不是单 task 结果列表

## Task 9: 验证

- [ ] `./gradlew :team:test` 全部通过
- [ ] 现有测试 (PastureTest / BossAgentTest / BossAgentIntegrationTest) 不退化 (DAG 是 additive; round 完成触发等价于原 task terminal 触发的语义对单 task round)
- [ ] Boss 系统提示词更新后, 用真实 LLM (可选, CI 可跳过) 验证能正确使用 depends_on

---

## 风险与回滚

- **API 变更 (破坏性)**:
  - `TaskAssignment` 加必填字段 `dependsOn`,退化为非 event —— 所有构造点需更新(主要是 `PublishTaskTool.execute` 和测试)
  - `TaskUpdate` 原来是 `PublishEvent` 的实现类,现在改为独立的 `ProgressEvent` 实现类 —— 任何直接引用 `PublishEvent.TaskUpdate` 的代码需改为 `TaskUpdate`
  - `pendingResultEvents` channel 类型从 `Channel<TaskUpdate>` 改为 `Channel<TaskUpdate>`
  - ~~`PublishTaskTool` 构造签名变(加 `roundIdProvider` + `knownTaskIds` 回调)~~ —— **撤回**:roundId 由 BossAgent 内部管理,knownTaskIds 由 PublishTaskTool 内部维护,**PublishTaskTool 构造签名零变化**,BossAgentBuilder 零改动
  - ~~`task_id` 从 UUID 生成改为 LLM 提供~~ —— **撤回**:`task_id` 仍由 UUID 生成(只是从 PublishTaskTool 内部生成,而不是原代码每次 execute 内生成);LLM 只提供 `ref`(本批唯一 symbolic name)。**LLM 不必提供 task_id,token 节省 + 拼写错风险消除**
  - `PublishTaskTool.execute` 校验失败的请求返回 error
  - `UserRound` 加 `roundId` 字段
- **行为变更**:
  - `cancel_task(any_task_id)` 现在会 cascade 所有传递依赖
  - 跨 round 引用未注册的 task_id 现在返回 error(PublishTaskTool 入口拒绝)
  - BossAgent 续轮触发改由 round 完成决定(`handleTaskUpdate` 检测 allDone 后把 roundSummary 压入 channel)
  - LLM input 用 `roundSummary`(round summary),删掉了 `formatTaskResults`
- **回滚**: 没有「软回滚」路径 —— 改动需一步到位(Git revert 即整批回退)
- **未覆盖**: 跨 round 环检测(数学上不可能);group 抽象(本计划主动去除,用 roundId 替代);DAG 可视化(v2);roundId 持久化(每次 run() 重新生成,BossAgent 重启后 round 信息丢失 —— 后续 v2 可考虑持久化)

## 相关历史

- `0c95659 refactor(team): 删除 BulletinBoard.subscriptionCount，测试改用 delay 等待订阅就绪` + 后续 `lateinit bulletinBoard` + `BossAgent 统一订阅 events 流` —— 上一次重构建立的 attach/observe + lateinit 基建, 是本计划 DAG + round 调度器能干净落地的条件
- `team/src/main/kotlin/io/github/yeyi/agent/team/PublishTaskTool.kt:23-43` 当前 description 明确说"For dependent tasks, make multiple calls" —— 本计划反转这个语义为「一次发完整 DAG, 跨 round 用 depends_on 累积」