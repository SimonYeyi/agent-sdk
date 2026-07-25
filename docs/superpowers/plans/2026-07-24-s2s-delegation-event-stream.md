# S2S 委派事件播报 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 S2S delegation 从“每次 `run()` 等待并匹配一个异步结果”改为“统一通过全局 `updates` 流按到达顺序播报”，避免无标识 continuation 队列造成结果错位。

**Architecture:** `RealtimeDelegation.run(asrText)` 只负责触发一次 Boss 请求；当前请求的即时 `Final`、即时失败和 `boss.continuations` 的异步结果统一转换为 `DelegationUpdate`，通过一个全局 `updates: Flow<DelegationUpdate>` 输出。`RealtimeAppliance` 在自己的生命周期内只订阅一次该流，所有更新按流收集顺序调用 `session.injectAndRespond()`；不引入 `taskId`，不建立 `run()` 与 continuation 的程序级绑定。

**Tech Stack:** Kotlin Multiplatform/JVM, kotlinx.coroutines `Flow`, `MutableSharedFlow`, `merge`, Kotlin Test, `runTest`.

## Global Constraints

- 不引入 `taskId`、请求 ID 或结果匹配表。
- 不让 `run()` 等待 `boss.continuations` 的异步任务结果。
- `run()` 方法名保留；`DelegationResult` 改为 `DelegationUpdate`。
- `Confirmation`、`Success`、`Failure` 的文本直接透传，不添加额外前缀。
- `RealtimeAppliance` 只建立一个 `delegation.updates` 订阅。
- marker 委派发生时，先调用 `session.cancelResponse()`，再触发 `delegation.run(asrText)`。
- 多个任务按异步结果实际到达顺序播报，不强制按提交顺序重排。
- `BossDelegation` 的一个实例只服务一个 S2S/Boss 上下文；不支持多个独立会话共享同一全局 updates 流。

---

## 文件结构

本次只修改已有文件，不新增生产代码文件：

- Modify: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt`
  - 定义新的 `RealtimeDelegation`、`DelegationUpdate` 接口形态。
  - 在 `RealtimeAppliance` 生命周期中收集一次 `updates`。
  - 让事件处理只负责触发 `run()`，不再等待或匹配结果。
- Modify: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/RealtimeApplianceTest.kt`
  - 更新 fake delegation 和 fake session。
  - 验证统一更新流、并行任务完成顺序、关闭生命周期和 marker 打断行为。
- Modify: `demo/src/main/kotlin/io/github/yeyi/agent/demo/s2s/BossDelegation.kt`
  - 将 `boss.run()` 即时事件和 `boss.continuations` 合并为统一更新流。
  - `run()` 只派发并处理即时结果，不等待 continuation。

不修改 `BossAgent` 或 `team` 模块，因为 S2S 语音输出不需要任务级程序关联。

---

### Task 1: 重定义 realtime delegation 更新模型

**Files:**
- Modify: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt`
- Test: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/RealtimeApplianceTest.kt`

**Interfaces:**
- Produces:
  ```kotlin
  public sealed interface DelegationUpdate {
      public data class Confirmation(val text: String) : DelegationUpdate
      public data class Success(val text: String) : DelegationUpdate
      public data class Failure(val message: String) : DelegationUpdate
  }

  public interface RealtimeDelegation {
      public val capabilities: List<String>
      public val updates: Flow<DelegationUpdate>
      public suspend fun run(asrText: String)
  }
  ```
- Consumes: Existing `DelegationHandler`, `RealtimeEvent`, `Flow`, and coroutine scope owned by `RealtimeAppliance`.

- [ ] **Step 1: Update tests to model a global update stream**

  Replace the fake delegation's result-returning `run()` with an emitter-backed implementation. The fake must expose a `MutableSharedFlow` or `Channel` as `updates`, record each ASR input, and make `run(asrText)` record the dispatch without waiting for a result. Add helpers that emit `Confirmation`, `Success`, and `Failure` updates independently of the `run()` invocation.

  The test fake should represent the intended semantics explicitly:

  ```kotlin
  private class FakeDelegation : RealtimeDelegation {
      override val capabilities: List<String> = emptyList()
      private val updateEmitter = MutableSharedFlow<DelegationUpdate>(extraBufferCapacity = 16)
      override val updates: Flow<DelegationUpdate> = updateEmitter.asSharedFlow()
      val dispatched = Channel<String>(Channel.UNLIMITED)

      override suspend fun run(asrText: String) {
          dispatched.send(asrText)
      }

      fun emit(update: DelegationUpdate) {
          check(updateEmitter.tryEmit(update))
      }
  }
  ```

  Use the repository's existing coroutine test style if the current test file already has a more suitable signaling primitive; do not introduce task IDs into the fake.

- [ ] **Step 2: Run the focused test to verify the interface migration fails**

  Run:

  ```bash
  ./gradlew :realtime:core:test --tests io.github.yeyi.agent.realtime.RealtimeApplianceTest
  ```

  Expected: FAIL at compilation because production code still expects the old `RealtimeDelegation` result contract.

- [ ] **Step 3: Implement the new public update types and interface**

  In `RealtimeAppliance.kt`, replace `DelegationResult` with `DelegationUpdate`, add the `updates` property to `RealtimeDelegation`, and change `run` to `public suspend fun run(asrText: String)`.

  Keep the three update payloads direct and minimal:

  ```kotlin
  public sealed interface DelegationUpdate {
      public data class Confirmation(val text: String) : DelegationUpdate
      public data class Success(val text: String) : DelegationUpdate
      public data class Failure(val message: String) : DelegationUpdate
  }
  ```

  Do not add IDs, source fields, timestamps, wrapper messages, or compatibility overloads.

- [ ] **Step 4: Run the focused test to verify the type migration passes its compile boundary**

  Run:

  ```bash
  ./gradlew :realtime:core:compileTestKotlin
  ```

  Expected: The new interface and fake compile; remaining failures, if any, should be limited to old `RealtimeAppliance` behavior and old test expectations.

---

### Task 2: Make `RealtimeAppliance` consume one global updates stream

**Files:**
- Modify: `realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt`
- Test: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/RealtimeApplianceTest.kt`

**Interfaces:**
- Consumes:
  ```kotlin
  RealtimeDelegation.updates: Flow<DelegationUpdate>
  RealtimeDelegation.run(asrText: String): Unit
  ```
- Produces: A single appliance-owned collector that maps every update payload to its raw text and calls `session.injectAndRespond()` exactly once per update.

- [ ] **Step 1: Add a failing test for global update delivery**

  Add a test that starts the appliance, waits for its delegation update collector to subscribe, emits updates without associating them with any `run()` call, and verifies the session receives them in order:

  ```kotlin
  @Test
  fun `delegation updates are injected in stream order`() = runTest {
      val session = FakeSession(fakeInputFormat, fakeOutputFormat)
      val delegation = FakeDelegation()
      val appliance = makeAppliance(session = session, delegation = delegation)

      appliance.start()
      val firstSubscription = realAwait { delegation.subscribedSignal.receive() }

      delegation.emit(DelegationUpdate.Confirmation("正在处理"))
      delegation.emit(DelegationUpdate.Success("空调已打开"))
      delegation.emit(DelegationUpdate.Failure("缺少房间参数"))

      val injected = listOf(
          realAwait { session.injectedSignal.receive() },
          realAwait { session.injectedSignal.receive() },
          realAwait { session.injectedSignal.receive() },
      )

      appliance.close()

      assertEquals(listOf("正在处理", "空调已打开", "缺少房间参数"), injected)
  }
  ```

  Adapt the helper and signal names to the existing test fixture. The test must not call `run()` before emitting the updates; this proves the appliance consumes a global stream rather than a per-run result.

- [ ] **Step 2: Run the new test to verify it fails**

  Run:

  ```bash
  ./gradlew :realtime:core:test --tests 'io.github.yeyi.agent.realtime.RealtimeApplianceTest.delegation updates are injected in stream order'
  ```

  Expected: FAIL because `RealtimeAppliance` currently does not subscribe to `delegation.updates`.

- [ ] **Step 3: Start one updates collector inside `start()`**

  After the session, microphone, and speaker are initialized, launch exactly one collector under the appliance `scope`:

  ```kotlin
  scope?.launch {
      delegation?.updates?.collect { update ->
          val text = when (update) {
              is DelegationUpdate.Confirmation -> update.text
              is DelegationUpdate.Success -> update.text
              is DelegationUpdate.Failure -> update.message
          }
          session.injectAndRespond(text)
      }
  }
  ```

  Keep the collector inside the same `SupervisorJob` so `close()` cancels it through the existing scope lifecycle. Do not create a `Channel` that assigns results back to individual `run()` calls. A single collector also serializes calls to `injectAndRespond()` in update arrival order.

- [ ] **Step 4: Change marker handling to cancel and dispatch only**

  In `DelegationHandler`, keep the existing marker detection and pending ASR behavior, but change the marker branch to:

  ```kotlin
  pendingAsr?.let { asrText ->
      scopeProvider()?.launch {
          session.cancelResponse()
          delegation.run(asrText)
      }
  }
  ```

  The handler must not collect a return flow, wait for a continuation, or call `injectAndRespond()` itself. `RealtimeAppliance`'s single `updates` collector owns all output injection.

  Preserve the existing response lifecycle clearing behavior for `ResponseDone`, `ResponseCanceled`, `AssistantAudioDone`, and `Error` unless the focused tests show that the marker cancellation needs a narrowly scoped adjustment.

- [ ] **Step 5: Run the focused realtime tests**

  Run:

  ```bash
  ./gradlew :realtime:core:test --tests io.github.yeyi.agent.realtime.RealtimeApplianceTest
  ```

  Expected: PASS for the global update delivery test and all existing tests updated to the new contract; no duplicate `injectAndRespond()` calls.

---

### Task 3: Convert `BossDelegation` to a global update source

**Files:**
- Modify: `demo/src/main/kotlin/io/github/yeyi/agent/demo/s2s/BossDelegation.kt`
- Test: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/RealtimeApplianceTest.kt` for appliance behavior; add a demo test only if the demo module already has a suitable BossAgent test harness.

**Interfaces:**
- Consumes:
  - `BossAgent.run(asrText): Flow<AgentEvent>` for immediate round events.
  - `BossAgent.continuations` for asynchronous task completion events.
- Produces:
  ```kotlin
  override val updates: Flow<DelegationUpdate>
  override suspend fun run(asrText: String)
  ```

- [ ] **Step 1: Add a test or fixture for both Boss output paths**

  Verify the intended mapping before implementation:

  ```text
  boss.run() AgentEvent.Final   -> Confirmation(raw final content)
  boss.run() AgentEvent.Failed  -> Failure(raw failure message)
  continuation Final            -> Success(raw continuation content)
  ```

  If constructing a real `BossAgent` is impractical in the current demo test setup, keep the exact mapping covered through the existing `RealtimeApplianceTest` fake and use compilation plus the existing team event tests to validate the concrete event names. Do not introduce a fake task ID or a per-request result queue.

- [ ] **Step 2: Implement a replay-safe immediate update emitter**

  Add a private `MutableSharedFlow<DelegationUpdate>` for immediate `boss.run()` output and expose a merged flow with the hot continuation stream:

  ```kotlin
  private val  runEvents = MutableSharedFlow<DelegationUpdate>(extraBufferCapacity = 64)

  override val updates: Flow<DelegationUpdate> = merge(
      runEvents,
      boss.continuations.mapNotNull { event ->
          when (event) {
              is AgentEvent.Final -> DelegationUpdate.Success(event.result.message.content)
              is AgentEvent.Failed -> DelegationUpdate.Failure(
                  event.cause.message ?: event.cause.toString(),
              )
              else -> null
          }
      },
  )
  ```

  Use the actual continuation result property names from the team module. Do not start a separate coroutine in `init`; the appliance-owned collector must control subscription and cancellation.

- [ ] **Step 3: Make `run()` emit only the current Boss round output**

  Implement `run(asrText)` by collecting `boss.run(asrText)` and emitting raw updates:

  ```kotlin
  override suspend fun run(asrText: String) {
      boss.run(asrText).collect { event ->
          when (event) {
              is AgentEvent.Final ->  runEvents.emit(
                  DelegationUpdate.Confirmation(event.result.message.content),
              )

              is AgentEvent.Failed ->  runEvents.emit(
                  DelegationUpdate.Failure(event.cause.message ?: event.cause.toString()),
              )

              else -> Unit
          }
      }
  }
  ```

  Do not prepend `任务完成` or any other explanatory text. Do not collect `boss.continuations` inside `run()`. Do not block `run()` on the asynchronous task result.

- [ ] **Step 4: Run demo and realtime compilation**

  Run:

  ```bash
  ./gradlew :realtime:core:compileKotlin :demo:compileKotlin
  ```

  Expected: PASS with no remaining references to `DelegationResult`, old `run(): DelegationResult`, or object-level continuation buffering in `BossDelegation`.

---

### Task 4: Replace tests that assume per-run result matching

**Files:**
- Modify: `realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/RealtimeApplianceTest.kt`

**Interfaces:**
- Tests the public behavior of `RealtimeAppliance`, not internal task/result association.

- [ ] **Step 1: Rewrite the old delegation tests around updates**

  Replace tests that wait on `delegation.calledSignal` followed by `session.injectedSignal` from the same `run()` result with tests that:

  1. emit the marker event;
  2. verify `session.cancelResponse()` was called;
  3. verify the ASR text was dispatched through `FakeDelegation.run()`;
  4. independently emit a `DelegationUpdate.Success` or `Failure`;
  5. verify the raw update text is injected once.

  Keep the chitchat test asserting that ordinary S2S audio does not trigger delegation.

- [ ] **Step 2: Add the parallel completion-order test**

  Add a test equivalent to:

  ```kotlin
  @Test
  fun `parallel delegation results are injected once in completion order`() = runTest {
      val session = FakeSession(fakeInputFormat, fakeOutputFormat)
      val delegation = FakeDelegation()
      val appliance = makeAppliance(session = session, delegation = delegation)

      appliance.start()
      awaitSubscribed(session)
      awaitDelegationUpdatesSubscribed(delegation)

      session.emit(RealtimeEvent.UserTranscriptCompleted("打开空调"))
      session.emit(RealtimeEvent.AssistantTextDelta("|正在处理空调"))
      session.emit(RealtimeEvent.UserTranscriptCompleted("播放音乐"))
      session.emit(RealtimeEvent.AssistantTextDelta("|正在处理音乐"))

      realAwait { delegation.dispatched.receive() }
      realAwait { delegation.dispatched.receive() }

      delegation.emit(DelegationUpdate.Success("音乐已播放"))
      delegation.emit(DelegationUpdate.Success("空调已打开"))

      val first = realAwait { session.injectedSignal.receive() }
      val second = realAwait { session.injectedSignal.receive() }

      appliance.close()

      assertEquals("音乐已播放", first)
      assertEquals("空调已打开", second)
      assertEquals(2, session.injectCount)
  }
  ```

  Adjust marker text and signal setup to the existing fixture. The assertion must verify completion order and exactly-once injection, not task identity.

- [ ] **Step 3: Add close lifecycle coverage**

  Emit an update after `appliance.close()` and verify no additional `injectAndRespond()` occurs. Keep existing double-close behavior if it remains part of the public lifecycle contract.

- [ ] **Step 4: Run all realtime core tests**

  Run:

  ```bash
  ./gradlew :realtime:core:test
  ```

  Expected: PASS, including existing microphone forwarding, idempotent start, reconnect, audio passthrough, and null delegation tests.

---

### Task 5: Repository-wide cleanup and validation

**Files:**
- Modify: Any remaining Kotlin callers found by search, only if they still reference the old delegation contract.

**Interfaces:**
- Final public API has exactly one `run(asrText: String)` method and one `updates: Flow<DelegationUpdate>` stream.

- [ ] **Step 1: Search for stale result API references**

  Run:

  ```bash
  git grep -n -E 'DelegationResult|run\(asrText: String\).*Delegation|continuations.*Channel|calledSignal' -- '*.kt'
  ```

  Expected: No production references to `DelegationResult` or a continuation result channel. Test-only signal names may remain if they are unrelated to result matching.

- [ ] **Step 2: Run formatting and static checks used by the repository**

  Run the repository's existing check task:

  ```bash
  ./gradlew check
  ```

  Expected: PASS. If the full check task includes unrelated pre-existing failures, report the exact failing task and run the relevant `:realtime:core:test` and `:demo:compileKotlin` tasks separately.

- [ ] **Step 3: Review the diff for accidental scope expansion**

  Run:

  ```bash
  git diff -- realtime/core/src/main/kotlin/io/github/yeyi/agent/realtime/RealtimeAppliance.kt realtime/core/src/test/kotlin/io/github/yeyi/agent/realtime/RealtimeApplianceTest.kt demo/src/main/kotlin/io/github/yeyi/agent/demo/s2s/BossDelegation.kt
  ```

  Confirm that the diff does not add task IDs, modify `BossAgent`, alter unrelated audio behavior, or add result prefixes.

- [ ] **Step 4: Run the final focused verification**

  Run:

  ```bash
  ./gradlew :realtime:core:test :demo:compileKotlin
  ```

  Expected: PASS.
