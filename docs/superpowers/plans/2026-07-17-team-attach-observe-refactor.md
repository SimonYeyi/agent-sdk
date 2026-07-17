# Team 模块 attach/observe 重构

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 把 `BossAgent` / `Pasture` 对 `BulletinBoard` 的订阅从构造期隐式 `scope.launch` 改为显式 suspend 方法 `attach` / `observe`，消除测试里 `delay(50)` / `delay(200)` 等"等订阅就绪"的 magic number。

**Background:** 当前 `BossAgent.init` 和 `Pasture.init` 各自 `scope.launch { collect { ... } }` 隐式启动订阅——构造返回 ≠ 订阅已挂上。测试手工 `bb.publishEvent(...)` 时若订阅尚未就绪，事件被 `SharedFlow(replay = 0)` 丢弃。测试靠 `delay(50)` 盲等，CI 慢 runner 上 flaky（`PastureTest.kt:94` 已从 50ms 调到 200ms）。

**Architecture:**

- `BulletinBoard` 不动（`replay = 0` 表达业务语义"现场直播"，不背负测试基建）
- `BossAgent` 构造不再接收 `BulletinBoard`；新增 `suspend fun attach(bb)` —— 内部 launch 一个 collect 协程（后台长生命周期）订阅 `bulletinBoard.events` 统一流，内部 when 区分 TaskAssignment / TaskUpdate。BossAgent 在 `_events` 上是 1 个订阅者，`subscriptionCount.first { it >= expected }` 用 `expected = +1` 同步等到
- `Pasture` 构造不再接收 `BulletinBoard`；新增 `suspend fun observe(bb)` —— 同上，单 collect 版本
- `BossAgentBuilder.build()` 在创建完 `Pasture` 和 `BossAgent` 后 `runBlocking { pasture.observe(bb); boss.attach(bb) }` —— runBlocking 等的是 `subscriptionCount.first { ... }` 同步信号，几 ms 完成，build 是"同步组装"语义

**关键设计决策:**

1. **attach 是 suspend 而非 fire-and-forget** —— 调用方 await 它 = 拿到"订阅已挂上"的同步保证。fire-and-forget 必须配 magic delay，违背初衷
2. **内部用 `scope.launch` 包 collect** —— attach 方法本身是短生命周期（启动后台 + await ready + 返回），collect 协程是长生命周期（持续订阅直到 scope 取消）。直接 collect 会让 attach 方法永不返回
3. **用 `SharedFlow.subscriptionCount` 而非 `onStart` 触发就绪** —— `onStart` 的 lambda 在 collect 内部注册 collector **之前**同步执行，存在时序 race：`complete()` 唤醒调用方线程后，后台协程可能还没进入 `MutableSharedFlow.collect` 注册 collector，调用方提前 publishEvent 仍会丢事件。`subscriptionCount: StateFlow<Int>` 在 collect 内部**同步原子 +1**，`first { it >= expected }` 返回时所有 expected 个 collector 必然已注册到 `_events`。这是 SharedFlow 标准 API 的硬保证，语义比 `onStart` 紧
4. **用 `lateinit var bulletinBoard` 而非 nullable + getter 视图** —— `private lateinit var bulletinBoard: BulletinBoard` 由 `attach` / `observe` 赋值，后续 collect / `launchBeast` 统一从此字段读取。check `!::bulletinBoard.isInitialized` 防止重复调用。未初始化访问抛 `UninitializedPropertyAccessException`（消息固定但可读）。比 `BulletinBoard?` + 自定义 getter 视图表达力更强（无「两个 BulletinBoard 字段」歧义），且避免 `!!` 在使用点撒糖
5. **build() 用 runBlocking 而非改 suspend** —— `bossAgent { ... }` 调用方零侵入；runBlocking 阻塞的是当前线程（应用启动或测试 setup），不阻塞 dispatcher worker

**Tech Stack:** Kotlin + kotlinx.coroutines + kotlinx.serialization (json)

**Spec 参考:** `docs/superpowers/specs/2026-07-13-team-module-design.md`, `docs/superpowers/specs/2026-07-15-team-boss-and-agent-impl.md`

---

## 文件改动清单

| 路径 | 改动 |
|---|---|
| `team/src/main/kotlin/io/github/yeyi/agent/team/BossAgent.kt` | 构造去 bb、删 init、加 `attach` |
| `team/src/main/kotlin/io/github/yeyi/agent/team/Pasture.kt` | 构造去 bb、删 init、加 `observe` |
| `team/src/main/kotlin/io/github/yeyi/agent/team/BossAgentBuilder.kt` | `build()` 改用 `runBlocking` 等待 attach/observe |
| `team/src/test/kotlin/io/github/yeyi/agent/team/PastureTest.kt` | `setupPasture` 加 `runBlocking { observe(bb) }`，删 `delay(200)` |
| `team/src/test/kotlin/io/github/yeyi/agent/team/BossAgentTest.kt` | 构造后 `runBlocking { boss.attach(bb) }`，删订阅等待类 delay |
| `team/src/test/kotlin/io/github/yeyi/agent/team/BossAgentIntegrationTest.kt` | 构造改 + 加 attach/observe + 删 `@Suppress` 占位变量 |

`BulletinBoard` / `BossAgentBuilder.buildBoss()` / 工具类（`PublishTaskTool` / `CancelTaskTool`）不动。

---

## Task 1: `BossAgent` 改造

- [ ] `BossAgent.kt` 构造签名去掉 `bulletinBoard: BulletinBoard` 参数（`BossAgent.kt:45-49`）
- [ ] 删除 `BossAgent.kt:92-108` 整个 `init {}` 块（含两个 `scope.launch { ... .collect { ... } }`）
- [ ] 新增 `private lateinit var bulletinBoard: BulletinBoard` 字段（与其他字段同区）—— `lateinit` 标记「稍后由 attach 初始化」，避免 nullable + 自定义 getter 视图的双字段形态
- [ ] 在 `run()` 方法前新增 `suspend fun attach(bb: BulletinBoard)`：
  ```kotlin
  public suspend fun attach(bb: BulletinBoard) {
      check(!::bulletinBoard.isInitialized) { "BossAgent.attach() must be called only once" }
      bulletinBoard = bb
      val expected = bulletinBoard.subscriptionCount.value + 1   // BossAgent 是 1 个订阅者
      scope.launch {
          bulletinBoard.events.collect { event ->
              when (event) {
                  is TaskAssignment -> tasksLock.withLock {
                      tasks[event.taskId] = TaskState(event.selections, event.task)
                  }
                  is TaskUpdate -> handleTaskUpdate(event)
                  // 其他事件 (Cancellation 等) BossAgent 不关心, 显式 no-op
                  // 让编译器在 BulletinEvent 加新类型时强制更新此 when.
                  else -> Unit
              }
          }
      }
      bulletinBoard.subscriptionCount.first { it >= expected }
  }
  ```
- [ ] 加 import：`kotlinx.coroutines.flow.first`
- [ ] 移除 import：`kotlinx.coroutines.flow.filterIsInstance`（改用 `events` + when 替代 filterIsInstance）

## Task 2: `Pasture` 改造

- [ ] `Pasture.kt` 构造签名去掉 `bulletinBoard: BulletinBoard` 参数（`Pasture.kt:21-31`）
- [ ] 删除 `Pasture.kt:37-47` 整个 `init {}` 块（含一个 `scope.launch { ... .collect { ... } }`）
- [ ] 新增 `private lateinit var bulletinBoard: BulletinBoard` 字段—— `lateinit` 标记「稍后由 observe 初始化」
- [ ] 新增 `suspend fun observe(bb: BulletinBoard)`：
  ```kotlin
  internal suspend fun observe(bb: BulletinBoard) {
      check(!::bulletinBoard.isInitialized) { "Pasture.observe() must be called only once" }
      bulletinBoard = bb
      val expected = bb.subscriptionCount.value + 1
      scope.launch {
          bulletinBoard.publishEvents
              .collect { event ->
                  when (event) {
                      is TaskAssignment -> handleAssignment(event)
                      is Cancellation -> handleCancellation(event)
                  }
              }
      }
      bulletinBoard.subscriptionCount.first { it >= expected }
  }
  ```
- [ ] 加 import：`kotlinx.coroutines.flow.first`

## Task 3: `BossAgentBuilder` 改造

- [ ] `BossAgentBuilder.kt:76-98` `build()` 改写：
  ```kotlin
  public fun build(): BossAgent {
      val llm = requireNotNull(llmProvider0) { "llmProvider must be set" }
      val mem = requireNotNull(memory0) { "memory must be set" }

      val bulletinBoard = BulletinBoard()
      val bossScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

      val pasture = Pasture(
          llmProvider = llm,
          toolRegistry = delegatedToolRegistry0,
          skillRegistry = skillRegistry0,
          subagentRegistry = subagentRegistry0,
          toolsetRegistry = toolsetRegistry0,
          scope = bossScope,
          maxIterations = maxIterations0,
          maxRounds = maxRounds0,
      )
      val boss = buildBoss(mem, llm, bulletinBoard, bossScope)

      runBlocking {
          pasture.observe(bulletinBoard)
          boss.attach(bulletinBoard)
      }

      return boss
  }
  ```
- [ ] 加 import：`kotlinx.coroutines.runBlocking`
- [ ] `buildBoss(...)` 不动——它仍接收 `bulletinBoard` 用于构造 `PublishTaskTool` / `CancelTaskTool`

**此时编译全断**（测试还在用旧构造签名）。继续下面 Task 修复。

## Task 4: `PastureTest` 改造

- [ ] `setupPasture`（`PastureTest.kt:48-68`）构造 `Pasture` 时去掉 `bulletinBoard = bb`，创建后加 `runBlocking { pasture.observe(bb) }`：
  ```kotlin
  private fun setupPasture(
      toolReg: ToolRegistry? = null,
      skillReg: SkillRegistry? = null,
      toolsetReg: ToolsetRegistry? = null,
      llmResponses: List<ChatResponse> = listOf(PASTURE_FINAL),
  ): Triple<BulletinBoard, Pasture, CoroutineScope> {
      val bb = BulletinBoard()
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val pasture = Pasture(
          llmProvider = FakeLlmProvider(nonStreamResponses = llmResponses),
          toolRegistry = toolReg,
          skillRegistry = skillReg,
          subagentRegistry = null,
          toolsetRegistry = toolsetReg,
          scope = scope,
          maxIterations = 1,
          maxRounds = 5,
      )
      runBlocking { pasture.observe(bb) }
      return Triple(bb, pasture, scope)
  }
  ```
- [ ] `runPastureTest`（`PastureTest.kt:84-96`）删 `delay(200)` 和对应注释；`setupPasture` 已 attach，无需再等：
  ```kotlin
  private fun runPastureTest(
      toolReg: ToolRegistry? = null,
      skillReg: SkillRegistry? = null,
      toolsetReg: ToolsetRegistry? = null,
      llmResponses: List<ChatResponse> = listOf(PASTURE_FINAL),
      block: suspend (BulletinBoard) -> Unit,
  ) = kotlinx.coroutines.runBlocking {
      val (bb, _, _) = setupPasture(toolReg, skillReg, toolsetReg, llmResponses)
      block(bb)
  }
  ```

## Task 5: `BossAgentTest` 改造

`createBossAgent` 不返回 attach 后的 boss——加 attach 是测试侧的职责。

- [ ] 每个测试在 `val (boss, bb) = createBossAgent()` 后加：
  ```kotlin
  runBlocking { boss.attach(bb) }
  ```
- [ ] 删除订阅等待类 `delay(50)`：
  - `BossAgentTest.kt:131`
  - `BossAgentTest.kt:193`
  - `BossAgentTest.kt:258` 及对应注释「等待 BossAgent 订阅回调就绪后再发布任务」
  - `BossAgentTest.kt:304` 及对应注释
- [ ] 删除 `BossAgentTest.kt:273` 的 `delay(100)` 及注释「让 collector 订阅就位」
- [ ] **保留**轮询类 `delay(50)`（这些是轮询业务事件/状态变化，不是等订阅就绪）：
  - `BossAgentTest.kt:281` `while (continuations.isEmpty()) delay(50)`
  - `BossAgentTest.kt:285` `while (boss.state.value != BossState.WAITING) delay(50)`
  - `BossAgentTest.kt:326` `while (boss.state.value != BossState.WAITING) delay(50)`
  - `BossAgentTest.kt:359` `while (boss.state.value != BossState.WAITING) delay(50)`

## Task 6: `BossAgentIntegrationTest` 改造

- [ ] 两个测试（`BossAgentIntegrationTest.kt:80-141` 和 `:144-238`）改构造：
  - `Pasture` 构造去掉 `bulletinBoard = bb`
  - `BossAgent` 构造去掉 `bb` 参数
  - 加 `runBlocking { pasture.observe(bb); boss.attach(bb) }`
- [ ] 删除 `@Suppress("UNUSED_VARIABLE") val _pasture`（`BossAgentIntegrationTest.kt:102, 166`）—— pasture 现在实际被使用
- [ ] 调整订阅等待类 `delay(50)`：
  - `:129` `while (continuations.isEmpty()) delay(50)` —— 这个是等续轮事件到达，**保留**
  - `:133` `while (boss.state.value != BossState.WAITING) delay(50)` —— 等状态落定，**保留**
  - `:226` 同 `:129`，**保留**
  - `:230` 同 `:133`，**保留**

## Task 7: 验证

- [ ] `./gradlew :team:test` 全部通过
- [ ] 删掉所有 magic delay 后，慢 CI runner 上不再 flaky（如有 CI 跑一次实测）

---

## 风险与回滚

- **API 破坏性变更**：`BossAgent` / `Pasture` 构造签名变更，`BossAgentBuilder.build()` 内部行为变更（多了 runBlocking 等几 ms）。调用方影响：`bossAgent { ... }` 调用零改动；直接 `new Pasture(...)` 或 `BossAgent(...)` 的代码（主要是测试）需要改
- **回滚**：保留旧 `init` 里的 launch 和旧构造签名作为废弃路径可以平滑迁移，但本次改动范围小，建议一步到位
- **未覆盖**：第三方使用 `BossAgent` / `Pasture` 构造的场景（项目内目前只有 `BossAgentBuilder` 一处）

## 相关历史

- `0c95659 refactor(team): 删除 BulletinBoard.subscriptionCount，测试改用 delay 等待订阅就绪` —— 本次重构正是要根治这个 commit 留下的 delay 痕迹
- `team/src/test/kotlin/io/github/yeyi/agent/team/PastureTest.kt:94` 注释「was 50ms; flaky on slower runners」—— 已是同问题的局部补丁