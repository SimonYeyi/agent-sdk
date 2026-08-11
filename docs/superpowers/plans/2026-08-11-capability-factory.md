# Capability Factory Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add `CapabilityFactory` abstract class as module implementer contract; reorder `CapabilityRegistry` / `CapabilityAdapter` / `DefaultCapabilityRegistry` generics to `<C, T, Ctx>` for consistency; migrate Subagent / Skill / Toolset to use the new factory.

**Architecture:** Single abstract class `CapabilityFactory<C, T, Ctx>` exposes abstract `registry()` / `contextFactory()` / `arguments()` and open `installOn(builder, mode)` which delegates to `CapabilityAdapter.of(...)` + iterates `auxiliaryTools()`. Each capability module gets an `internal` factory subclass; existing `AgentBuilder.xxxs(registry, mode)` ext fns become one-line delegations.

**Tech Stack:** Kotlin 2.x, kotlinx-serialization (existing), existing test framework (kotlin-test).

## Global Constraints

- All changes must preserve current public API surface (registry classes stay public; ext fn signatures unchanged). Only generic argument positions change in existing types.
- Factory classes (`SubagentFactory` / `SkillFactory` / `ToolsetFactory`) are `internal` — module authors use the abstract class externally; module consumers keep using ext fns.
- `installOn(builder, enableDelegateAdaptMode)` is `open` (Toolset overrides for try-catch wrapping).
- Commit messages follow `<type>(<module>): <subject>`; types: `feat` / `refactor` / `test` / `docs`. Only commit, do not push.

---

## File Structure

| File | Status | Purpose |
|---|---|---|
| `agent/capability/.../CapabilityFactory.kt` | **NEW** | Abstract class + default `installOn` |
| `agent/capability/.../CapabilityRegistry.kt` | modify | Reorder generics `<Ctx, C, T>` → `<C, T, Ctx>` |
| `agent/capability/.../CapabilityAdapter.kt` | modify | Reorder generics on abstract class + 2 private impls + companion `of()` |
| `agent/capability/.../CapabilityLoadTool.kt` | modify | Reorder generics + registry field type |
| `agent/subagent/.../SubagentRegistry.kt` | modify | Update `by` delegation generic args |
| `agent/subagent/.../SubagentExtensions.kt` | modify | Rewrite to delegate to factory |
| `agent/subagent/.../SubagentFactory.kt` | **NEW** | `internal class` extending `CapabilityFactory` |
| `agent/skill/.../SkillRegistry.kt` | modify | Update `by` delegation generic args |
| `agent/skill/.../SkillExtensions.kt` | modify | Rewrite to delegate to factory |
| `agent/skill/.../SkillFactory.kt` | **NEW** | `internal class` extending `CapabilityFactory` |
| `agent/toolset/.../ToolsetRegistry.kt` | modify | Update `by` delegation generic args |
| `agent/toolset/.../ToolsetExtensions.kt` | modify | Rewrite to delegate to factory |
| `agent/toolset/.../ToolsetFactory.kt` | **NEW** | `internal class` extending `CapabilityFactory`, override `installOn` for try-catch |
| `agent/capability/.../CapabilityFactoryTest.kt` | **NEW** | Default installOn behavior tests |
| `agent/subagent/.../SubagentFactoryTest.kt` | **NEW** | Subagent factory wiring tests |
| `agent/skill/.../SkillFactoryTest.kt` | **NEW** | Skill factory wiring tests |
| `agent/toolset/.../ToolsetFactoryTest.kt` | **NEW** | Toolset factory wiring tests |
| `docs/superpowers/specs/2026-06-23-subagent-design.md` | modify | Sync generic order in code block |
| `docs/superpowers/plans/2026-07-15-team-module-impl.md` | modify | Sync generic order in code block |

---

## Task 1: Reorder generics on capability core types

**Files:**
- Modify: `agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityRegistry.kt:12` (interface), `:32` (class)
- Modify: `agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityAdapter.kt:6` (abstract), `:27-37` (companion `of`), `:40-47` (DelegationAdapter), `:49-64` (OneToOneAdapter)
- Modify: `agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityLoadTool.kt:23` (class signature + registry field)

**Interfaces:**
- Consumes: existing classes — no new dependency
- Produces: `CapabilityRegistry<C, T, Ctx>`, `CapabilityAdapter<C, T, Ctx>`, `DefaultCapabilityRegistry<C, T, Ctx>`, `CapabilityLoadTool<C, T, Ctx>` (all with new generic order)

- [ ] **Step 1: Update `CapabilityRegistry.kt` interface**

Replace line 12:
```kotlin
public interface CapabilityRegistry<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>
```
With:
```kotlin
public interface CapabilityRegistry<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>
```

Also replace the kdoc `@param` lines (10-11) to match new param names:
```kotlin
 * @param C capability 类型
 * @param T arguments 类型
 * @param Ctx 执行上下文类型
```

- [ ] **Step 2: Update `CapabilityRegistry.kt` DefaultCapabilityRegistry class**

Replace line 32:
```kotlin
public class DefaultCapabilityRegistry<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    override val capabilityType: String
) : CapabilityRegistry<Ctx, C, T> {
```
With:
```kotlin
public class DefaultCapabilityRegistry<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    override val capabilityType: String
) : CapabilityRegistry<C, T, Ctx> {
```

- [ ] **Step 3: Update `CapabilityAdapter.kt` abstract class**

Replace line 6:
```kotlin
public abstract class CapabilityAdapter<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    protected val registry: CapabilityRegistry<Ctx, C, T>,
    protected val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    protected val arguments: CapabilityArguments<T>?
) {
```
With:
```kotlin
public abstract class CapabilityAdapter<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    protected val registry: CapabilityRegistry<C, T, Ctx>,
    protected val capabilityContextFactory: CapabilityContextFactory<Ctx>,
    protected val arguments: CapabilityArguments<T>?
) {
```

- [ ] **Step 4: Update `CapabilityAdapter.kt` companion `of()`**

Replace lines 27-36:
```kotlin
        public fun <Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any> of(
            registry: CapabilityRegistry<Ctx, C, T>,
            capabilityContextFactory: CapabilityContextFactory<Ctx>,
            arguments: CapabilityArguments<T>?,
            enableDelegateAdaptMode: Boolean = true
        ): CapabilityAdapter<Ctx, C, T> = if (enableDelegateAdaptMode) {
            DelegationAdapter(registry, capabilityContextFactory, arguments)
        } else {
            OneToOneAdapter(registry, capabilityContextFactory, arguments)
        }
```
With:
```kotlin
        public fun <C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext> of(
            registry: CapabilityRegistry<C, T, Ctx>,
            capabilityContextFactory: CapabilityContextFactory<Ctx>,
            arguments: CapabilityArguments<T>?,
            enableDelegateAdaptMode: Boolean = true
        ): CapabilityAdapter<C, T, Ctx> = if (enableDelegateAdaptMode) {
            DelegationAdapter(registry, capabilityContextFactory, arguments)
        } else {
            OneToOneAdapter(registry, capabilityContextFactory, arguments)
        }
```

- [ ] **Step 5: Update `CapabilityAdapter.kt` private DelegationAdapter class**

Replace lines 40-44:
```kotlin
private class DelegationAdapter<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    registry: CapabilityRegistry<Ctx, C, T>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    arguments: CapabilityArguments<T>? = null
) : CapabilityAdapter<Ctx, C, T>(registry, capabilityContextFactory, arguments) {
```
With:
```kotlin
private class DelegationAdapter<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    registry: CapabilityRegistry<C, T, Ctx>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    arguments: CapabilityArguments<T>? = null
) : CapabilityAdapter<C, T, Ctx>(registry, capabilityContextFactory, arguments) {
```

- [ ] **Step 6: Update `CapabilityAdapter.kt` private OneToOneAdapter class**

Replace lines 49-53:
```kotlin
private class OneToOneAdapter<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    registry: CapabilityRegistry<Ctx, C, T>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    arguments: CapabilityArguments<T>? = null
) : CapabilityAdapter<Ctx, C, T>(registry, capabilityContextFactory, arguments) {
```
With:
```kotlin
private class OneToOneAdapter<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    registry: CapabilityRegistry<C, T, Ctx>,
    capabilityContextFactory: CapabilityContextFactory<Ctx>,
    arguments: CapabilityArguments<T>? = null
) : CapabilityAdapter<C, T, Ctx>(registry, capabilityContextFactory, arguments) {
```

- [ ] **Step 7: Update `CapabilityLoadTool.kt` class signature + registry field**

Replace line 23:
```kotlin
internal class CapabilityLoadTool<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>(
    private val registry: CapabilityRegistry<Ctx, C, T>,
```
With:
```kotlin
internal class CapabilityLoadTool<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>(
    private val registry: CapabilityRegistry<C, T, Ctx>,
```

- [ ] **Step 8: Run capability module tests**

Run: `cd agent/capability && ../../gradlew :capability:test`
Expected: PASS — `CapabilityAdapterTest` uses star projections (`CapabilityAdapter<*, *, *>`) and relies on type inference at `of()` call sites, so no test code changes needed.

- [ ] **Step 9: Commit**

```bash
git add agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityRegistry.kt \
        agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityAdapter.kt \
        agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityLoadTool.kt
git commit -m "refactor(capability): 泛型顺序统一为 <C, T, Ctx>,主类型在前"
```

---

## Task 2: Update module registries' `by` delegation generic args

**Files:**
- Modify: `agent/subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentRegistry.kt:10-12`
- Modify: `agent/skill/src/main/kotlin/io/github/yeyi/agent/skill/SkillRegistry.kt:16-18`
- Modify: `agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetRegistry.kt:26-28`

**Interfaces:**
- Consumes: reordered types from Task 1
- Produces: module registries with `<C, T, Ctx>` generic args

- [ ] **Step 1: Update `SubagentRegistry.kt`**

Replace lines 9-12:
```kotlin
public class SubagentRegistry :
    CapabilityRegistry<SubagentContext, Subagent, SubagentTask> by DefaultCapabilityRegistry(
        capabilityType = Subagent.CAPABILITY_TYPE
    )
```
With:
```kotlin
public class SubagentRegistry :
    CapabilityRegistry<Subagent, SubagentTask, SubagentContext> by DefaultCapabilityRegistry(
        capabilityType = Subagent.CAPABILITY_TYPE
    )
```

- [ ] **Step 2: Update `SkillRegistry.kt`**

Replace lines 15-18:
```kotlin
public class SkillRegistry :
    ToolDispatcher, CapabilityRegistry<SkillContext, Skill, Unit> by DefaultCapabilityRegistry(
    capabilityType = Skill.CAPABILITY_TYPE
) {
```
With:
```kotlin
public class SkillRegistry :
    ToolDispatcher, CapabilityRegistry<Skill, Unit, SkillContext> by DefaultCapabilityRegistry(
    capabilityType = Skill.CAPABILITY_TYPE
) {
```

- [ ] **Step 3: Update `ToolsetRegistry.kt`**

Replace lines 25-28:
```kotlin
public class ToolsetRegistry :
    CapabilityRegistry<ToolsetContext, Toolset, Unit> by DefaultCapabilityRegistry(
        Toolset.CAPABILITY_TYPE
    )
```
With:
```kotlin
public class ToolsetRegistry :
    CapabilityRegistry<Toolset, Unit, ToolsetContext> by DefaultCapabilityRegistry(
        Toolset.CAPABILITY_TYPE
    )
```

- [ ] **Step 4: Run all module tests**

Run: `cd agent && ../gradlew :subagent:test :skill:test :toolset:test`
Expected: PASS — existing behavior unchanged.

- [ ] **Step 5: Commit**

```bash
git add agent/subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentRegistry.kt \
        agent/skill/src/main/kotlin/io/github/yeyi/agent/skill/SkillRegistry.kt \
        agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetRegistry.kt
git commit -m "refactor(subagent,skill,toolset): 同步 CapabilityRegistry 泛型实参位置"
```

---

## Task 3: Add `CapabilityFactory` abstract class with default `installOn`

**Files:**
- Create: `agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityFactory.kt`

**Interfaces:**
- Consumes: `CapabilityRegistry<C, T, Ctx>` (from Task 1), `CapabilityAdapter.of(...)`, `CapabilityContextFactory<Ctx>`, `CapabilityArguments<T>?`
- Produces: `abstract class CapabilityFactory<C, T, Ctx>` with abstract `registry()`, `contextFactory()`, `arguments()`, open `auxiliaryTools()`, open `installOn(builder, mode)`

- [ ] **Step 1: Create `CapabilityFactory.kt`**

```kotlin
package io.github.yeyi.agent.capability

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.tool.Tool

/**
 * Capability 包的接线契约 —— 模块实现者继承本抽象类并 override 接线部件 = 完成一个能力。
 *
 * 单一契约,无外部助手类:
 * - 抽象方法 [registry]: 调用方 new 后传入,工厂持有并可继续 register
 * - 抽象方法 [contextFactory] / [arguments]: 必须 override
 * - 开放方法 [auxiliaryTools]: 默认空,需要 Loader/Caller/Delegate 等辅助 tool 时覆写
 *
 * **面向能力模块作者,不面向调用方。** 外部仍按现状
 * `XxxRegistry().apply { register(...) }` + `AgentBuilder.xxxs(registry)`。
 *
 * @param C capability 类型
 * @param T arguments 类型
 * @param Ctx capability context 类型
 */
public abstract class CapabilityFactory<
    C : Capability<T, Ctx>,
    T : Any,
    Ctx : CapabilityContext,
> {

    /** 调用方 new 后传入;工厂持有并可继续 register。 */
    protected abstract fun registry(): CapabilityRegistry<C, T, Ctx>

    /** 把 ToolContext 装成能力专属 context 的工厂。 */
    protected abstract fun contextFactory(): CapabilityContextFactory<Ctx>

    /** arguments schema + serializer;无 arguments 传 null。 */
    protected abstract fun arguments(): CapabilityArguments<T>?

    /** 框架自带辅助 tool —— 默认空。Skill/Toolset 等需要补 Loader/Caller/Delegate 时覆写。 */
    protected open fun auxiliaryTools(): List<Tool> = emptyList()

    /**
     * 安装到 [AgentBuilder]。
     *
     * @param enableDelegateAdaptMode true 委托模式（单一 Delegate Tool），
     *                                false 一一映射模式（每个 Capability 一个 Tool）。
     */
    public open fun installOn(
        agentBuilder: AgentBuilder,
        enableDelegateAdaptMode: Boolean = true,
    ) {
        CapabilityAdapter.of(registry(), contextFactory(), arguments(), enableDelegateAdaptMode)
            .installOn(agentBuilder)
        auxiliaryTools().forEach { agentBuilder.tool(it) }
    }
}
```

- [ ] **Step 2: Verify compile**

Run: `cd agent/capability && ../../gradlew :capability:compileKotlin`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add agent/capability/src/main/kotlin/io/github/yeyi/agent/capability/CapabilityFactory.kt
git commit -m "feat(capability): 新增 CapabilityFactory 抽象类作为模块实现者接线契约"
```

---

## Task 4: Subagent module — add `SubagentFactory` and rewrite `SubagentExtensions`

**Files:**
- Create: `agent/subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentFactory.kt`
- Modify: `agent/subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentExtensions.kt:1-23`

**Interfaces:**
- Consumes: `CapabilityFactory<C, T, Ctx>` (from Task 3), `SubagentRegistry`, `SubagentContextFactory()`, `SubagentArguments()`
- Produces: `internal class SubagentFactory(registry)` + ext fn delegating to `SubagentFactory(registry).installOn(builder, mode)`

- [ ] **Step 1: Create `SubagentFactory.kt`**

```kotlin
package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.capability.CapabilityFactory

/**
 * Subagent 的接线模板 —— 仅 override contextFactory 和 arguments,无辅助 tool。
 *
 * 仅供 Subagent 模块内部 `subagents(registry, ...)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数,不感知本类。
 */
internal class SubagentFactory(
    private val registry: SubagentRegistry,
) : CapabilityFactory<Subagent, SubagentTask, SubagentContext>() {

    override fun registry(): SubagentRegistry = registry

    override fun contextFactory(): SubagentContextFactory = SubagentContextFactory()

    override fun arguments(): SubagentArguments = SubagentArguments()
}
```

- [ ] **Step 2: Rewrite `SubagentExtensions.kt`**

Replace the entire file:
```kotlin
package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.log.LoggingTagged

/**
 * 将 SubagentRegistry 注册到 Agent。
 *
 * @param registry Subagent 注册中心
 * @param enableDelegateAdaptMode true 使用委托模式，false 为每个 subagent 生成独立工具
 */
public fun AgentBuilder.subagents(
    registry: SubagentRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    SubagentFactory(registry).installOn(this, enableDelegateAdaptMode)
}

internal val log = LoggingTagged("subagent")
```

- [ ] **Step 3: Verify compile + existing tests**

Run: `cd agent/subagent && ../../gradlew :subagent:test`
Expected: BUILD SUCCESSFUL; all existing `SubagentTest` and `DynamicSubagentTest` pass.

- [ ] **Step 4: Commit**

```bash
git add agent/subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentFactory.kt \
        agent/subagent/src/main/kotlin/io/github/yeyi/agent/subagent/SubagentExtensions.kt
git commit -m "refactor(subagent): 引入 SubagentFactory,ext fn 收为单行委托"
```

---

## Task 5: Skill module — add `SkillFactory` and rewrite `SkillExtensions`

**Files:**
- Create: `agent/skill/src/main/kotlin/io/github/yeyi/agent/skill/SkillFactory.kt`
- Modify: `agent/skill/src/main/kotlin/io/github/yeyi/agent/skill/SkillExtensions.kt:1-31`

**Interfaces:**
- Consumes: `CapabilityFactory<C, T, Ctx>`, `SkillRegistry`, `SkillContextFactory()`, `SkillToolLoader`, `SkillToolCaller`
- Produces: `internal class SkillFactory(registry)` overriding `auxiliaryTools()` for conditional Loader/Caller; ext fn delegates to factory

- [ ] **Step 1: Create `SkillFactory.kt`**

```kotlin
package io.github.yeyi.agent.skill

import io.github.yeyi.agent.capability.CapabilityArguments
import io.github.yeyi.agent.capability.CapabilityContextFactory
import io.github.yeyi.agent.capability.CapabilityFactory
import io.github.yeyi.agent.tool.Tool

/**
 * Skill 的接线模板 —— override contextFactory + arguments（null）+ auxiliaryTools（条件返回）。
 *
 * 仅供 Skill 模块内部 `skills(registry, ...)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数,不感知本类。
 */
internal class SkillFactory(
    private val registry: SkillRegistry,
) : CapabilityFactory<Skill, Unit, SkillContext>() {

    override fun registry(): SkillRegistry = registry

    override fun contextFactory(): SkillContextFactory = SkillContextFactory()

    override fun arguments(): CapabilityArguments<Unit>? = null

    override fun auxiliaryTools(): List<Tool> {
        return if (registry.allTools().isNotEmpty()) {
            listOf(SkillToolLoader(registry), SkillToolCaller(registry))
        } else emptyList()
    }
}
```

- [ ] **Step 2: Rewrite `SkillExtensions.kt`**

Replace the entire file:
```kotlin
package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder

/**
 * 注册多个 [Skill] 到 Agent。
 *
 * 该扩展函数：
 * 1. 将 [registry] 中的所有 Skill 安装到 AgentBuilder（通过 Capability 框架）
 * 2. 若 registry 含工具，则注册 [SkillToolLoader] 和 [SkillToolCaller]
 *
 * @param registry Skill 注册中心，含所有待注册的 Skill 实例
 * @param enableDelegateAdaptMode 是否启用委托适配模式，默认 true
 */
public fun AgentBuilder.skills(
    registry: SkillRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    SkillFactory(registry).installOn(this, enableDelegateAdaptMode)
}
```

- [ ] **Step 3: Verify compile + existing tests**

Run: `cd agent/skill && ../../gradlew :skill:test`
Expected: BUILD SUCCESSFUL; all existing `SkillTest` / `SkillRegistryTest` / `SkillExtensionsTest` pass.

- [ ] **Step 4: Commit**

```bash
git add agent/skill/src/main/kotlin/io/github/yeyi/agent/skill/SkillFactory.kt \
        agent/skill/src/main/kotlin/io/github/yeyi/agent/skill/SkillExtensions.kt
git commit -m "refactor(skill): 引入 SkillFactory,auxiliaryTools 条件返回 Loader/Caller"
```

---

## Task 6: Toolset module — add `ToolsetFactory` and rewrite `ToolsetExtensions`

**Files:**
- Create: `agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetFactory.kt`
- Modify: `agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetExtensions.kt:1-35`

**Interfaces:**
- Consumes: `CapabilityFactory<C, T, Ctx>`, `ToolsetRegistry`, `ToolsetContextFactory()`, `SubToolDelegate`, `ToolDuplicateException`, `ToolsetsInstallException`
- Produces: `internal class ToolsetFactory(registry)` overriding `installOn` for try-catch wrap; ext fn delegates to factory

- [ ] **Step 1: Create `ToolsetFactory.kt`**

```kotlin
package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.capability.CapabilityAdapter
import io.github.yeyi.agent.capability.CapabilityArguments
import io.github.yeyi.agent.capability.CapabilityFactory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolDuplicateException

/**
 * Toolset 的接线模板 —— override installOn 套 try-catch 把 ToolDuplicateException 包装为 ToolsetsInstallException。
 *
 * 仅供 Toolset 模块内部 `toolsets(registry, ...)` 扩展函数使用;
 * 外部调用方应直接使用扩展函数,不感知本类。
 */
internal class ToolsetFactory(
    private val registry: ToolsetRegistry,
) : CapabilityFactory<Toolset, Unit, ToolsetContext>() {

    override fun registry(): ToolsetRegistry = registry

    override fun contextFactory(): ToolsetContextFactory = ToolsetContextFactory()

    override fun arguments(): CapabilityArguments<Unit>? = null

    override fun auxiliaryTools(): List<Tool> = listOf(SubToolDelegate(registry))

    public override fun installOn(
        agentBuilder: AgentBuilder,
        enableDelegateAdaptMode: Boolean = true,
    ) {
        try {
            super.installOn(agentBuilder, enableDelegateAdaptMode)
        } catch (e: ToolDuplicateException) {
            throw ToolsetsInstallException(e)
        }
    }
}
```

- [ ] **Step 2: Rewrite `ToolsetExtensions.kt`**

Replace the entire file:
```kotlin
package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder

/**
 * DSL — 将 [ToolsetRegistry] 中所有 Toolset 注册到 [AgentBuilder]。
 *
 * @param enableDelegateAdaptMode true (默认) — 单 Load Tool `load_toolset` + 共享 `sub_tool_delegate`；
 *                                false — 每个 Toolset 暴露为独立 Tool `toolset_<name>` + 共享 `sub_tool_delegate`。
 *
 * **不能重复注入** —— 本 DSL 会安装 `load_toolset` / `sub_tool_delegate`,这两个是
 * toolset 框架对外暴露的 discovery/delegation 工具,任何走 toolset 框架的 capability
 * DSL 都会安装同一对(直接 `toolsets()` 调用,或在其之上封装的更高层 DSL)。同一 Agent
 * 上只能出现一次,重复注入抛 [ToolsetsInstallException];直接调用 grep `toolsets`
 * 关键字即可找到,封装型 DSL 需要看它的 kdoc —— 走 toolset 框架的 DSL 会在 kdoc 中
 * 提及 `toolsets`。
 */
public fun AgentBuilder.toolsets(
    registry: ToolsetRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    ToolsetFactory(registry).installOn(this, enableDelegateAdaptMode)
}
```

- [ ] **Step 3: Verify compile + existing tests**

Run: `cd agent/toolset && ../../gradlew :toolset:test`
Expected: BUILD SUCCESSFUL; all existing `ToolsetTest` / `ToolsetRegistryTest` / `ToolsetExtensionsTest` pass.

- [ ] **Step 4: Commit**

```bash
git add agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetFactory.kt \
        agent/toolset/src/main/kotlin/io/github/yeyi/agent/toolset/ToolsetExtensions.kt
git commit -m "refactor(toolset): 引入 ToolsetFactory,installOn override 套 ToolsetsInstallException wrap"
```

---

## Task 7: Sync generic order in spec / plan docs

**Files:**
- Modify: `docs/superpowers/specs/2026-06-23-subagent-design.md:182`
- Modify: `docs/superpowers/plans/2026-07-15-team-module-impl.md:148`

**Interfaces:**
- Consumes: completed reorder from Tasks 1-2
- Produces: doc code blocks showing new `<C, T, Ctx>` order

- [ ] **Step 1: Update `2026-06-23-subagent-design.md:182`**

Replace line 182:
```kotlin
    CapabilityRegistry<SubagentContext, Subagent, SubagentTask> by DefaultCapabilityRegistry(
```
With:
```kotlin
    CapabilityRegistry<Subagent, SubagentTask, SubagentContext> by DefaultCapabilityRegistry(
```

- [ ] **Step 2: Update `2026-07-15-team-module-impl.md:148`**

Replace line 148:
```kotlin
    ToolDispatcher, CapabilityRegistry<SkillContext, Skill, Unit> by DefaultCapabilityRegistry(
```
With:
```kotlin
    ToolDispatcher, CapabilityRegistry<Skill, Unit, SkillContext> by DefaultCapabilityRegistry(
```

- [ ] **Step 3: Commit**

```bash
git add docs/superpowers/specs/2026-06-23-subagent-design.md \
        docs/superpowers/plans/2026-07-15-team-module-impl.md
git commit -m "docs(spec,plan): 同步 CapabilityRegistry 泛型实参位置到 <C, T, Ctx>"
```

---

## Task 8: Add `CapabilityFactoryTest.kt`

**Files:**
- Create: `agent/capability/src/test/kotlin/io/github/yeyi/agent/capability/CapabilityFactoryTest.kt`

**Interfaces:**
- Consumes: `CapabilityFactory<C, T, Ctx>` (from Task 3), a stub `Capability<T, Ctx>` + registry + context factory for anonymous subclass
- Produces: tests for default `installOn` wiring (auxiliaryTools iteration + CapabilityAdapter delegation)

- [ ] **Step 1: Create `CapabilityFactoryTest.kt`**

```kotlin
package io.github.yeyi.agent.capability

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CapabilityFactoryTest {

    // ---------- stubs ----------

    private class StubContext : CapabilityContext

    private class StubContextFactory : CapabilityContextFactory<StubContext> {
        override fun create(context: ToolContext): StubContext = StubContext()
    }

    private class StubCapability(
        override val name: String,
        override val description: String,
    ) : Capability<Unit, StubContext> {
        override suspend fun activate(arguments: Unit?, context: StubContext): String =
            "stub activated: $name"
    }

    private class StubAuxTool(private val toolName: String) : Tool {
        override val name: String get() = toolName
        override val description: String = "stub $toolName"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult.success("")
    }

    // AgentBuilder.toolRegistry is private — peek via reflection for assertions.
    private fun AgentBuilder.installedTools(): List<Tool> {
        val f = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ToolRegistry).all()
    }

    private fun minimalFactory(
        registry: CapabilityRegistry<StubCapability, Unit, StubContext> =
            DefaultCapabilityRegistry("stub").apply { register(StubCapability("a", "desc a")) },
        auxiliaryTools: List<Tool> = emptyList(),
    ): CapabilityFactory<StubCapability, Unit, StubContext> =
        object : CapabilityFactory<StubCapability, Unit, StubContext>() {
            override fun registry(): CapabilityRegistry<StubCapability, Unit, StubContext> = registry
            override fun contextFactory() = StubContextFactory()
            override fun arguments(): CapabilityArguments<Unit>? = null
            override fun auxiliaryTools(): List<Tool> = auxiliaryTools
        }

    private fun emptyBuilder(): AgentBuilder = AgentBuilder()

    // ---------- tests ----------

    @Test
    fun `factory exposes registry passed in constructor`() {
        val registry = DefaultCapabilityRegistry<StubCapability, Unit, StubContext>("stub")
        val factory = minimalFactory(registry)
        assertEquals(registry, factory.registry())
    }

    @Test
    fun `installOn in delegate mode installs load_stub tool`() {
        val factory = minimalFactory()
        val builder = emptyBuilder()
        factory.installOn(builder, enableDelegateAdaptMode = true)
        val toolNames = builder.installedTools().map { it.name }
        assertTrue("load_stub" in toolNames, "expected load_stub, got $toolNames")
    }

    @Test
    fun `installOn in one-to-one mode installs per-capability tool`() {
        val registry = DefaultCapabilityRegistry<StubCapability, Unit, StubContext>("stub").apply {
            register(StubCapability("alpha", "d"))
            register(StubCapability("beta", "d"))
        }
        val factory = minimalFactory(registry)
        val builder = emptyBuilder()
        factory.installOn(builder, enableDelegateAdaptMode = false)
        val toolNames = builder.installedTools().map { it.name }
        assertTrue("stub_alpha" in toolNames, "expected stub_alpha, got $toolNames")
        assertTrue("stub_beta" in toolNames, "expected stub_beta, got $toolNames")
        assertFalse("load_stub" in toolNames, "delegate tool should NOT be installed in one-to-one mode")
    }

    @Test
    fun `installOn installs auxiliaryTools after CapabilityAdapter`() {
        val aux = StubAuxTool("aux_helper")
        val factory = minimalFactory(auxiliaryTools = listOf(aux))
        val builder = emptyBuilder()
        factory.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertTrue("aux_helper" in toolNames, "expected aux_helper, got $toolNames")
        assertTrue("load_stub" in toolNames, "delegate tool must still be installed alongside aux tools")
    }

    @Test
    fun `installOn with empty auxiliaryTools installs only the load tool`() {
        val factory = minimalFactory(auxiliaryTools = emptyList())
        val builder = emptyBuilder()
        factory.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertEquals(listOf("load_stub"), toolNames)
    }
}
```

- [ ] **Step 2: Run test**

Run: `cd agent/capability && ../../gradlew :capability:test --tests CapabilityFactoryTest`
Expected: PASS — all 5 tests green.

- [ ] **Step 3: Commit**

```bash
git add agent/capability/src/test/kotlin/io/github/yeyi/agent/capability/CapabilityFactoryTest.kt
git commit -m "test(capability): CapabilityFactory 默认 installOn 行为测试"
```

---

## Task 9: Add `SubagentFactoryTest.kt`

**Files:**
- Create: `agent/subagent/src/test/kotlin/io/github/yeyi/agent/subagent/SubagentFactoryTest.kt`

**Interfaces:**
- Consumes: `SubagentFactory`, `SubagentRegistry`
- Produces: tests for Subagent factory wiring (registry exposure + installOn mode behavior)

**Stubs inlined**: `SubagentTest.kt` has `StubSubagent` / `StubLlmProvider` as `private class`, so they cannot be reused across files. This test file defines its own minimal stubs.

- [ ] **Step 1: Create `SubagentFactoryTest.kt`**

```kotlin
package io.github.yeyi.agent.subagent

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertFalse
import kotlin.test.assertSame

class SubagentFactoryTest {

    // ---------- stubs (local; SubagentTest.kt helpers are private) ----------

    private class StubSubagent(
        override val name: String,
        override val description: String = "stub subagent",
        override val maxIterations: Int? = 5,
        override val memory: Memory? = null,
        override val tools: List<Tool>? = null,
    ) : Subagent {
        override suspend fun load(): String = "stub instructions"
    }

    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest) =
            io.github.yeyi.agent.llm.ChatResponse(
                message = io.github.yeyi.agent.llm.ChatMessage.Assistant(content = "ok"),
                finishReason = io.github.yeyi.agent.llm.FinishReason.Stop,
            )
        override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Done(usage = null, finishReason = io.github.yeyi.agent.llm.FinishReason.Stop))
    }

    // AgentBuilder.toolRegistry is private — peek via reflection for assertions.
    private fun AgentBuilder.installedTools(): List<Tool> {
        val f = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ToolRegistry).all()
    }

    private fun newBuilder(): AgentBuilder = agent { llmProvider(StubLlm) }

    // ---------- tests ----------

    @Test
    fun `factory exposes the same registry passed in`() {
        val registry = SubagentRegistry().apply { register(StubSubagent("alpha")) }
        val factory = SubagentFactory(registry)
        assertSame(registry, factory.registry())
    }

    @Test
    fun `installOn in delegate mode installs load_subagent tool`() {
        val registry = SubagentRegistry().apply { register(StubSubagent("alpha")) }
        val factory = SubagentFactory(registry)
        val builder = newBuilder()
        factory.installOn(builder, enableDelegateAdaptMode = true)
        val toolNames = builder.installedTools().map { it.name }
        assertContains(toolNames, "load_subagent")
    }

    @Test
    fun `installOn in one-to-one mode installs per-subagent tools`() {
        val registry = SubagentRegistry().apply {
            register(StubSubagent("alpha"))
            register(StubSubagent("beta"))
        }
        val factory = SubagentFactory(registry)
        val builder = newBuilder()
        factory.installOn(builder, enableDelegateAdaptMode = false)
        val toolNames = builder.installedTools().map { it.name }
        assertContains(toolNames, "subagent_alpha")
        assertContains(toolNames, "subagent_beta")
        assertFalse("load_subagent" in toolNames)
    }

    @Test
    fun `installOn respects enableDelegateAdaptMode toggle`() {
        val registry = SubagentRegistry().apply { register(StubSubagent("x")) }
        val delegateBuilder = newBuilder()
        val oneToOneBuilder = newBuilder()
        SubagentFactory(registry).installOn(delegateBuilder, enableDelegateAdaptMode = true)
        SubagentFactory(registry).installOn(oneToOneBuilder, enableDelegateAdaptMode = false)
        assertContains(delegateBuilder.installedTools().map { it.name }, "load_subagent")
        assertFalse("load_subagent" in oneToOneBuilder.installedTools().map { it.name })
    }
}
```

- [ ] **Step 2: Run test**

Run: `cd agent/subagent && ../../gradlew :subagent:test --tests SubagentFactoryTest`
Expected: PASS — all 4 tests green.

- [ ] **Step 3: Commit**

```bash
git add agent/subagent/src/test/kotlin/io/github/yeyi/agent/subagent/SubagentFactoryTest.kt
git commit -m "test(subagent): SubagentFactory wiring 测试"
```

---

## Task 10: Add `SkillFactoryTest.kt`

**Files:**
- Create: `agent/skill/src/test/kotlin/io/github/yeyi/agent/skill/SkillFactoryTest.kt`

**Interfaces:**
- Consumes: `SkillFactory`, `SkillRegistry`
- Produces: tests for Skill factory wiring (auxiliaryTools conditional behavior)

**Stubs inlined**: `SkillTest.kt` has `FixedSkill` (not `StubSkill`) and `SkillExtensionsTest.kt` has its own `RecordingLlm` — both `private class`, not reusable across files. This test file defines its own minimal stubs.

- [ ] **Step 1: Create `SkillFactoryTest.kt`**

```kotlin
package io.github.yeyi.agent.skill

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.serialization.json.JsonElement
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SkillFactoryTest {

    // ---------- stubs (local; existing test helpers are private) ----------

    private class StubSkill(
        override val name: String,
        override val description: String = "stub skill",
    ) : Skill {
        override suspend fun load(): String = "stub instructions"
    }

    private class StubSkillTool(override val name: String) : Tool {
        override val description: String = "stub tool"
        override val parametersSchema: ToolParameters = ToolParameters.Empty
        override suspend fun execute(arguments: JsonElement, context: ToolContext): ToolExecutionResult =
            ToolExecutionResult.success("")
    }

    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest) =
            io.github.yeyi.agent.llm.ChatResponse(
                message = io.github.yeyi.agent.llm.ChatMessage.Assistant(content = "ok"),
                finishReason = io.github.yeyi.agent.llm.FinishReason.Stop,
            )
        override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Done(usage = null, finishReason = io.github.yeyi.agent.llm.FinishReason.Stop))
    }

    // AgentBuilder.toolRegistry is private — peek via reflection for assertions.
    private fun AgentBuilder.installedTools(): List<Tool> {
        val f = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ToolRegistry).all()
    }

    private fun newBuilder(): AgentBuilder = agent { llmProvider(StubLlm) }

    // ---------- tests ----------

    @Test
    fun `factory exposes the same registry passed in`() {
        val registry = SkillRegistry().apply { register(StubSkill("alpha")) }
        val factory = SkillFactory(registry)
        assertEquals(registry, factory.registry())
    }

    @Test
    fun `installOn installs load_skill tool`() {
        val registry = SkillRegistry().apply { register(StubSkill("alpha")) }
        val factory = SkillFactory(registry)
        val builder = newBuilder()
        factory.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertContains(toolNames, "load_skill")
    }

    @Test
    fun `installOn does NOT install SkillToolLoader or SkillToolCaller when registry has no tools`() {
        val registry = SkillRegistry().apply { register(StubSkill("alpha")) }
        val factory = SkillFactory(registry)
        val builder = newBuilder()
        factory.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertFalse("skill_tool_loader" in toolNames)
        assertFalse("skill_tool_caller" in toolNames)
    }

    @Test
    fun `installOn installs SkillToolLoader and SkillToolCaller when registry has tools`() {
        val registry = SkillRegistry().apply {
            register(StubSkill("alpha"))
            registerTools(listOf(StubSkillTool("helper_a")))
        }
        val factory = SkillFactory(registry)
        val builder = newBuilder()
        factory.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertContains(toolNames, "skill_tool_loader")
        assertContains(toolNames, "skill_tool_caller")
    }
}
```

- [ ] **Step 3: Run test**

Run: `cd agent/skill && ../../gradlew :skill:test --tests SkillFactoryTest`
Expected: PASS. If helper classes / APIs mismatch, adjust before commit.

- [ ] **Step 4: Commit**

```bash
git add agent/skill/src/test/kotlin/io/github/yeyi/agent/skill/SkillFactoryTest.kt
git commit -m "test(skill): SkillFactory wiring + auxiliaryTools 条件返回测试"
```

---

## Task 11: Add `ToolsetFactoryTest.kt`

**Files:**
- Create: `agent/toolset/src/test/kotlin/io/github/yeyi/agent/toolset/ToolsetFactoryTest.kt`

**Interfaces:**
- Consumes: `ToolsetFactory`, `ToolsetRegistry`, `Toolset(name, description)` factory function
- Produces: tests for Toolset factory wiring + `ToolsetsInstallException` wrap on duplicate

**Stubs inlined**: `ToolsetTest.kt` has `UnusedLlm` (not `StubLlmProvider`), and it's `private object` — not reusable across files. This test file defines its own minimal stub.

- [ ] **Step 1: Create `ToolsetFactoryTest.kt`**

```kotlin
package io.github.yeyi.agent.toolset

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.llm.StreamEvent
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class ToolsetFactoryTest {

    // ---------- stubs (local; existing test helpers are private) ----------

    private object StubLlm : LlmProvider {
        override val name: String = "stub"
        override suspend fun chat(request: io.github.yeyi.agent.llm.ChatRequest) =
            io.github.yeyi.agent.llm.ChatResponse(
                message = io.github.yeyi.agent.llm.ChatMessage.Assistant(content = "ok"),
                finishReason = io.github.yeyi.agent.llm.FinishReason.Stop,
            )
        override fun chatStream(request: io.github.yeyi.agent.llm.ChatRequest): Flow<StreamEvent> =
            flowOf(StreamEvent.Done(usage = null, finishReason = io.github.yeyi.agent.llm.FinishReason.Stop))
    }

    // AgentBuilder.toolRegistry is private — peek via reflection for assertions.
    private fun AgentBuilder.installedTools(): List<Tool> {
        val f = AgentBuilder::class.java.getDeclaredField("toolRegistry").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        return (f.get(this) as ToolRegistry).all()
    }

    private fun newBuilder(): AgentBuilder = agent { llmProvider(StubLlm) }

    // ---------- tests ----------

    @Test
    fun `factory exposes the same registry passed in`() {
        val registry = ToolsetRegistry().apply {
            register(Toolset("alpha", "alpha tools"))
        }
        val factory = ToolsetFactory(registry)
        assertEquals(registry, factory.registry())
    }

    @Test
    fun `installOn installs load_toolset and sub_tool_delegate tools`() {
        val registry = ToolsetRegistry().apply {
            register(Toolset("alpha", "alpha tools"))
        }
        val factory = ToolsetFactory(registry)
        val builder = newBuilder()
        factory.installOn(builder)
        val toolNames = builder.installedTools().map { it.name }
        assertContains(toolNames, "load_toolset")
        assertContains(toolNames, "sub_tool_delegate")
    }

    @Test
    fun `installOn wraps ToolDuplicateException into ToolsetsInstallException`() {
        val registry = ToolsetRegistry().apply {
            register(Toolset("alpha", "alpha tools"))
        }
        val factory = ToolsetFactory(registry)
        val builder = newBuilder()
        // First install populates load_toolset + sub_tool_delegate
        factory.installOn(builder)
        // Second install on same builder must throw ToolsetsInstallException
        assertFailsWith<ToolsetsInstallException> {
            factory.installOn(builder)
        }
    }
}
```

- [ ] **Step 3: Run test**

Run: `cd agent/toolset && ../../gradlew :toolset:test --tests ToolsetFactoryTest`
Expected: PASS. Adjust helper imports if mismatch.

- [ ] **Step 4: Commit**

```bash
git add agent/toolset/src/test/kotlin/io/github/yeyi/agent/toolset/ToolsetFactoryTest.kt
git commit -m "test(toolset): ToolsetFactory wiring + ToolsetsInstallException wrap 测试"
```

---

## Task 12: Full regression run

**Files:** (no file changes — verification only)

- [ ] **Step 1: Run full test suite**

Run: `cd agent && ../gradlew test`
Expected: ALL tests pass — including `CapabilityAdapterTest`, `SubagentTest`, `DynamicSubagentTest`, `SkillTest`, `SkillRegistryTest`, `SkillExtensionsTest`, `ToolsetTest`, `ToolsetRegistryTest`, `ToolsetExtensionsTest`, plus the 4 new factory tests.

- [ ] **Step 2: Verify downstream modules still compile**

Run: `cd team && ../gradlew compileKotlin`
Expected: BUILD SUCCESSFUL — BossAgentBuilder uses module registries / ext fns without re-declaring generic args, so no change needed.

- [ ] **Step 3: Run demo module compile**

Run: `cd demos && ../gradlew compileKotlin`
Expected: BUILD SUCCESSFUL — demos reference `subagents()` / `skills()` ext fns and registries whose signatures are preserved.

- [ ] **Step 4: Final summary**

No commit. Print:
```
✓ All 9 commits applied
✓ Generic reorder: <Ctx, C, T> → <C, T, Ctx> across capability module
✓ CapabilityFactory abstract class added
✓ Subagent/Skill/Toolset modules migrated to internal factories
✓ 4 new factory tests added
✓ Spec/plan docs synced
✓ No external API breakage (ext fn signatures unchanged)
```
