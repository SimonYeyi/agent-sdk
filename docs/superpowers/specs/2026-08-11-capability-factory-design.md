# Capability Factory 抽象类设计

> 日期：2026-08-11 · 状态：**Proposed**
> 模块：`capability`（核心改动）+ `subagent` / `skill` / `toolset`（适配）
> 范围：在 capability 模块新增 `CapabilityFactory` 抽象类作为模块实现者的接线契约；同时将 `CapabilityRegistry` / `CapabilityAdapter` / `DefaultCapabilityRegistry` 的泛型顺序统一调整为 `<C, T, Ctx>`。

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 提案代号 | capability-factory |
| 关联模块 | `capability` / `subagent` / `skill` / `toolset` |
| 关联前置 | `Capability` / `CapabilityRegistry` / `CapabilityAdapter` / `DefaultCapabilityRegistry` |
| 破坏性变更 | **是**——`CapabilityRegistry` / `CapabilityAdapter` / `DefaultCapabilityRegistry` 泛型顺序 `<Ctx, C, T>` → `<C, T, Ctx>`。下游继承这三个类的代码必须更新泛型实参位置 |

---

## 1. 动机

当前新增一个 Capability 类型（如 Skill、Subagent、Toolset），实现者要写 6 块散落在不同文件的样板：

1. `XxxContext : CapabilityContext`
2. `XxxContextFactory : CapabilityContextFactory<XxxContext>`
3. `XxxTask` data class + `XxxArguments : CapabilityArguments<XxxTask>`
4. `class XxxRegistry : CapabilityRegistry<XxxContext, Xxx, XxxTask> by DefaultCapabilityRegistry(...)`
5. `interface Xxx : Capability<XxxTask, XxxContext>` + `CAPABILITY_TYPE` companion
6. `AgentBuilder.xxxs(registry, ...)` ext fn，里头手工调 `CapabilityAdapter.of(...).installOn(this)`，并视需要塞辅助 tool（SkillToolLoader/Caller、SubToolDelegate）

`CapabilityRegistry<Ctx, C, T>` 的 3 个泛型**已经**隐式锁住了前 3 块（Context / Capability<T, Ctx> / T）—— 实现者无法绕过。但：
- 仍然要自己拼 `CapabilityAdapter.of(registry, ctxFactory, arguments, ...)` 这串调用
- 辅助 tool 在 ext fn 里硬编码，散落每个模块的 ext fn
- 没有"完成一个能力的接线"这个**显式契约**——靠看 Subagent/Skill 源码当模板

我们要给模块作者一个**单一抽象类**，实现它 = 完成能力。

---

## 2. 设计原则

- **单一契约类**：`CapabilityFactory` 是**抽象类**，不是接口。所有接线逻辑（`installOn`）在抽象类里实现，模块作者只 override 部件点。
- **registry 是构造器参数**：把 `registry` 放到抽象类构造器参数，类型系统强制"必须传入"。
- **行为参数化**：`enableDelegateAdaptMode` 是 `installOn(builder, ...)` 的参数，不是工厂字段——是"装到哪个 builder"的临时决定，不是工厂状态。
- **泛型顺序统一**：`<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>` —— 主类型在前，衍生/依赖类型在后。现有 `CapabilityRegistry` / `CapabilityAdapter` / `DefaultCapabilityRegistry` 同步调整。
- **面向模块作者，不面向调用方**：抽象类是 `public`（契约公开），但各模块的工厂类（`SubagentFactory` / `SkillFactory` / `ToolsetFactory`）是 `internal`——调用方感知不到。

---

## 3. 核心类型

### 3.1 `capability` 模块 —— 新增 `CapabilityFactory.kt`

```kotlin
package io.github.yeyi.agent.capability

import io.github.yeyi.agent.AgentBuilder
import io.github.yeyi.agent.tool.Tool

public abstract class CapabilityFactory<
    C : Capability<T, Ctx>,
    T : Any,
    Ctx : CapabilityContext,
>(
    public val registry: CapabilityRegistry<C, T, Ctx>,
) {
    protected abstract fun contextFactory(): CapabilityContextFactory<Ctx>
    protected abstract fun arguments(): CapabilityArguments<T>?
    protected open fun auxiliaryTools(): List<Tool> = emptyList()

    public open fun installOn(
        agentBuilder: AgentBuilder,
        enableDelegateAdaptMode: Boolean = true,
    ) {
        CapabilityAdapter.of(registry, contextFactory(), arguments(), enableDelegateAdaptMode)
            .installOn(agentBuilder)
        auxiliaryTools().forEach { agentBuilder.tool(it) }
    }
}
```

要点：
- 泛型参数 `<C, T, Ctx>` 与 §4 调整后的 `CapabilityRegistry<C, T, Ctx>` 顺序一致——`registry` 构造参数直接按同名对应位喂值。
- `contextFactory` / `arguments` 是 `protected abstract`，模块必须 override
- `auxiliaryTools` 是 `protected open`，默认空，需要时 override
- `installOn` 是 `public open`，默认实现走 `CapabilityAdapter.of(...)` + `auxiliaryTools()` 遍历；Toolset 需要套 try-catch 时 override

### 3.2 `capability` 模块 —— 泛型顺序调整（破坏性）

`CapabilityRegistry` / `CapabilityAdapter` / `DefaultCapabilityRegistry` 的泛型从 `<Ctx : CapabilityContext, C : Capability<T, Ctx>, T : Any>` 调整为 `<C : Capability<T, Ctx>, T : Any, Ctx : CapabilityContext>`。

涉及文件：
- `CapabilityRegistry.kt` —— interface + DefaultCapabilityRegistry class
- `CapabilityAdapter.kt` —— abstract class + DelegationAdapter + OneToOneAdapter + companion `of()`
- `CapabilityLoadTool.kt` —— `registry: CapabilityRegistry<Ctx, C, T>` 字段
- `CapabilityAdaptTool.kt` —— 若有使用，验证后调整

理由：
1. 主类型 `C`（Capability）在前符合自然阅读
2. `CapabilityFactory` 抽象类的 `<C, T, Ctx>` 顺序与这三个对齐
3. 一致性收益大于一次性破坏成本

### 3.3 `subagent` 模块适配

新增 `SubagentFactory.kt`：
```kotlin
internal class SubagentFactory(
    registry: SubagentRegistry,
) : CapabilityFactory<Subagent, SubagentTask, SubagentContext>(registry) {
    override fun contextFactory() = SubagentContextFactory()
    override fun arguments() = SubagentArguments()
}
```

重构 `SubagentExtensions.kt`：
```kotlin
public fun AgentBuilder.subagents(
    registry: SubagentRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    SubagentFactory(registry).installOn(this, enableDelegateAdaptMode)
}
```

更新 `SubagentRegistry.kt`（泛型实参位置调整）：
```kotlin
// 旧: CapabilityRegistry<SubagentContext, Subagent, SubagentTask>
// 新: CapabilityRegistry<Subagent, SubagentTask, SubagentContext>
public class SubagentRegistry :
    CapabilityRegistry<Subagent, SubagentTask, SubagentContext> by DefaultCapabilityRegistry(
        capabilityType = Subagent.CAPABILITY_TYPE
    )
```

### 3.4 `skill` 模块适配

新增 `SkillFactory.kt`：
```kotlin
internal class SkillFactory(
    registry: SkillRegistry,
) : CapabilityFactory<Skill, Unit, SkillContext>(registry) {
    override fun contextFactory() = SkillContextFactory()
    override fun arguments(): CapabilityArguments<Unit>? = null

    override fun auxiliaryTools(): List<Tool> {
        return if (registry.allTools().isNotEmpty()) {
            listOf(SkillToolLoader(registry), SkillToolCaller(registry))
        } else emptyList()
    }
}
```

重构 `SkillExtensions.kt`：
```kotlin
public fun AgentBuilder.skills(
    registry: SkillRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    SkillFactory(registry).installOn(this, enableDelegateAdaptMode)
}
```

更新 `SkillRegistry.kt`：
```kotlin
// 旧: ToolDispatcher, CapabilityRegistry<SkillContext, Skill, Unit>
// 新: ToolDispatcher, CapabilityRegistry<Skill, Unit, SkillContext>
public class SkillRegistry :
    ToolDispatcher, CapabilityRegistry<Skill, Unit, SkillContext> by DefaultCapabilityRegistry(
    capabilityType = Skill.CAPABILITY_TYPE
) { /* ... existing body unchanged ... */ }
```

### 3.5 `toolset` 模块适配

新增 `ToolsetFactory.kt`：
```kotlin
internal class ToolsetFactory(
    registry: ToolsetRegistry,
) : CapabilityFactory<Toolset, Unit, ToolsetContext>(registry) {
    override fun contextFactory() = ToolsetContextFactory()
    override fun arguments(): CapabilityArguments<Unit>? = null
    override fun auxiliaryTools() = listOf<Tool>(SubToolDelegate(registry))

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

重构 `ToolsetExtensions.kt`：
```kotlin
public fun AgentBuilder.toolsets(
    registry: ToolsetRegistry,
    enableDelegateAdaptMode: Boolean = true,
) {
    ToolsetFactory(registry).installOn(this, enableDelegateAdaptMode)
}
```

更新 `ToolsetRegistry.kt`：
```kotlin
// 旧: CapabilityRegistry<ToolsetContext, Toolset, Unit>
// 新: CapabilityRegistry<Toolset, Unit, ToolsetContext>
public class ToolsetRegistry :
    CapabilityRegistry<Toolset, Unit, ToolsetContext> by DefaultCapabilityRegistry(
        Toolset.CAPABILITY_TYPE
    )
```

---

## 4. 错误处理

- **`ToolsetFactory.installOn` 的 try-catch**：捕获 `ToolDuplicateException`（来自 `CapabilityAdapter` 或 `SubToolDelegate`），包装成 `ToolsetsInstallException`。保持原有错误语义。
- **`SkillFactory.auxiliaryTools` 的条件返回**：`registry.allTools().isNotEmpty()` 时返回 Loader/Caller，否则空列表 —— 与原 `SkillExtensions.skills()` 行为一致。
- **`SubagentFactory`**：无 try-catch、无辅助 tool，行为 = 单纯 `CapabilityAdapter.of(...).installOn(...)`。
- **类型系统层错误**：`contextFactory` / `arguments` 未 override 会编译失败（`abstract`）；`registry` 类型不匹配 `CapabilityRegistry<Ctx, C, T>` 会编译失败（构造参数类型约束）。

---

## 5. 测试

### 5.1 现有测试受影响范围

`CapabilityAdapterTest.kt` 使用 `CapabilityAdapter<*, *, *>` 星投射 + 类型推导调用 `of(...)`，不显式依赖泛型顺序，**预计零改动通过**。验证后再定。

`SkillExtensionsTest.kt` / `ToolsetRegistryTest.kt` 等使用 `XxxRegistry` 的代码可能需要更新泛型实参位置（如果显式声明了的话）。grep 后逐一确认。

### 5.2 新增测试

- **`CapabilityFactoryTest.kt`**（位于 `agent/capability/src/test/...`）：用 `CapabilityRegistry` mock 测 `installOn` 委托、auxiliaryTools 遍历、参数透传
- **`SubagentFactoryTest.kt`**（位于 `agent/subagent/src/test/...`）：registry 持有、enableDelegateAdaptMode 透传、helper tool 安装
- **`SkillFactoryTest.kt`**（位于 `agent/skill/src/test/...`）：auxiliaryTools 条件返回
- **`ToolsetFactoryTest.kt`**（位于 `agent/toolset/src/test/...`）：`ToolsetsInstallException` wrap

测试粒度：覆盖"工厂正确把部件装到 AgentBuilder"集成点。`CapabilityAdapter` 行为细节由 `CapabilityAdapterTest` 覆盖，不重复。

---

## 6. 迁移路径

按以下顺序落地（每步可单独编译/测试）：

1. 新增 `CapabilityFactory.kt`（抽象类 + 默认 installOn）
2. 重排 `CapabilityRegistry` / `CapabilityAdapter` / `DefaultCapabilityRegistry` 泛型为 `<C, T, Ctx>`，同步更新 `CapabilityLoadTool` 字段声明
3. 更新 `SubagentRegistry` / `SkillRegistry` / `ToolsetRegistry` 的 `by` 委托泛型实参
4. 新增各模块的 `XxxFactory.kt`
5. 重写各模块的 `XxxExtensions.kt` 为 ext fn → factory 委托
6. 同步更新 spec doc（`2026-06-23-subagent-design.md`）和 plan doc（`2026-07-15-team-module-impl.md`）中显式声明的泛型顺序
7. 新增 4 个 `XxxFactoryTest`，跑全量 `gradle test` 验证

---

## 7. 风险评估

| 风险 | 等级 | 缓解 |
|---|---|---|
| 泛型重排导致下游编译失败 | MEDIUM | 当前下游仅 Subagent/Skill/Toolset 三个模块；外加 spec/plan 文档。本提案一次性同步更新 |
| Toolset 的 try-catch 在抽象类 `installOn` 上 override，第三方扩展若有继承需要同步 | LOW | `installOn` 是 `open`，且 `ToolsetFactory` 是 `internal`，第三方无法继承 |
| 新增测试用例对 `AgentBuilder` 真实 API 假设可能有偏差 | LOW | plan 阶段先 grep `AgentBuilder` 暴露的 tool 查询方式（`toolRegistry()` 等）再定型测试 |
| 抽象类 vs 接口的可见性细节（如 `protected abstract` 的实现类是否必须 public） | LOW | Kotlin 默认规则：`internal class` 可继承 `public abstract class`，override 成员可见性独立 |

---

## 8. 不在范围内

- `BossAgentBuilder` 五个可选字段的统一折叠（用户明确"不破坏现有接口"，且本次仅解决"实现新能力的契约"）
- 异构 `List<CapabilityFactory>` 列表支持（用户明确不需要）
- `Capability` 接口本身的调整（已经是 `<T, Ctx>`，与新顺序 `<C, T, Ctx>` 中 `C : Capability<T, Ctx>` 的 `T/Ctx` 部分一致）
