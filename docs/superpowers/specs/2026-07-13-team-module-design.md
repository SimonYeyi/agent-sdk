# Team 模块设计

> 日期：2026-07-13 · 状态：**Draft**（待用户审阅）
> 模块：新增 `team/`
> 范围：把 `boss` + `布告栏` + `牧场` + `牛马` 四个组件封装为 `TeamAgent` 容器，提供异步任务编排能力。boss 包装 `ReActAgent` 做闲聊 / 意图识别 / 一次性工具调用 / 派活；牛马包装 `ReActAgent` 做单次任务执行；布告栏作纯事件总线；牧场作任务路由 + 调度。

---

## 0. 元信息

| 项 | 值 |
|---|---|
| 提案代号 | team |
| 关联模块 | 新增 `team/` |
| 关联前置 | `agent` (ReActAgent / AgentEvent / AgentBuilder / Tool) / `skill` (Skill / SkillRegistry) / `subagent` (Subagent / SubagentRegistry) / `toolset` (Toolset / ToolsetRegistry) / `mcp` (Mcp / McpRegistry — 内部走 Toolset) / `capability` (Capability / CapabilityRegistry) / `session` (Session / JsonlBackedMemory — 仅 BossAgent 依赖) |
| 破坏性变更 | 否（新增模块） |
| 不在范围 | 多牧场分布式、跨进程持久化、限流、ox pool 复用、任务超时、任务优先级、boss 远程加载 |

---

## 1. 动机

当前 SDK 已有的任务委托能力（`subagent` 模块）是 **LLM 主动调用 + 同步阻塞等待结果** 的模型：

```
boss LLM 调 subagent 工具
  → 启动 subagent 跑 ReAct
    → 等 subagent 跑完拿到结果
      → 继续 boss 自己的 ReAct
```

这有两个痛点：

1. **响应延迟**：派发 subagent 时 boss 必须等其跑完才能给用户最终回复，**长任务期间用户看不到任何反馈**。
2. **编排受限**：boss LLM 在一次 run() 内只能线性编排子任务，**无法并发派活多个独立任务、无法跨轮次等待依赖任务的结果**。

业务上典型的"发包-接单"场景需要：

- boss 收到用户请求后**立即响应**（"我让搜索助手去查了，稍等"），不等任务完成
- 派发出去的任务**异步执行**，进度/结果**实时回流**到 boss
- boss 看到结果后再决定怎么回复用户
- boss 自己也能**闲聊 / 一次性工具调用**，不需要每个交互都派活

**外包模型**：boss = 发包方，布告栏 = 平台，牧场 = 外包公司，牛马 = 外包人员。boss 通过布告栏平台发布任务，牧场（外部公司）通过布告栏接收任务或取消，牧场驱动外包人员干活。

---

## 2. 设计原则

- **复用优先**：不引入新的执行循环、不重写 `ReActAgent`；不引入新的能力抽象，复用 `Skill` / `Subagent` / `Toolset` / `Capability` 现有体系；不引入新的 memory 抽象，boss 用 `Session.memory`、ox 用 `InMemoryMemory`。
- **包装不重写**：`BossAgent` 包装 `ReActAgent` + 状态机；`Beast` 接口（牛/马）也是 `ReActAgent` 的薄包装，按工作模式区分（牛=通用、马=专项），承担"任务执行"职责。
- **配置外部一次**：team 是一个整体容器 — 每个 capability 类别至多 1 个 registry (`ToolRegistry`/`SkillRegistry`/`SubagentRegistry`/`ToolsetRegistry`)，在 `teamAgent { }` DSL 里一次性配置，由 `TeamAgent` 内部分配给 boss（菜单）和 pasture（路由），不要求外部配置两次。MCP 经 `McpRegistry` 内部注册到 `ToolsetRegistry`，不单独占位。boss 看到的能力 = pasture 路由的能力（同一 registry 实例），保证派活的"承诺"和执行的"能力"对得上。
- **Beast 抽象**：牛 (Ox) 通用 beast，registry 自由配置；马 (Horse) 专项 beast，pre-resolved capability 描述塞 persona + 按类型 wire Tool。Pasture 按 `TaskAssignment` 派发，v1 多数走 `Horse` 分支；Ox 是 [Pasture.assembleHorse] 失败 (selections 空 / 含任何 subagent / 找不到 selection) 经 `error` 抛 `IllegalStateException` 后由 `handleAssignment` 兜底退 [Ox] — 三类失败共用 [Pasture.buildOx] helper.
- **闲聊 / 派活靠 LLM 自主**：框架不区分，由 `PublishTaskTool.description` 引导 LLM 选择"直接 final 回复"还是"调 publish_task 派活"。
- **纯事件总线**：`BulletinBoard` 是无状态的事件发布 / 订阅器，不持有任务状态、不提供业务方法（不提供 `submitAssignment` / `publishInput` 这种带业务语义的 API）。
- **最小防御性**：按 "good enough" 原则，不为构造 / 发布 / 取消增加额外 fail-fast 校验（空字符串警告、UUID 格式检查等不做）。
- **YAGNI**：多牧场、跨进程、持久化、限流、ox pool 复用全部不在范围；保留扩展点（未来加 `Semaphore` 等）但当前不实现。

---

## 3. 架构

### 3.1 4 组件 + 1 容器

```
┌────────────────────────────────────────────────────────────────────┐
│                      TeamAgent (容器 / 装配点)                       │
│                                                                    │
│  team 整体配置 (一次性声明, TeamAgent 内部分配)                          │
│    • skillRegistry        ──┐                                       │
│    • subagentRegistry     ──┼──► boss (菜单)                          │
│    • toolsetRegistry      ──┤   pasture (路由)                        │
│    │   (含 MCP via McpRegistry 注册的 toolset)                       │
│    │                       ──┘                                       │
│    • toolRegistry         ──► boss 菜单 (tool 类) + pasture 解析     │
│    • tools (quick)         ──► boss.toolRegistry (boss LLM 直调)      │
│                                                                    │
│  ┌──────────────┐    ┌──────────────┐    ┌──────────────┐         │
│  │ BulletinBoard│◄──►│   Pasture    │◄──►│   BossAgent  │         │
│  │  纯事件总线   │    │  任务路由+调度 │    │  包装 ReAct  │         │
│  └──────────────┘    └──────┬───────┘    └──────┬───────┘         │
│                             │                  │                  │
│                             ▼                  ▼                  │
│                      ┌──────────────┐    ┌──────────────┐         │
│                      │   Beast      │    │   registries │         │
│                      │ (Ox / Horse) │    │   registries │         │
│                      │ 包装 ReAct  │    │              │         │
│                      └──────────────┘    └──────────────┘         │
└────────────────────────────────────────────────────────────────────┘
```

### 3.2 端到端消息流

```
[用户]  "帮我搜下 Kotlin 协程,然后发邮件给 boss@x.com"
   │
   │ ① sendInput()
   ▼
[BossAgent]  state=WAITING → RUNNING
   │  innerAgent.run() 启动
   │  boss LLM 决策: 派活 + 闲聊
   ▼
[BossAgent]  调 PublishTaskTool.execute(tasks = [{selections: [{type: "toolset", name: "web_search"}], task: "Kotlin 协程"}, {selections: [{type: "toolset", name: "email_sender"}], task: "发邮件给 boss@x.com"}])
   │  内部: 为每个 task 生成 taskId, tasks[taskId1] = TaskState(selections=[Toolset("web_search")], ...), tasks[taskId2] = TaskState(selections=[Toolset("email_sender")], ...)
   │  内部: bulletinBoard.publishEvent(TaskAssignment(taskId1, [Toolset("web_search")], ...)) × 2
   │  内部: 调 CancelTaskTool 不调
   │  调完后 boss LLM 决定: 直接 final 文本回复用户
   ▼
[ReActAgent] emit Final("已让搜索助手和邮件助手去处理,稍等")
   │
[BossAgent]  state=RUNNING → WAITING
   │
   │ ② bulletinBoard.publishEvent(TaskAssignment) ─────►
   ▼
[BulletinBoard]  events 流 emit TaskAssignment
   │
   │  pasture.events.collect 接到
   ▼
[Pasture]  handleAssignment(taskAssignment)
   │  1. assembleHorse(selections) → 解析 selections, 拼出 Horse(llmProvider, persona, tools); 失败 (empty / 含 subagent / 找不到) 经 [error] 抛 IllegalStateException
   │  2. catch IllegalStateException → buildOx() 兜底
   │  3. 启动 coroutine job:
   │       beast.run(task) { event -> bulletinBoard.progressEvent(TaskUpdate(...)) }
   │  4. runningJobs[taskId] = job
   │  5. job.invokeOnCompletion { runningJobs.remove(taskId) }
   ▼
[Beast/Horse]  内部 new ReActAgent(独立 memory, persona=baseRole+capability 描述, tools=wire 后的) → 跑 ReAct 循环
   │
   │  逐个 AgentEvent
   ▼
[Pasture.handleAssignment loop]  bulletinBoard.progressEvent(TaskUpdate(taskId, event))
   │
   │  ③ bulletinBoard.progressEvent(TaskUpdate) ─────►
   ▼
[BulletinBoard]  events 流 emit TaskUpdate
   │
   │  boss.events.collect 接到
   ▼
[BossAgent]  handleTaskUpdate(update)
   │  1. tasks[taskId].events.append(event)
   │  2. state=WAITING → 触发新 run(), 把 TaskUpdate 注入 memory 作为 system/user 消息
   │  3. innerAgent.run() 看到 "任务 X 已 Final: 结果是 Y" → final 文本
   ▼
[ReActAgent] emit Final("搜索完成,结果如下: ... 邮件已发送")
   │
[BossAgent]  state=RUNNING → WAITING
   │
   ▼
[用户]  看到响应
```

### 3.3 取消流

```
[用户]  "算了,那个搜索任务取消"
   │
   ▼
[BossAgent]  LLM 调 CancelTaskTool.execute(taskId)
   │  内部: bulletinBoard.publishEvent(Cancellation(taskId))
   ▼
[BulletinBoard]  events 流 emit Cancellation
   │
   ▼
[Pasture]  handleCancellation(cancellation)
   │  runningJobs[taskId]?.cancel()
   │  (job 不存在 = 已完成, 静默忽略)
   ▼
[Beast]  ReActAgent 协程抛 CancellationException
   │
   ▼
[Pasture.handleAssignment catch]  bulletinBoard.progressEvent(TaskUpdate(taskId, AgentEvent.Failed(CancellationException())))
   │
   ▼
[BossAgent]  看到 Failed(CancellationException) → 报告用户
```

---

## 4. 核心类型

### 4.1 `BulletinBoard`（布告栏 — 纯事件总线）

```kotlin
package io.github.yeyi.agent.team

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.filterIsInstance

/**
 * 纯事件总线 — 接受任务/进度事件发布, 暴露订阅口给订阅者.
 *
 * 发布入口拆成两个: [publishEvent] (任务派发/取消, boss → pasture) 和 [progressEvent]
 * (任务进度/结果, pasture → boss). 类型系统强制方向, 防止事件污染.
 *
 * 订阅口也对称拆成两个类型化 Flow: [publishEvents] (派发方向) 和 [progressEvents]
 * (进度方向). 共享同一内部 [MutableSharedFlow], 零额外缓冲开销. 调试/日志场景用
 * 全局 [events] 总线.
 *
 * 内部无状态: 不持有任务 job, 不生成 taskId.
 *
 * 容量策略: extraBufferCapacity = 64, BufferOverflow.SUSPEND.
 * 订阅者慢不会丢失事件, 只会 back-pressure 发送者.
 */
internal class BulletinBoard {
    private val _events = MutableSharedFlow<BulletinEvent>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.SUSPEND
    )

    /** 全局事件总线 (调试/日志用). 业务订阅请用 [publishEvents] / [progressEvents]. */
    internal val events: SharedFlow<BulletinEvent> = _events.asSharedFlow()

    /** 类型化订阅口 — 派发方向 (boss → pasture). pasture 订阅. */
    internal val publishEvents: Flow<PublishEvent> = _events.filterIsInstance()

    /** 类型化订阅口 — 进度方向 (pasture → boss). boss 订阅. */
    internal val progressEvents: Flow<ProgressEvent> = _events.filterIsInstance()

    /** 发布任务事件 (TaskAssignment / Cancellation). boss 调用. */
    internal suspend fun publishEvent(event: PublishEvent) {
        _events.emit(event)
    }

    /** 发布进度事件 (TaskUpdate 及后续扩展). pasture 调用. */
    internal suspend fun progressEvent(event: ProgressEvent) {
        _events.emit(event)
    }
}
```

### 4.2 `BulletinEvent`（事件 — 按性质分层）

```kotlin
package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent

/**
 * 布告栏上流动的所有事件 — 顶层 sealed interface.
 * 按性质分两个并列的 sealed interface: [PublishEvent] / [ProgressEvent].
 */
internal sealed interface BulletinEvent

/** 任务发布事件 — boss 派发/取消任务. boss → pasture. */
internal sealed interface PublishEvent : BulletinEvent

/** 任务进度事件 — 任务执行的进度/结果. pasture → boss. 当前只含 [TaskUpdate]; 预留扩展点. */
internal sealed interface ProgressEvent : BulletinEvent

/**
 * 任务派发. 1 team 1 boss, 事件由 [BulletinBoard] 直传, 不带 boss 标识.
 *
 * @param selections boss LLM 选定的能力列表 (一个 task 可组合多个 selection).
 *   - 空 → [assembleHorse] 抛 [IllegalStateException] → handleAssignment 兜底退 [Ox]
 *   - 含任何 [Selection.Subagent] → 同上, 兜底退 [Ox] (Horse 装配破坏 subagent 闭环)
 *   - 其余 → 派发到 [Horse] (pre-load 模式, Pasture 按 selections 预组装 persona + tools);
 *     任一 selection 找不到抛 [IllegalStateException], 同样兜底退 [Ox]
 * @param task 本任务的**核心指令** — beast 直接执行的目标. 非空时作为 beast 的 user input
 *   (无 context 时直接是 task; 有 context 时拼在 context 后, 空行分隔).
 * @param context 可选 — 完成本 task **必要的上下文背景信息** (用户偏好 / 历史任务
 *   结果 / 环境状态 / 业务背景等). 拼在 task 前 (空行分隔) 作为 beast 的 user input 头部.
 *   不传或空白时 beast 只接收 task.
 */
internal data class TaskAssignment(
    internal val taskId: String,
    internal val selections: List<Selection>,
    internal val task: String,
    internal val context: String? = null,
) : PublishEvent

/**
 * 任务派发的"选了什么能力" — 一个 task 可携带多个 Selection, 由 [Pasture.assembleHorse] 一次性解析.
 *
 * 4 种类型分别对应 TeamAgent 配置的 4 个 registry:
 * - [Skill] → [io.github.yeyi.agent.skill.SkillRegistry]
 * - [Toolset] → [io.github.yeyi.agent.toolset.ToolsetRegistry] (含 MCP via [io.github.yeyi.agent.mcp.McpRegistry])
 * - [Subagent] → [io.github.yeyi.agent.subagent.SubagentRegistry]
 * - [Tool] → [io.github.yeyi.agent.tool.ToolRegistry] (普通 Tool, 不属于 Capability)
 *
 * v1 限制: 一个 task 最多 1 个 [Subagent] (subagent 整体接管 persona; 多个 subagent 需要协调, v1 不支持).
 *   schema 上 `publish_task` 阻止 LLM 传多个; Pasture.assembleHorse 兜底 — 若 LLM 绕过 schema 传了多个,
 *   全部 selection 丢弃, 退到 Ox 通用模式 (不报错).
 */
internal sealed interface Selection {
    /**
     * 路由键 — 协议层 (JSON `"type"` 字段) 与 sealed 子类一一对应,
     * 由 [Selection] 自身契约保证 (override `type`); [PublishTaskTool] 按 `type` 路由到 sealed 子类构造.
     *
     * 为什么不放 `name` 进接口:
     * - `type` 是**派发**维度 (由 sealed 子类决定, 编译期固定, 1 子类 → 1 字符串)
     * - `name` 是**资源**维度 (由 LLM 运行时传入, 同子类的不同实例 `name` 不同, 1 实例 → 1 字符串)
     * 把 `name` 放接口会让所有 sealed 子类强约束"必须可空"或"必须有", 与设计不符
     */
    val type: String

    internal data class Skill(val name: String) : Selection {
        override val type: String get() = TYPE
        internal companion object { const val TYPE: String = "skill" }
    }
    internal data class Toolset(val name: String) : Selection {
        override val type: String get() = TYPE
        internal companion object { const val TYPE: String = "toolset" }
    }
    internal data class Subagent(val name: String) : Selection {
        override val type: String get() = TYPE
        internal companion object { const val TYPE: String = "subagent" }
    }
    internal data class Tool(val name: String) : Selection {
        override val type: String get() = TYPE
        internal companion object { const val TYPE: String = "tool" }
    }

    /**
     * 协议 type 字符串 → factory. 与 sealed 子类一对一 — "声明子类" 与 "维护 factory" 在
     * [Selection] 内就近挨着, 新增子类时不易遗漏.
     *
     * 新增 [Selection] 子类时**必须**同时:
     * 1. override `type` + 在子类伴生对象声明 `TYPE` 常量 (sealed interface 契约 — 编译期约束)
     * 2. 在本表加一行 (`<SubClass>.TYPE to <SubClass>::<SubClass>`) — **手写**约定,
     *    编译器无法对 `String → sealed` 强制穷尽 (Kotlin sealed `when` 仅对 sealed type
     *    本身生效, 不能跨 string 协议层 — 这层映射靠本表承载, 漏改会落到 else 分支
     *    并被 [PublishTaskTool.execute] 入口 `Selection.FACTORIES[type]?.invoke(name)`
     *    fail-fast 拦截, 不会静默成功)
     *
     * 已收敛的同源点 (新增子类时**自动**跟随本表变更, 无需手工改):
     * - [PublishTaskTool.description] 里的 type 列表 (`Selection.FACTORIES.keys.joinToString`)
     * - [PublishTaskTool] `parametersSchema` enum 数组 (SCHEMA_JSON 模板 `$ENUM` 占位符派生)
     * - [PublishTaskTool.execute] 错误消息里的合法 type 列表 (`Selection.FACTORIES.keys`)
     *
     * 与 [Pasture.assembleHorse] 的 `when (s) is Selection.X` 互补 — 那边走 sealed 模式分派,
     * 编译器强约束; 这边是协议层反序列化入口, 没法走 sealed 路径.
     */
    internal companion object {
        internal val FACTORIES: Map<String, (String) -> Selection> = mapOf(
            Skill.TYPE to Skill::Skill,
            Toolset.TYPE to Toolset::Toolset,
            Subagent.TYPE to Subagent::Subagent,
            Tool.TYPE to Tool::Tool,
        )
    }
}

/** 任务取消. */
internal data class Cancellation(
    internal val taskId: String,
) : PublishEvent

/** 任务进度/结果. event 是 ox 跑出的 [AgentEvent], 终态由 event 自身表达 (Final/Failed). */
internal data class TaskUpdate(
    internal val taskId: String,
    internal val event: AgentEvent,
) : ProgressEvent
```

**为什么三层 sealed interface**：表达"布告栏事件"的总类（订阅者可以 `events.collect { when (it) { is PublishEvent -> ...; is ProgressEvent -> ... } }`），同时按性质分流便于类型检查和未来扩展。`publishEvent` / `progressEvent` 在 API 边界强制方向, 杜绝事件污染。

**`Selection` 与 registry 的关系**：
- 每个 Selection 子类对应 1 个由 TeamAgent 配置的 registry (Skill → SkillRegistry / Toolset → ToolsetRegistry / Subagent → SubagentRegistry / Tool → ToolRegistry)
- `name` 字段在对应 registry 中按精确名查找 — 不同 selection 内的同名 tool 允许共存, 不去重 (不同 registry 下的同名 tool 不一定是同一实现, 由 LLM 按 tool 描述区分)
- Selection 类型在 `publish_task` 工具 JSON 协议里以字符串 `"skill" / "toolset" / "subagent" / "tool"` 表示, 见 § 4.6

### 4.3 `Beast`（牛马 — 任务执行抽象）

牛马 = 团队中的"牲口" — 跑一次任务，通过 `onEvent` 流式回调。两种实现按工作模式区分，但 **persona 由外部传入**（Pasture 预组装），不在 Beast 内部再拼：

- **牛 ([Ox])**: 通用 beast — 像常规 ReActAgent：持 `Persona`（系统提示）+ 4 个 capability registry（`ToolRegistry?` / `SkillRegistry?` / `SubagentRegistry?` / `ToolsetRegistry?`），`agent { }` DSL 把所有 registry 注册进 inner agent，ReAct 循环自由选用。适用"无明确 selection，自己挑工具"的场景。
- **马 ([Horse])**: 专项 beast — 持 **预组装好的 `Persona`** + 派生 `tools: List<Tool>` 列表（没有 registries），inner agent 只用这一组 tool + 一组 persona 跑。适用"boss 已显式选好 selection，专注按 persona + tool 跑"的场景。

两种实现都吃 `Persona`（风格一致），区别在于：
- **Ox 持 4 registry**（完整 raw ReActAgent 装配模型，beast 自己解析）
- **Horse 不持 registry**（pre-load 模式：selection 解析 + persona + tool 列表组装都已在 Pasture 完成）

**关键设计**:
- **persona 由 Pasture 预组装**, Beast 内部不拼接:这是核心解耦 — Beast 不再持有任何 registry/selection 解析逻辑,只接 Pasture 算好的成品.
- Ox 持 4 个 **单** registry(每个类别最多 1 个), 不是 list — 团队内每个 capability 类别只有 1 个 registry.
- Ox 持全类别 registry (tool/skill/subagent/toolset) — 不只是 tool/skill, subagent/toolset 也要能访问 (MCP 经 `McpRegistry` 内部注册到 `ToolsetRegistry`, 不单独算).
- Horse 的 `tools` 列表由 Pasture 根据 selections 一次性解析组装好,直接传给 inner agent; Horse 内部不再做 extract / invoke / describe 这种 wire-up.
- **Tool 与 Capability 解耦** — Tool 不属于 Skill/Toolset/Subagent 这三种 Capability 类别, 但 Beast 也能用到 Tool. Ox 通过 `toolRegistry` 持 Tool 池; Horse 通过 `tools: List<Tool>` 拿到 Pasture 组装好的 Tool 列表(含来自 `tool` selection、Toolset.all()、SkillRegistry.allTools()、Subagent 自己的 tools 等所有来源).

```kotlin
package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry

/**
 * 牛马 = 团队中的"牲口" — 跑一次任务，通过 onEvent 流式回调。
 *
 * 两种实现:
 * - [Ox]: 通用 — 持 4 个单 registry + Persona, agent { } DSL 全注册 (适合"无明确 selection"任务)
 * - [Horse]: 专项 — 持 Persona + 派生的 tools 列表, 不持 registry (pre-load 模式)
 *
 * 每次 [run] 都 new 一个独立的 ReActAgent (独立 memory, 独立 ReAct 循环),
 * 跑完丢弃, 避免状态污染.
 *
 * 不维护 pool, 不限流, 每次任务 new 一个; 未来加限流在 [Pasture] 内部加 Semaphore.
 */
internal interface Beast {
    /** 跑一次任务, AgentEvent 通过 onEvent 逐个回调. */
    internal suspend fun run(task: String, onEvent: suspend (AgentEvent) -> Unit)
}

/**
 * 牛 = 通用 beast. 持 [Persona] + 4 个 capability registry (tool/skill/subagent/toolset).
 * 通过 agent { } DSL 把 registry 注册进 inner agent, 内部 ReAct 循环自由选用.
 *
 * 适用: 任务没有具体 selection (空 selections), 牛自选工具.
 *
 * 每个 registry 参数为可空 — 团队可能没有某一类 capability, 牛按需跳过.
 *
 * v1 实例化由 [Pasture.handleAssignment] 兜底触发 — [Pasture.assembleHorse] 失败
 *   (selections 空 / 含任何 [Subagent] / 找不到 selection) 时经 [error] 抛 [IllegalStateException],
 *   [handleAssignment] catch 后调 [Pasture.buildOx] 返回本通用模式, 让 LLM 在通用循环里
 *   自选工具 / 自主调 subagent (capability invoke).
 * 单一构造点 [Pasture.buildOx] helper, 构造等价 (baseRole + 全部 registry).
 */
internal class Ox internal constructor(
    private val llmProvider: LlmProvider,
    private val persona: Persona,
    private val toolRegistry: ToolRegistry?,
    private val skillRegistry: SkillRegistry?,
    private val subagentRegistry: SubagentRegistry?,
    private val toolsetRegistry: ToolsetRegistry?,
    private val maxIterations: Int,
    private val maxRounds: Int,
) : Beast {
    internal override suspend fun run(task: String, onEvent: suspend (AgentEvent) -> Unit) {
        val inner = agent {
            persona(persona)
            llmProvider(llmProvider)
            memory(InMemoryMemory(), maxRounds)
            toolRegistry?.let { tools(it) }
            skillRegistry?.let { skills(it) }
            subagentRegistry?.let { subagents(it) }   // 需 AgentBuilder DSL 支持
            toolsetRegistry?.let { toolsets(it) }     // 需 AgentBuilder DSL 支持
            maxIterations(maxIterations)
        }
        inner.run(task).collect { onEvent(it) }
    }
}

/**
 * 马 = 专项 beast (pre-load 模式). 持 [Persona] + 派生 tools 列表, 不持 registry.
 * [Persona] 与 tools 列表都已由 [Pasture] 预组装好 — Horse 内部不做 selection 解析 / wire-up,
 * 只用成品跑 ReAct 循环.
 *
 * 适用: boss 已显式选择 selection(s), 马专注按 persona + 选定的 tools 跑任务.
 *
 * tools 列表为空时, inner agent 跑无 tool 的纯 LLM 循环 (适用于纯文本任务).
 */
internal class Horse internal constructor(
    private val llmProvider: LlmProvider,
    private val persona: Persona,
    private val tools: List<Tool> = emptyList(),
    private val maxIterations: Int,
    private val maxRounds: Int,
) : Beast {
    internal override suspend fun run(task: String, onEvent: suspend (AgentEvent) -> Unit) {
        val inner = agent {
            persona(persona)
            llmProvider(llmProvider)
            memory(InMemoryMemory(), maxRounds)
            tools(tools)
            maxIterations(maxIterations)
        }
        inner.run(task).collect { onEvent(it) }
    }
}
```

**Beast 持有什么 / 不持什么**:
- Beast 不持有: 任务状态、memory、agent 实例 — 全部任务级, 跑完即丢
- Ox 持有: 4 个单 capability registry (`toolRegistry` / `skillRegistry` / `subagentRegistry` / `toolsetRegistry`, 每个类别至多 1 个) + 预组装 `Persona`
- Horse 持有: 预组装 `Persona` + 派生 `tools: List<Tool>`(Pasture 组装的成品)
- 每次 run 都 new 一个独立 ReActAgent, 独立 memory, 独立 ReAct 循环

派发逻辑见 [Pasture] (§ 4.4) — v1 多数走 `Horse` 分支 (Boss 派发的 TaskAssignment 都带非空 selections 且不含 subagent); `Ox` 是 [assembleHorse] 失败 (selections 空 / 含任何 subagent / 找不到 selection) 时 `handleAssignment` 兜底退 [Ox].

### 4.4 `Pasture`（牧场 — 任务路由 + 调度）

```kotlin
package io.github.yeyi.agent.team

import io.github.yeyi.agent.AgentEvent
import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.skill.Skill
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.Subagent
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.Toolset
import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 牧场 — 订阅 [BulletinBoard], 接收 [TaskAssignment] 派发到 [Beast] 执行,
 * 把 beast 的 [AgentEvent] 翻译成 [TaskUpdate] 发回.
 *
 * 同时处理 [Cancellation] — 取消对应 taskId 的 running job.
 *
 * 派发策略 (assembleHorse):
 * - [Pasture.assembleHorse] 装配 [Horse]; 失败 (empty / 含 subagent / selection 找不到) 经 [error]
 *   抛 [IllegalStateException], [handleAssignment] 兜底为 [buildOx] (静默降级, 不向 boss 报错).
 * - 含任何 [Selection.Subagent] 时退 Ox 的理由: subagent 是原子能力, 内部 tool 调用链封闭; boss
 *   通过 description 选 subagent 看不到 `subagent.tools` 等私有细节, Horse 装配 (persona 覆盖 +
 *   tools 注入) 会破坏 subagent 闭环. Ox 持全 registry, LLM 在通用循环里通过 capability invoke
 *   调 subagent. 详见 [assembleHorse] KDoc.
 * - 其余 → 按每个 selection 在对应单 registry 解析, 装配到 [Horse]:
 *   - [Selection.Skill]     → `skill.load()` 文本拼入 persona; load 文本中提到 (全词匹配) 的
 *     `SkillRegistry.allTools()` tool 名主动注入 Horse.tools, 让 LLM 能直接调 (绕过
 *     `skill_tool_loader` + `skill_tool_caller` 二段式)
 *   - [Selection.Toolset]   → `toolset.all()` tools 拼入 tools 列表
 *   - [Selection.Tool]      → 单个 tool 直接拼入 tools 列表
 *
 * 持 4 个单 registry (tool/skill/subagent/toolset), 与 [Ox] 一一对应 — 每个 capability 类别最多 1 个 registry.
 * MCP 经 [McpRegistry] 内部注册到 toolsetRegistry, 不单独算.
 *
 * 不维护 beast pool, 不限流. 每次任务 new 一个 [Beast]; 未来加限流在 [Pasture] 内部加 Semaphore.
 */
internal class Pasture internal constructor(
    private val bulletinBoard: BulletinBoard,
    private val llmProvider: LlmProvider,
    private val toolRegistry: ToolRegistry?,
    private val skillRegistry: SkillRegistry?,
    private val subagentRegistry: SubagentRegistry?,
    private val toolsetRegistry: ToolsetRegistry?,
    private val scope: CoroutineScope,
    private val maxIterations: Int,
    private val maxRounds: Int,
) {
    /** Pasture 内部默认 base role — 由 Pasture 决定, 不暴露给 TeamAgentBuilder. */
    private val baseRole: String = "You are a helpful worker. Complete the given task and return the result."

    private val runningJobs: MutableMap<String, Job> = mutableMapOf()
    private val jobsLock: Mutex = Mutex()

    init {
        scope.launch {
            bulletinBoard.publishEvents
                .collect { event ->
                    when (event) {
                        is TaskAssignment -> handleAssignment(event)
                        is Cancellation -> handleCancellation(event)
                    }
                }
        }
    }

    private suspend fun handleAssignment(e: TaskAssignment) {
        // assembleHorse 装配失败时抛 [IllegalStateException] (经 [error] 抛出) —
        // 兜底为 buildOx (静默降级, 不向 boss 报错).
        val beast: Beast = try {
            assembleHorse(e.selections)
        } catch (e: IllegalStateException) {
            buildOx()
        }
        launchBeast(e, beast)
    }

    private fun launchBeast(e: TaskAssignment, beast: Beast) {
        val userInput = if (e.context.isNullOrBlank()) {
            e.task
        } else {
            "${e.context}\n\n${e.task}"
        }
        val job = scope.launch {
            try {
                beast.run(userInput) { event ->
                    bulletinBoard.progressEvent(
                        TaskUpdate(e.taskId, event)
                    )
                }
            } catch (e: Throwable) {
                bulletinBoard.progressEvent(
                    TaskUpdate(
                        taskId = e.taskId,
                        event = AgentEvent.Failed(e),
                    )
                )
                if (e is CancellationException) throw e
            }
        }
        jobsLock.withLock { runningJobs[e.taskId] = job }
        job.invokeOnCompletion { jobsLock.withLock { runningJobs.remove(e.taskId) } }
    }

    private fun handleCancellation(e: Cancellation) {
        scope.launch {
            val job = jobsLock.withLock { runningJobs[e.taskId] }
            job?.cancel()
            // job 不存在 (= 已完成) 静默忽略 — 取消是幂等的
        }
    }

    /**
     * 按 [TaskAssignment.selections] 装配 [Horse]. **失败抛 [IllegalStateException]** (经 [error] 抛出),
     * 由 [handleAssignment] 统一兜底为 [buildOx] (静默降级, 不向 boss 报错).
     *
     * **失败场景** (均抛 [IllegalStateException] 走 Ox 兜底):
     * - `selections.isEmpty()` — 退 Ox 通用模式
     * - `selections.any { it is Selection.Subagent }` — Horse 装配破坏 subagent 闭环
     *   (boss 看不到 `subagent.tools` 等私有细节, persona 覆盖 + tools 注入不完整);
     *   Ox 持全 registry, 让 LLM 在通用循环里通过 capability invoke 调 subagent
     * - 任一 selection 在对应 registry 找不到 — 让 LLM 用全 registry 自选工具补齐
     *
     * v1 subagent 不走 [Horse]: boss 选 subagent 时看不到 `subagent.tools` 等私有细节, 装配 persona + tools
     * 会破坏 subagent 内部 tool 调用链.
     */
    private fun assembleHorse(selections: List<Selection>): Horse {
        if (selections.isEmpty()) {
            error("assembleHorse: selections is empty")
        }
        if (selections.any { it is Selection.Subagent }) {
            error("assembleHorse: selections contains Subagent, fallback to Ox")
        }

        val skillTexts = mutableListOf<String>()
        val tools = mutableListOf<Tool>()

        for (s in selections) {
            when (s) {
                is Selection.Skill -> {
                    val skill: Skill? = skillRegistry?.all()?.firstOrNull { it.name == s.name }
                    if (skill == null) {
                        error("assembleHorse: skill not found: ${s.name}")
                    }
                    val text = skill.load()
                    skillTexts += text
                    // Skill 涉及的 tool 不直接暴露给 LLM (走 skill_tool_loader + skill_tool_caller 二段式),
                    // 但 Horse 已经持有 skill 文本, 不需要二段式: 用 skill.load() 文本与
                    // SkillRegistry.allTools() 做全词匹配, 命中的 tool 主动注入 Horse.tools,
                    // 让 Horse LLM 能直接调.
                    //
                    // 约定: Skill 作者在 load() 文本里把所需 tool 名作为独立词写出, 注册同名 tool
                    // 即可被自动绑定. 匹配规则: `\b<toolName>\b`, 大小写敏感 (与 tool 名严格对齐).
                    skillRegistry?.allTools()?.forEach { tool ->
                        val pattern = Regex("\\b" + Regex.escape(tool.name) + "\\b")
                        if (pattern.containsMatchIn(text)) {
                            tools += tool
                        }
                    }
                }
                is Selection.Toolset -> {
                    val toolset: Toolset? = toolsetRegistry?.all()?.firstOrNull { it.name == s.name }
                    if (toolset == null) {
                        error("assembleHorse: toolset not found: ${s.name}")
                    }
                    tools += toolset.all()
                }
                is Selection.Tool -> {
                    val tool: Tool? = toolRegistry?.all()?.firstOrNull { it.name == s.name }
                    if (tool == null) {
                        error("assembleHorse: tool not found: ${s.name}")
                    }
                    tools += tool
                }
                is Selection.Subagent -> {
                    // unreachable: 上面 `selections.any { it is Selection.Subagent }` 已 throw.
                }
            }
        }

        val persona = Persona(
            buildString {
                append(baseRole)
                skillTexts.forEach { append("\n\n").append(it) }
            }
        )

        // 工具不去重 — 不同 selection 引入的同名 tool 允许共存, 由 LLM 按 tool 描述区分
        return Horse(
            llmProvider = llmProvider,
            persona = persona,
            tools = tools,
            maxIterations = maxIterations,
            maxRounds = maxRounds,
        )
    }

    /**
     * 构造通用 [Ox]. [handleAssignment] 装配失败兜底 + selections 空 / 含 subagent 三处共用 —
     * 抽出避免重复构造, 未来加 Ox 字段一处改即可.
     */
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

**说明**：
- **Pasture 不再感知 capability 运行时类型** — 一切由 `Selection` sealed 子类路由 (`is Selection.Skill` 走 `skill.load()`, `is Selection.Toolset` 走 `toolset.all()`). 类型细节在 `when` 分支内封闭, 不外泄.
- **persona 拼装规则**: Subagent 选中时**整体覆盖** (subagent 是"专项角色", 它的 `load()` 是完整指令); 无 Subagent 时, baseRole + 所有 skill `load()` 文本拼接. Skill 文本**不**带 wrapper, 直接拼接 — Skill 作者自行组织段落.
- **tools 拼装规则**:
  - Toolset 全量 (`toolset.all()`)
  - Subagent 自带 (`sub.tools` 非 null; null = 继承父, 本流程不流入)
  - Skill 自动绑定 — `skill.load()` 文本中以全词出现的 `SkillRegistry.allTools()` tool 名, 主动注入 (绕过二段式调用)
  - 直接 Tool (`Selection.Tool`)
  - 不同 selection 引入的同名 tool 不去重 (允许共存, 由 LLM 按 tool 描述区分)
- **Skill tool 自动绑定的理由**: Skill 涉及的 tool 在 agent 里原本只能通过 `skill_tool_loader` + `skill_tool_caller` 二段式访问, LLM 看不见、不能直接调. 但 Horse 已经持有 skill 文本, 不需要二段式 — 把文本里提到的 tool 名解析后注入 Horse, LLM 就能直接调. 约定 Skill 作者在 `load()` 文本里把所需 tool 名作为独立词写出.
- **`sub.tools == null` 的语义**: 文档上 Subagent.tools 注释说"null = 继承父 agent 工具"; 在 Pasture 流程里父 agent 的 ToolRegistry **不会**自动流入 Horse.tools, 因此 null 等价于"本 task 不追加 Subagent 自己的 tools" — 实际使用上, Subagent 既然接管 persona 就应自带 tools, 选 null + 委派到 Subagent 通常是配置错误, 但不在 v1 校验.
- **找不到 selection 时的处理**: 不向 boss 报错, [assembleHorse] 用 [error] 抛 [IllegalStateException],
  [handleAssignment] 兜底为 [buildOx] 静默降级 (持全 registry 自选工具).
- **subagent 派发**: v1 selections 含任何 subagent → [assembleHorse] 用 [error] 抛 [IllegalStateException],
  [handleAssignment] 兜底为 [buildOx] (Horse 装配破坏 subagent 闭环). LLM 在通用循环里自主调
  subagent (capability invoke). 多 subagent 协调留 v2.

### 4.5 `BossAgent`（boss — 包装 innerAgent + 状态机 + 双事件流分流）

BossAgent 是 team 的核心调度者 — 包装一个内部 `Agent` 作为 innerAgent, 在外层承担三件事:
- 把 `PublishTaskTool` / `CancelTaskTool` 注册到 innerAgent 的 ToolRegistry, 让 boss LLM 可以派活 / 取消
- 订阅 `BulletinBoard` 的 `TaskAssignment` 跟踪自己派出的任务, 订阅 `TaskUpdate` 把异步进度事件合并到下一轮 input
- 维护 `BossState` 状态机 (WAITING / RUNNING / INPUTTING / COLLECTING — 详见 § 7) 决定什么时候触发新的 innerAgent.run(), 什么时候进入 1s 合并窗口

**双事件流分流**:
- User round 流: `run(input)` 触发, 事件喂到 per-round `UserRound.channel`, 调用方从 `run()` 返回的 Flow 拿到 — 这是用户驱动的 round, 含合并 round (有 user pending 时)
- Continuation 流: 终态 TaskUpdate 触发, 事件喂到 `continuationsEmitter` (hot SharedFlow), 调用方从 `continuations` property 订阅 — 这是任务驱动的续轮

两条流互不干扰, boss 不 wrap 事件 (无 `emitEvents` 开关)。状态机通过 `tryTriggerNext()` + `decisionLock` 串行化决策, 避免并发触发多个 round。

**实现细节 (含 `BossState` / `UserRound` / `TaskState` / 完整 BossAgent 类、`tryTriggerNext` 决策逻辑、并发锁)** 详见独立 spec:

> **[§ 1 BossAgent 内部实现](./2026-07-15-team-boss-and-agent-impl.md#1-bossagent-内部实现)**

### 4.6 `PublishTaskTool` 和 `CancelTaskTool`

```kotlin
package io.github.yeyi.agent.team

import io.github.yeyi.agent.tool.Tool
import io.github.yeyi.agent.tool.ToolContext
import io.github.yeyi.agent.tool.ToolExecutionResult
import io.github.yeyi.agent.tool.ToolParameters
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * boss LLM 调用的"派活"工具 — 一次调用可发布**多个无依赖任务**, 每个 task 携带一个或多个 [Selection].
 *
 * - LLM 想同时派发多个无依赖任务 → 一次调本工具, tasks 数组里放多个
 * - LLM 想串行派发有依赖任务 → 分多次调本工具, 每次一个 task (后续发布要等前序 TaskUpdate 触发新一轮 run() 才能发生)
 * - 闲聊/简单问题 → 不调本工具, 直接 final 文本回复
 *
 * **每个 task 必须指定 selections 数组** — 数组中每条 selection 形如 `{"type": "skill|toolset|subagent|tool", "name": "..."}`.
 * 一个 task 可组合多个 selection (e.g., 一个 toolset + 一个直接 tool), 由 [Pasture.assembleHorse] 统一解析.
 */
internal class PublishTaskTool(
    private val bulletinBoard: BulletinBoard,
    private val capabilitiesByType: Map<String, List<NamedCapability>>,  // type → 列出 (name, description)
) : Tool {

    override val name: String = "publish_task"

    override val description: String = buildString {
        // 与 [Selection.FACTORIES] 同源 — 协议层 type 列表的描述文案, 新增 selection 子类时自动同步.
        val typeList = Selection.FACTORIES.keys.joinToString(" | ") { "'$it'" }
        append("""
            Use this to delegate one or more tasks that need external execution to workers (beasts).
            Pass an array of independent tasks to run them concurrently.
            For dependent tasks (one needs the result of another), make multiple calls — the second call will happen
            after the first task's result is available in the next turn.
            For chitchat or simple questions, just respond directly without calling this tool.

            Each task must specify a non-empty 'selections' array. Each selection is {type, name} where type is
            $typeList. A task can carry multiple selections to combine resources
            (e.g. one toolset + one standalone tool). At most one 'subagent' per task — multiple subagents in one
            task are rejected.

            Available workers (grouped by type):
        """.trimIndent())
        capabilitiesByType.forEach { (type, caps) ->
            append("\n  [").append(type).append("]")
            caps.forEach { cap ->
                append("\n    - ").append(cap.name).append(": ").append(cap.description)
            }
        }
    }

    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema(
        SCHEMA_JSON.replace("\$ENUM", Selection.FACTORIES.keys.joinToString("\", \"", "\"", "\""))
    )

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext,
    ): ToolExecutionResult {
        val tasksArray = arguments.jsonObject["tasks"] as? JsonArray
            ?: return ToolExecutionResult.error("Missing 'tasks' array")
        if (tasksArray.isEmpty()) return ToolExecutionResult.error("'tasks' must not be empty")

        // 每个 task 解析 → publish TaskAssignment 到 BulletinBoard.
        // BossAgent 通过订阅 publishEvents 写入自己的 tasks map — 见 [BossAgent.init].
        // Pasture 同时订阅, 启动 beast 跑任务. 单一事件源, 多订阅者.
        val summary = tasksArray.mapIndexed { idx, taskElement ->
            val obj = taskElement.jsonObject
            val task = obj["task"]?.jsonPrimitive?.content
                ?: return ToolExecutionResult.error("Missing 'task' in task #$idx")
            val context = obj["context"]?.takeIf { it !is JsonNull }?.jsonPrimitive?.content
            val selectionsArray = obj["selections"] as? JsonArray
                ?: return ToolExecutionResult.error("Missing 'selections' array in task #$idx")
            if (selectionsArray.isEmpty()) {
                return ToolExecutionResult.error("'selections' must not be empty in task #$idx")
            }

            val selections: List<Selection> = selectionsArray.mapIndexed { sIdx, selElement ->
                val selObj = selElement.jsonObject
                val type = selObj["type"]?.jsonPrimitive?.content
                    ?: return ToolExecutionResult.error("Missing 'type' in task #$idx selection #$sIdx")
                val name = selObj["name"]?.jsonPrimitive?.content
                    ?: return ToolExecutionResult.error("Missing 'name' in task #$idx selection #$sIdx")
                Selection.FACTORIES[type]?.invoke(name)
                    ?: return ToolExecutionResult.error(
                        "Unknown selection type '$type' in task #$idx selection #$sIdx — must be one of ${Selection.FACTORIES.keys}"
                    )
            }

            val taskId = UUID.randomUUID().toString()
            bulletinBoard.publishEvent(
                TaskAssignment(taskId, selections, task, context)
            )

            val selStr = selections.joinToString("+") { sel -> "${sel.type}(${sel.name})" }
            "- $taskId → $selStr"
        }
        return ToolExecutionResult("Assigned ${summary.size} task(s):\n${summary.joinToString("\n")}")
    }

    private companion object {
        /**
         * publish_task JSON schema 模板.
         * `\$ENUM` 占位符在 [PublishTaskTool] 构造时由 [Selection.FACTORIES] 派生,
         * 与协议层 type 列表同源 — 新增 [Selection] 子类时, schema enum 自动同步.
         */
        private val SCHEMA_JSON: String = """
            {
              "type": "object",
              "properties": {
                "tasks": {
                  "type": "array",
                  "minItems": 1,
                  "items": {
                    "type": "object",
                    "properties": {
                      "selections": {
                        "type": "array",
                        "minItems": 1,
                        "items": {
                          "type": "object",
                          "properties": {
                            "type": {
                              "type": "string",
                              "enum": [$ENUM],
                              "description": "Type of the resource to load — 'skill' for instruction text, 'toolset' for a group of tools, 'subagent' for a specialized agent (at most one per task), 'tool' for a single tool."
                            },
                            "name": {
                              "type": "string",
                              "description": "Name of the resource. Must match one of the available workers listed in the tool description."
                            }
                          },
                          "required": ["type", "name"]
                        },
                        "description": "Array of selections to combine for this task. Each task must have at least one."
                      },
                      "task": {
                        "type": "string",
                        "description": "Core instruction for the worker — what to do. Use 'context' for background info; do not embed long context into the task itself."
                      },
                      "context": {
                        "type": "string",
                        "description": "Optional background info needed to complete the task — user preferences, prior task results, environment state, etc. Prepended to the task (blank-line separated) in the worker's input. Omit if the task is self-contained."
                      }
                    },
                    "required": ["selections", "task"]
                  },
                  "description": "Array of independent tasks to assign concurrently. For dependent tasks, make multiple calls."
                }
              },
              "required": ["tasks"]
            }
        """.trimIndent()
    }
}

/** 工具描述用的轻量记录 — 不暴露 Capability 内部细节. */
internal data class NamedCapability(val name: String, val description: String)

internal class CancelTaskTool(
    private val bulletinBoard: BulletinBoard,
) : Tool {
    override val name: String = "cancel_task"
    override val description: String = "Cancel a previously published task by its task_id."
    override val parametersSchema: ToolParameters = ToolParameters.JsonSchema("""
        {
          "type": "object",
          "properties": {
            "task_id": { "type": "string", "description": "The task_id returned by publish_task" }
          },
          "required": ["task_id"]
        }
    """.trimIndent())

    override suspend fun execute(
        arguments: JsonElement,
        context: ToolContext,
    ): ToolExecutionResult {
        val taskId = arguments.jsonObject["task_id"]?.jsonPrimitive?.content
            ?: return ToolExecutionResult.error("Missing 'task_id'")
        // 取消意图通过 Cancellation 事件发到 BulletinBoard, 由 Pasture 处理 (取消 beast job).
        // BossAgent 不需要直接感知 — 后续 TaskUpdate(Failed(CancellationException)) 会通过 progressEvents 回来.
        bulletinBoard.publishEvent(Cancellation(taskId))
        return ToolExecutionResult("Task $taskId cancellation requested")
    }
}
```

### 4.7 `TeamAgent`（容器 — 单一装配点）

TeamAgent 是 team 唯一对外 API 表面 — 自身实现 `Agent` 接口, 把 `run` / `runStream` 转交给内部 `BossAgent`. 内部 boss / pasture / bulletinBoard 全部私有, 外部通过 `team.run` / `team.runStream` / `team.continuations` / `team.state` / `team.inputting` / `team.shutdown` 与之交互.

**事件流转发**:
- `team.run(input).collect { }` — 拿到 user round 流 (单次 round 的事件, 含合并 round)
- `team.continuations.collect { }` — 拿到续轮流 (任务驱动, 订阅一次即可收所有续轮)
- `team.state.collect { }` — boss 状态

构造由 `TeamAgentBuilder` 完成 — 用户通过 `teamAgent { }` DSL 配置; builder 内部装配 bulletinBoard / pasture / boss, 把 teamScope 注入 boss, 然后返回 [TeamAgent].

**TeamAgent 类本身的实现** (薄壳 — 仅持有 boss 引用 + team 协程作用域, 转发 run/continuations/state/inputting/shutdown) 详见独立 spec:

> **[§ 2 TeamAgent 公开容器](./2026-07-15-team-boss-and-agent-impl.md#2-teamagent-公开容器)**

下面列出 TeamAgentBuilder + `teamAgent { }` DSL 的完整实现 — 这是主 spec 范围内:

```kotlin
package io.github.yeyi.agent.team

import io.github.yeyi.agent.Persona
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.llm.LlmProvider
import io.github.yeyi.agent.memory.Memory
import io.github.yeyi.agent.mcp.McpRegistry
import io.github.yeyi.agent.skill.SkillRegistry
import io.github.yeyi.agent.subagent.SubagentRegistry
import io.github.yeyi.agent.tool.ToolRegistry
import io.github.yeyi.agent.toolset.ToolsetRegistry
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

public class TeamAgentBuilder internal constructor() {
    // 配置字段 — 方法式, 不提供默认 builder (用户必须显式调用)
    private var memory0: Memory? = null
    private var llmProvider0: LlmProvider? = null
    private var maxIterations0: Int = 20
    private var maxRounds0: Int = 20

    // 每个 capability 类别最多 1 个 registry
    private var delegatedToolRegistry0: ToolRegistry? = null
    private var quickToolRegistry0: ToolRegistry? = null
    private var skillRegistry0: SkillRegistry? = null
    private var subagentRegistry0: SubagentRegistry? = null
    private var toolsetRegistry0: ToolsetRegistry? = null

    /** 用户注入的 boss persona — 由 [persona] DSL 设置. */
    private var bossPersona0: Persona? = null

    /**
     * 框架 boss role — 由 framework 强制注入,user 配置的 persona 必须让出 [Persona.role] 字段
     * (其 role 必须为空字符串,否则 [persona] DSL 抛 [IllegalArgumentException]).
     * boss 必须知道自己能闲聊 / 用 quick tools / 派活 (`publish_task`).
     */
    private val baseRole: String = """
        You are the boss of a team. You can:
        1. Respond to chitchat directly.
        2. Answer simple questions using your quick tools.
        3. Delegate complex tasks to workers (beast) by calling publish_task — see the tool description
           for available workers and the selection format.
    """.trimIndent()

    public fun memory(value: Memory) { memory0 = value }
    public fun llmProvider(value: LlmProvider) { llmProvider0 = value }
    public fun maxIterations(value: Int) { maxIterations0 = value }
    public fun maxRounds(value: Int) { maxRounds0 = value }

    /**
     * 注册 tool 集合. [quick] 决定用途:
     * - `quick = true`  → boss 自己可快速调的工具, 合并进 innerAgent 的 ToolRegistry (LLM 可见可调; 同步阻塞当前 run)
     * - `quick = false` (默认) → tool 池, boss 通过 [Selection.Tool] 选用, pasture 解析注入 Horse
     *
     * "快速"强调: 调用走 boss 同步路径, 无 beast 派发开销, 执行耗时短.
     *
     * 一次性赋值: 用户为 quick 和非 quick **各**调一次, 分别注册一个 ToolRegistry. 不累加.
     * 同一 [quick] 值多次调用后赢.
     */
    public fun tools(registry: ToolRegistry, quick: Boolean = false) {
        if (quick) {
            quickToolRegistry0 = registry
        } else {
            delegatedToolRegistry0 = registry
        }
    }

    /** skill registry — boss 看到菜单, pasture 解析注入 Horse persona. */
    public fun skills(registry: SkillRegistry) {
        skillRegistry0 = registry
    }

    /** subagent registry — boss 看到菜单; 含任何 subagent 的派活退 Ox, Ox 在通用循环里 capability invoke 调 subagent. */
    public fun subagents(registry: SubagentRegistry) {
        subagentRegistry0 = registry
    }

    /** toolset registry — boss 看到菜单, pasture 解析注入 Horse tools. */
    public fun toolsets(registry: ToolsetRegistry) {
        toolsetRegistry0 = registry
    }

    /**
     * 注入 boss persona — 与 [AgentBuilder.persona] API 风格对齐 (`persona(Persona)`).
     *
     * **role 是 framework 内部保留字段**: user 构造 [Persona] 时 [Persona.role]
     * 必须为空字符串,否则 throw [IllegalArgumentException]. framework 用 [baseRole]
     * 作为最终 boss role — boss 必须知道自己能闲聊 / 用 quick tools / 派活 (`publish_task`).
     *
     * user 可通过 [Persona.personality] / [Persona.domain] / [Persona.constraints] /
     * [Persona.extra] 设置偏好 / 状态 / 背景 — 这些字段的内容在最终 persona 渲染时
     * 作为 raw 文本段追加在 [baseRole] 之后.
     *
     * 不调用本方法时 framework 用默认 `Persona(baseRole)`.
     */
    public fun persona(persona: Persona) {
        require(persona.role.isBlank()) {
            "Persona.role is reserved by the team framework — must be empty string. " +
                "Use personality / domain / constraints / extra to customize agent persona."
        }
        bossPersona0 = persona
    }

    /**
     * MCP 一站式接入 — 接受一个 [McpRegistry], 内部 Toolset 已经注册到 user 的 ToolsetRegistry.
     * 本方法**只做 DSL 接口完整性**, 不额外存引用.
     *
     * 用法: 在 [McpRegistry] 构造时传入 toolsetRegistry, 然后用 `toolsets(toolsetRegistry)` +
     * `mcps(mcpRegistry)` 挂到 builder 上 (后者仅为 DSL 完整性); 调用方在 build 之后持有
     * McpRegistry 引用调用 `unregisterAll()` 关闭连接.
     */
    public fun mcps(registry: McpRegistry) {
        @Suppress("UNUSED_PARAMETER") registry
    }

    public fun build(): TeamAgent {
        val llm = requireNotNull(llmProvider0) { "llmProvider must be set" }
        val mem = requireNotNull(memory0) { "memory must be set" }

        val bulletinBoard = BulletinBoard()

        // 团队统一协程作用域 — boss 和 pasture 共享同一个 SupervisorJob.
        // 一个子树失败不会级联取消另一个; [TeamAgent.shutdown] 取消整个 scope.
        val teamScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val pasture = Pasture(
            bulletinBoard = bulletinBoard,
            llmProvider = llm,
            toolRegistry = delegatedToolRegistry0,
            skillRegistry = skillRegistry0,
            subagentRegistry = subagentRegistry0,
            toolsetRegistry = toolsetRegistry0,
            scope = teamScope,
            maxIterations = maxIterations0,
            maxRounds = maxRounds0,
        )

        val boss = buildBoss(
            memory = mem,
            llmProvider = llm,
            bulletinBoard = bulletinBoard,
            delegatedToolRegistry = delegatedToolRegistry0,
            skillRegistry = skillRegistry0,
            subagentRegistry = subagentRegistry0,
            toolsetRegistry = toolsetRegistry0,
            quickToolRegistry = quickToolRegistry0,
            maxIterations = maxIterations0,
            maxRounds = maxRounds0,
            scope = teamScope,
        )

        return TeamAgent(boss, teamScope)
    }

    private fun buildBoss(
        memory: Memory,
        llmProvider: LlmProvider,
        bulletinBoard: BulletinBoard,
        delegatedToolRegistry: ToolRegistry?,
        skillRegistry: SkillRegistry?,
        subagentRegistry: SubagentRegistry?,
        toolsetRegistry: ToolsetRegistry?,
        quickToolRegistry: ToolRegistry?,
        maxIterations: Int,
        maxRounds: Int,
        scope: CoroutineScope,
    ): BossAgent {
        // 按 type 分组的 capability 列表 — 用于 PublishTaskTool 菜单渲染
        // 4 类都列出 (含 tool — Selection.Tool 也走菜单); 用 NamedCapability 暴露 (name, description) 不外泄内部细节
        val capabilitiesByType: Map<String, List<NamedCapability>> = buildMap {
            delegatedToolRegistry?.let { reg -> put("tool", reg.all().map { NamedCapability(it.name, it.description) }) }
            skillRegistry?.let { reg -> put("skill", reg.all().map { NamedCapability(it.name, it.description) }) }
            subagentRegistry?.let { reg -> put("subagent", reg.all().map { NamedCapability(it.name, it.description) }) }
            toolsetRegistry?.let { reg -> put("toolset", reg.all().map { NamedCapability(it.name, it.description) }) }
        }

        // PublishTaskTool / CancelTaskTool 都是无状态事件源 — 只 publish 事件到 BulletinBoard.
        // BossAgent 通过订阅 publishEvents 记录任务 (写自己的 tasks map),
        // Pasture 通过订阅 publishEvents 启动 beast. 单一事件源, 多订阅者.
        val publishTask = PublishTaskTool(
            bulletinBoard = bulletinBoard,
            capabilitiesByType = capabilitiesByType,
        )
        val cancelTask = CancelTaskTool(
            bulletinBoard = bulletinBoard,
        )

        // boss innerAgent 的最终 ToolRegistry: 立即响应工具 (可选) + 派活工具 (publish_task / cancel_task)
        val bossToolRegistry = quickToolRegistry.apply {
            register(publishTask)
            register(cancelTask)
        }

        val persona = buildPersona()

        val innerAgent = agent {
            persona(persona)
            llmProvider(llmProvider)
            memory(memory, maxRounds)
            tools(bossToolRegistry)
            maxIterations(maxIterations)
        }

        return BossAgent(
            innerAgent = innerAgent,
            bulletinBoard = bulletinBoard,
            scope = scope,
        )
    }

    private fun buildPersona(): Persona {
        val extra = bossPersona0
        return if (extra == null) {
            Persona(baseRole)
        } else {
            // 框架 base role 永远是 Persona.role; extra 内容以 raw 文本段形式作为 label-less extra 追加.
            // 最终渲染: baseRole + "\n\n" + extra.toString()
            Persona(baseRole).extra(extra.toString())
        }
    }
}

public fun teamAgent(block: TeamAgentBuilder.() -> Unit): TeamAgent =
    TeamAgentBuilder().apply(block).build()
```

**说明**：
- **builder 全部用方法配置** — 不提供 `var` 字段, 用户必须显式调用 `memory(...)` / `llmProvider(...)` / `maxIterations(...)` / `maxRounds(...)`. `llmProvider` / `memory` 是必填项, `build()` 时检查; `maxIterations` / `maxRounds` 默认 20, **作用于 boss + beast 双方** (`maxIterations` 防止 LLM 死循环, `maxRounds` 防止 memory 无限增长).
- **方法名统一复数**: `skills` / `subagents` / `toolsets` / `tools` / `mcps` — 与 [AgentBuilder] DSL 的命名风格保持一致 (skills/subagents/toolsets).
- **`tools(registry, quick)` 单一形态** — `quick = false` (默认) 是 tool 池, boss 通过 `Selection.Tool` 选用, pasture 解析注入 Horse; `quick = true` 是 boss 自己快速可调的工具 (同步阻塞, 执行耗时短), 注册到 innerAgent 的 ToolRegistry. **一次性赋值** — 用户为 quick 和非 quick **各**注册一个 ToolRegistry. 同一 `quick` 值多次调用后赢 (last-write-wins).
- **每个 capability 类别最多 1 个 registry**: `tools(registry)` / `skills(registry)` / `subagents(registry)` / `toolsets(registry)`. 多次设置后赢 (last-write-wins) — 不抛异常, 遵循 "good enough" 原则.
- **`mcps(McpRegistry)` 是 no-op** — MCP 内部的 Toolset 已在构造 `McpRegistry(toolsetRegistry, ...)` 时注册到用户的 `ToolsetRegistry` (经 `toolsets()` 接入); `mcps()` 仅做 DSL 接口完整性, 不额外存引用. 调用方在 `build` 之后持有 `McpRegistry` 引用调用 `unregisterAll()` 关闭连接.
- 立即响应工具走 `bossToolRegistry`, boss LLM 可直接调
- 派活工具 (`publish_task` / `cancel_task`) 是内部注入的, 外部不感知
- **菜单含 4 类** (tool / skill / toolset / subagent), LLM 在 `publish_task` 里任意组合 selection

---

## 5. team 整体配置

team 是一个整体容器 — registry 是 team 自己的配置, 单一来源. `teamAgent { }` DSL 里 4 个 setXxx 一次性配置, TeamAgent 内部分配给 boss (菜单) 和 pasture (路由). boss 看到的和 pasture 路由的是同一 registry 实例.

### 5.1 配置示例

```kotlin
val skillRegistry = SkillRegistry().apply { register(searchSkill) }
val subagentRegistry = SubagentRegistry().apply { register(reviewer) }
val toolsetRegistry = ToolsetRegistry().apply { register(searchToolset) }
// MCP 通过 McpRegistry 内部注册到 toolsetRegistry:
val mcpRegistry = McpRegistry(toolsetRegistry, clientInfo).apply {
    register(webMcpServer)
}

val team = teamAgent {
    // 一次性配置, 每个 capability 类别最多 1 个 registry
    skills(skillRegistry)
    subagents(subagentRegistry)
    toolsets(toolsetRegistry)
    // MCP 一站式接入 (no-op, 仅 DSL 完整性; MCP 已在 toolsetRegistry 里)
    mcps(mcpRegistry)
}
```

**配置外部一次的好处**:
- boss 看到的"能力菜单" = pasture 路由的能力 = 同一 registry 实例
- 派活的"承诺"和执行的"能力"对得上 — boss LLM 派到不存在的 selection 时 [assembleHorse] 抛 [IllegalStateException], [handleAssignment] 兜底为 [buildOx] 静默降级, 让 LLM 用全 registry 自选工具补齐
- 调用方只配置一次, TeamAgent 内部分配 — 不要求调用方管理"boss 视角"和"pasture 视角"两份配置

**关于 McpServer**：`McpRegistry` 不是 `CapabilityRegistry`，它内部把每个 `Mcp` 实例注册到目标 `ToolsetRegistry`（复用 toolset 框架的 `load_toolset` / `sub_tool_delegate`）。所以 MCP 的工具集天然属于该 `ToolsetRegistry`，对 LLM 而言 `Selection.Toolset(name)` 路由，description 文案上区分（如 "Web search (via MCP)"），路由和解析全部走 toolset 分支。

### 5.2 装配规则（取代旧 Horse wire-up）

Pre-load 模式下, 全部装配逻辑由 [Pasture.assembleHorse] 一次性完成 (§ 4.4), [Horse] / [Ox] 内部不再做 wire-up. 本节列出规则总览, 便于查阅.

**Persona 拼装**:
| 选中情况 | persona 来源 |
|---|---|
| 含任何 Subagent | (不适用 — 全部 selection 丢弃, 退 [Ox] 用 baseRole, 见 § 4.4) |
| 无 Subagent, 有 Skill | `baseRole` + 所有选中 `skill.load()` 文本拼接 (Skill 间用 `\n\n` 分隔) |
| 仅 Tool / Toolset | `baseRole` (无 skill 文本追加) |
| 0 selections (Ox) | `baseRole` |

**Tools 拼装**:
| 来源 | 拼入条件 |
|---|---|
| `Selection.Toolset(name)` | `toolset.all()` 全量 |
| `Selection.Subagent(name)` | `sub.tools` 非 null 时拼入; null = 继承父, 本流程不流入 |
| `Selection.Skill(name)` | `skill.load()` 文本中以全词出现的 `SkillRegistry.allTools()` tool 名, 自动注入 |
| `Selection.Tool(name)` | 该 tool 本身 |
| (无去重) | 不同 selection 引入的同名 tool 允许共存, 由 LLM 按 tool 描述区分 |

**重要说明**:
- Skill 文本里提到的 tool 名 → 自动绑定到 Horse.tools, LLM 能直接调 (绕过 `skill_tool_loader` + `skill_tool_caller` 二段式). 这是 Skill 在 Horse 上下文里的特殊处理 — Skill 自身架构保持不变.
- v1 **subagent 不走 [Horse]**: boss 选 subagent 时看不到 `subagent.tools` 等私有细节, Horse 装配会破坏 subagent 闭环. 任何含 subagent 的 selection → 全部丢弃, 退到 [Ox] 让 LLM 在通用循环里通过 capability invoke 调 subagent. 多 subagent 协调留 v2.

### 5.3 装配匹配规则

`Pasture.assembleHorse(selections)` 的匹配逻辑 (对应 § 4.4 内的 `assembleHorse` 函数; 任一检查失败经 [error] 抛 [IllegalStateException], [handleAssignment] 兜底为 [buildOx]):

1. `selections.isEmpty()` → `error("assembleHorse: selections is empty")`
2. `selections.any { it is Selection.Subagent }` → `error("assembleHorse: selections contains Subagent, fallback to Ox")`
3. 遍历每个 selection, 按 sealed 子类路由 (此处**不会**有 Subagent):
   - `is Selection.Skill`    → `skillRegistry?.all()?.firstOrNull { it.name == s.name }`, 找不到 → `error("assembleHorse: skill not found: ${s.name}")`
   - `is Selection.Toolset`  → `toolsetRegistry?.all()?.firstOrNull { it.name == s.name }`, 找不到 → `error("assembleHorse: toolset not found: ${s.name}")`
   - `is Selection.Tool`     → `toolRegistry?.all()?.firstOrNull { it.name == s.name }`, 找不到 → `error("assembleHorse: tool not found: ${s.name}")`
4. 全找到 → 拼 persona + 拼 tools (不去重) → [Horse]

**示例**:
- `assembleHorse(t1, [Selection.Skill("web_search")])` → 在 `skillRegistry` 里找 `name == "web_search"` 的 Skill, `skill.load()` 进 persona, 文本里提到的 tool 自动注入 tools, 返回 [Horse]
- `assembleHorse(t2, [Selection.Toolset("weather"), Selection.Tool("get_time")])` → toolset 全部 tool + `get_time` 一起注入 tools, persona 只有 baseRole, 返回 [Horse]
- `assembleHorse(t3, [Selection.Subagent("reviewer")])` → 含 subagent, 抛 [IllegalStateException], [handleAssignment] 兜底为 [buildOx]
- `assembleHorse(t4, [Selection.Skill("nonexistent")])` → 找不到 skill, 抛 [IllegalStateException], [handleAssignment] 兜底为 [buildOx]

### 5.4 tool 分类 DSL

`tools(registry, quick)` 单一方法, `quick` 参数区分两条路径:

```kotlin
teamAgent {
    // 快速响应工具 — boss LLM 可直接调 (同步阻塞, 执行耗时短; 合并到 innerAgent 的 ToolRegistry)
    tools(quickToolRegistry, quick = true)

    // tool 池 — boss 通过 publish_task(selections: [{type: "tool", name: "..."}]) 选用
    tools(toolRegistry)
}
```

**为什么 `quick` 用 ToolRegistry 而非单 Tool**:
- 快速响应工具通常成组出现 (如: `getCurrentTime` + `calculator`), 一次性注册更直观
- 与"委派"路径统一为 `tools(registry)`, 单一 API; `quick` 只决定合并目的地
- 用户为 quick 和非 quick **各**注册一个 ToolRegistry, 一次性赋值, 后赢原则

**为什么不再区分 "快速 / 委派" 单 Tool**:
- boss 看到一个普通 Tool 没有任何意义 — boss 不直接调这些 tool, 调了也绕过了 beast 的封装
- boss 应当通过 `publish_task` 把所有非平凡的工作委派出去; 想用某个 tool 就 `Selection.Tool(name)` 选
- 快速响应工具走 `quickToolRegistry`, 经 `tools(..., quick = true)` 注入; 非快速响应的工具走 `tools(toolRegistry)` 池, 再经 `publish_task` 选用

---

## 6. 消息流（端到端）

**双事件流说明**: 整个 team 暴露两条事件流, 调用方按需订阅:

| 流 | 何时收到事件 | API |
|---|---|---|
| User round 流 | 每次 `run(input)` 触发的 round (含合并 round) | `team.run("...").collect { }` |
| Continuation 流 | 任务结果触发的续轮 (无 user input 参与) | `team.continuations.collect { }` |

User 调 `run()` 拿到一个 Flow, 内部是 per-round Channel, 该 round 跑完 Flow 完成。**派活后该轮也正常结束** (boss LLM final 文本后 `run()` 返回的 Flow 终止) — 任务在后台继续, 续轮事件从 `continuations` 走。

### 6.1 正常派活（单 selection 单 task）

```
1. 用户 sendInput("帮我搜下 Kotlin 协程")
2. BossAgent.run() 启动, state → RUNNING, innerAgent.run(input)
3. boss LLM 决定调 publish_task(tasks=[
     {selections: [{type: "skill", name: "web_search"}], task: "Kotlin 协程"}
   ])
4. PublishTaskTool.execute: 生成 taskId, publish TaskAssignment(taskId, [Skill("web_search")], "Kotlin 协程")
   BossAgent 通过订阅 publishEvents 写入自己的 tasks[taskId] = TaskState(selections=[Skill("web_search")], task="Kotlin 协程")
5. PublishTaskTool 返回 "Assigned 1 task(s):\n- xxx → skill(web_search)"
6. innerAgent.memory 记录 ToolResult
7. innerAgent 继续 LLM 循环: 决定 final 文本回复用户
8. emit Final("已让 web_search 助手去查,稍等")
9. BossAgent.run() 完成: round.channel.close()
   → user 拿到的 Flow 自然结束, 任务仍在后台跑
   → state → WAITING, tryTriggerNext() 决策 (没 terminals, idle)

10. Pasture.handleAssignment 接到 TaskAssignment
11. assembleHorse([Skill("web_search")]) →
    - skillRegistry.all() 找 name="web_search" → 找到 WeatherSkill
    - skill.load() 文本 "use get_weather tool..."
    - skill 文本里全词匹配 SkillRegistry.allTools() → 命中 get_weather tool, 加入 tools
    - 返回 Horse(persona=baseRole+skillText, tools=[get_weather])
12. 启动 coroutine job:
      beast.run(task) { event -> publish TaskUpdate(...) }
13. runningJobs[taskId] = job

14. Horse 内部: 启动 ReActAgent(persona=baseRole+skillText, tools=[get_weather]) 跑 ReAct 循环
15. Horse LLM 看到 persona 说"use get_weather" + schema, 直接调 get_weather (不再走 skill_tool_loader 二段式)
16. 逐个 AgentEvent (TextDelta / ToolCallStart / ToolCallEnd / Final) 触发 onEvent 回调
17. Pasture 转 TaskUpdate, publish 到 bulletinBoard

18. BossAgent.handleTaskUpdate 接到 TaskUpdate (Final)
19. tasksLock.withLock: tasks[taskId].events += event, terminal=true
20. pendingResultEvents.trySend(update)
21. state=WAITING → tryTriggerNext()
22. decisionLock.withLock: 看到 hasTerminals, hasActive=false → 启动 runContinuationRound()
23. state → RUNNING, innerAgent.run("任务 X 已 Final: 结果是 Y")
24. boss LLM 决定 final 文本回复用户
25. emit Final("搜索完成,结果如下: ...")
26. BossAgent.runContinuationRound() finally: state → WAITING
    → continuationsEmitter.emit(event) 喂到续轮流
    → 订阅 team.continuations 的 UI 收到该轮事件
```

### 6.2 多 selection 单 task (toolset + 直接 tool 组合)

```
1. 用户 sendInput("查北京天气,顺便看下现在几点了")
2. boss LLM 决定调 publish_task(tasks=[
     {selections: [
        {type: "toolset", name: "weather"},
        {type: "tool", name: "get_time"}
      ], task: "查北京天气 + 现在时间"}
   ])
3. PublishTaskTool.execute: 解析 selections 数组, publish TaskAssignment(taskId, [Toolset("weather"), Tool("get_time")], task)
4. boss LLM 决定 final 文本 "已派一个任务,稍等"
5. BossAgent.run() 完成, state → WAITING

6. Pasture.assembleHorse([Toolset("weather"), Tool("get_time")]) →
   - toolsetRegistry.all() 找 "weather" → 找到, 取出 weather.all() (含 get_weather / get_forecast)
   - toolRegistry.all() 找 "get_time" → 找到, 加入 tools
   - tools = [get_weather, get_forecast, get_time] (不去重)
   - persona = baseRole (无 skill / subagent)
   - 返回 Horse(persona=baseRole, tools=[get_weather, get_forecast, get_time])
7. Horse 启动 ReAct 循环, LLM 看到 3 个 tool 都能用
8. ... 后续回流同 6.1
```

### 6.3 并发派活（一次 publish_task 多个 task）

```
1. 用户 sendInput("查北京天气,同时发邮件给 boss@x.com")
2. boss LLM 决定调 publish_task(tasks=[
     {selections: [{type: "toolset", name: "weather"}], task: "查北京天气"},
     {selections: [{type: "toolset", name: "email_sender"}], task: "发邮件给 boss@x.com"}
   ])
3. PublishTaskTool.execute: 生成两个 taskId, publish 两个 TaskAssignment; BossAgent 通过 publishEvents 订阅记录两个 TaskState
4. PublishTaskTool 返回 "Assigned 2 task(s):\n- t1 → toolset(weather)\n- t2 → toolset(email_sender)"
5. boss LLM 决定 final 文本 "已派两个任务,稍等"
6. BossAgent.run() 完成, state → WAITING

7. Pasture 接到两个 TaskAssignment, 启动两个独立 coroutine job 并发跑
8. 每个 job 跑出 AgentEvent 后转 TaskUpdate, 独立 publish
9. BossAgent 收到两个 TaskUpdate, 累加到各自的 tasks[taskId].events
10. 第一个 TaskUpdate 触发新 run(), LLM 看到"任务 A 已 Final, 任务 B 还在跑" → 决定不 final, 继续等
11. 第二个 TaskUpdate 触发又一轮 run(), LLM 看到两个都完成 → final 汇报用户
```

### 6.4 串行依赖（多次 publish_task, 每次 1 个 task）

```
1. 用户 sendInput("查北京天气,然后基于结果发邮件给 boss@x.com")
2. boss LLM 决定调 publish_task(tasks=[
     {selections: [{type: "toolset", name: "weather"}], task: "查北京天气"}
   ])  // 第一次
3. boss LLM 决定 final 文本 "已查天气,稍等结果" (不等任务完成)
4. BossAgent.run() 完成, state → WAITING
5. TaskUpdate(Final_weather) 回来, boss 状态机触发新 run()
6. innerAgent.run() 看到 "任务 X (toolset(weather)) 已 Final: 天气是晴 25 度"
7. boss LLM 决定调 publish_task(tasks=[
     {selections: [{type: "toolset", name: "email_sender"}], task: "天气晴 25 度,发邮件给 boss@x.com"}
   ])  // 第二次
8. boss LLM 决定 final 文本 "邮件已派发"
9. ... 后续同正常派活
```

### 6.5 派活含 subagent (退 Ox, LLM 自主调 subagent)

```
1. 用户 sendInput("帮我审一下这段代码")
2. boss LLM 决定调 publish_task(tasks=[
     {selections: [{type: "subagent", name: "reviewer"}], task: "审下面这段代码: ..."}
   ])
3. PublishTaskTool.execute → publish TaskAssignment(selections=[Subagent("reviewer")], ...)
4. boss LLM 决定 final 文本 "已派 reviewer 审代码"
5. BossAgent.run() 完成

6. Pasture.handleAssignment 调 assembleHorse([Subagent("reviewer")]) →
   - selections.any { it is Selection.Subagent } = true
   - `error("assembleHorse: selections contains Subagent, fallback to Ox")` 抛 [IllegalStateException]
7. handleAssignment catch IllegalStateException → fallback buildOx(), launchBeast
8. Ox 持全 registry (含 subagentRegistry), 启动 ReAct 循环
9. Ox LLM 看到 subagent description (reviewer = 代码审查), 决定调 subagent capability
10. Ox LLM 通过 capability invoke 调 reviewer.run(task="审下面这段代码: ...", context=Ox AgentContext)
11. reviewer 启动自己的 ReAct 循环, 完成审查 → 返回结果
12. Ox 拿到结果, emit Final
13. ... 后续回流同 6.1
```

### 6.6 派活 selection 找不到 (assembleHorse 抛异常 → handleAssignment fallback Ox)

```
1. 用户 sendInput("查北京天气") (假设 weather toolset 没注册)
2. boss LLM 决定调 publish_task(tasks=[
     {selections: [{type: "toolset", name: "weather"}], task: "查北京天气"}
   ])
3. PublishTaskTool.execute → publish TaskAssignment(selections=[Toolset("weather")], ...)

4. Pasture.handleAssignment 调 assembleHorse([Toolset("weather")]) →
   - toolsetRegistry?.all() 为 null (未注册) 或不包含 "weather"
   - `error("assembleHorse: toolset not found: weather")` 抛 [IllegalStateException]
5. handleAssignment catch IllegalStateException → fallback buildOx(), launchBeast
6. Ox 持全 registry, 在通用循环里尝试 (toolsetRegistry 也没 weather → 失败/放弃)
   或者 weather 实际有别的 toolset 能用 → LLM 自主找到并完成
7. emit Final
```

**关键区别**:
- 6.3 一次 publish_task 多个 task = LLM 显式表达"同时无依赖", 框架不特殊处理 (多 job 并发自然支持)
- 6.4 多次 publish_task = LLM 显式表达"串行依赖", 靠 boss 状态机 (TaskUpdate 触发新一轮 run) 自然支持
- 6.2 一个 task 多 selection = LLM 显式表达"组合资源", 由 Pasture.assembleHorse 一次性解析
- 6.6 派活 selection 找不到 = assembleHorse 经 [error] 抛 [IllegalStateException] → handleAssignment fallback Ox, 静默降级, 不向 boss 报错
- 框架不引入"任务编排图"等额外抽象 — LLM 自主决策何时用哪种调用方式

---

## 7. 状态机

### 7.1 四态定义

| 状态 | 含义 | 进入条件 | 退出条件 |
|---|---|---|---|
| `WAITING` | idle, 等待外部输入 (用户或终态 TaskUpdate) | 构造时 / round finally / COLLECTING 1s 等待结束 | user `run()` / 终态 TaskUpdate 触发 `tryTriggerNext` |
| `RUNNING` | round 正在跑 (user-driven 或 task-driven 续轮) | `runUserRound` / `runContinuationRound` 起始 | round finally → WAITING |
| `INPUTTING` | user 在 WAITING 状态下开始打字 (UI 信号) | `inputting(true)` 当 state = WAITING | `inputting(false)` / user 提交 (`run()` → RUNNING) |
| `COLLECTING` | 任务触发的续轮等 1s 合并窗口 | `tryTriggerNext` 见 hasTerminals + hasActive | 1s 等待结束 → 续轮 (→ RUNNING) 或回 WAITING |

### 7.2 转换规则 (双事件流分流)

**两条事件流的触发路径**:

```
                    ┌──────────────────────────────────────────┐
                    │                                          │
                    ▼                                          │
WAITING ──user run()──► RUNNING ──round finally──► WAITING ────┤
   ▲                       │                                   │
   │                       │ (round 实际事件)                  │
   │                       ▼                                   │
   │                  UserRound.channel                        │
   │                       │                                   │
   │                       ▼                                   │
   │              Flow (run() 返回值) ◄─── 调用方 collect        │
   │                                                           │
   │                                                           │
   │   ┌─────── TaskUpdate 终态 (channel.trySend) ───────────┐ │
   │   │                                                    │ │
   │   ▼                                                    ▼ │
tryTriggerNext()  ──(decisionLock.withLock)──► hasTerminals? │
   │                                                │          │
   │                          ┌─────────────────────┘          │
   │                          ▼                                │
   │                  hasActive?                              │
   │                  ┌─────┴─────┐                            │
   │                  ▼           ▼                            │
   │              yes: COLLECTING  no: runContinuationRound() │
   │                  │           │                            │
   │                  │ 1s wait   │                            │
   │                  │           ▼                            │
   │                  │    RUNNING ──finally──► WAITING        │
   │                  │           │                            │
   │                  │           ▼                            │
   │                  │   continuationsEmitter ──► continuations (Flow)
   │                  ▼                                        │
   │              RUNNING ──finally──► WAITING                 │
   │                  │                                        │
   └──────────────────┘                                        │
                                                               │
WAITING ──inputting(true)──► INPUTTING ──inputting(false)──► WAITING
                                       │
                                       └── user run() ──► RUNNING (user 流)
```

**关键点**:
- `run()` 触发 → `UserRound.channel` 流 (返回的 Flow)
- 续轮触发 → `continuationsEmitter` 流 (`continuations` property)
- 合并 round (有 user pending) → 走 user 流 (UserRound.channel)
- 纯续轮 (无 user pending) → 走 continuations 流

### 7.3 终态事件合并策略

| 当前 state | 终态 TaskUpdate 到达时 |
|---|---|
| `WAITING` / `INPUTTING` | 缓存到 `pendingResultEvents`, 调 `tryTriggerNext` 决策 |
| `RUNNING` | 缓存到 `pendingResultEvents`, round finally 会调 `tryTriggerNext` |
| `COLLECTING` | 缓存到 `pendingResultEvents`, 1s 等待结束自然处理 |

**`formatTaskResults` 格式**:
```
[Task Result]
taskId: Final: AgentResult(...)
taskId: Failed: <throwable 类别名 + message>
```

### 7.4 `inputting()` 状态机感知

UI 通过 `inputting(true/false)` 通知 boss, 但状态转换受当前 state 约束:

| 当前 state | `inputting(true)` | `inputting(false)` |
|---|---|---|
| `WAITING` | → `INPUTTING` | no-op |
| `INPUTTING` | no-op | → `WAITING` |
| `RUNNING` | no-op (不打断正在跑的 round) | no-op |
| `COLLECTING` | no-op (不打断 1s 合并) | no-op |

INPUTTING 状态的语义: "user 在 WAITING 状态下开始打字, 意图提交"。不打断 RUNNING/COLLECTING — 这些状态下 user 调 `inputting(true)` 不影响 round 进度, user input 走 `pendingUserRound` 通道, 等当前 round 结束后合并触发。

### 7.5 并发安全

`tryTriggerNext()` 是唯一决策点, 串行化所有触发源:
- `run()` (state 闲时) 调
- `handleTaskUpdate` 收到终态时 调
- `runUserRound` / `runContinuationRound` finally 调

通过 `decisionLock: Mutex.withLock { }` 保护:
1. 重检 state (lock 内, 避免 TOCTOU)
2. 读 `pendingUserRound` / `pendingResultEvents` / `tasks`
3. 决定下一步 (user round / COLLECTING / continuation)
4. `scope.launch { ... }` 启动

**其他并发安全**:
- `tasks` map 所有访问走 `tasksLock.withLock`
- `pendingResultEvents` Channel 自带线程安全; peek 用 `isEmpty` 而非 `tryReceive + trySend` 的非原子操作
- `_state` (StateFlow) 线程安全
- `continuationsEmitter` (SharedFlow) 线程安全

### 7.6 责任划分

- **boss 框架**: 状态机 + 任务跟踪 + 终态事件合并 + 双事件流分流
- **UI 层**: 通过 `inputting(true/false)` 通知 typing 状态; 通过 `state.collect` 订阅状态; 通过 `run()` / `continuations` 订阅事件
- **innerAgent**: 跑 ReAct 循环, 不感知 boss 状态机

---

## 8. 取消

### 8.1 端到端流程

```
1. 用户 "算了,那个任务取消"
2. boss LLM 调 cancel_task(taskId)
3. CancelTaskTool.execute: publish Cancellation 到 BulletinBoard (无本地状态)
4. BulletinBoard 广播 Cancellation
5. Pasture.handleCancellation 接到
6. runningJobs[taskId]?.cancel()
7. job 取消 → beast 内部 ReActAgent 协程抛 CancellationException
8. Pasture.handleAssignment catch: publish TaskUpdate(taskId, Failed(CancellationException))
9. job.invokeOnCompletion 清理 runningJobs
10. BossAgent.handleTaskUpdate 接到 Failed → 触发新 run() 报告用户
```

### 8.2 幂等性

- 如果 taskId 已被移除（已完成 / 已取消）→ `runningJobs[taskId]` 为 null → 静默忽略
- 多次取消同一 taskId = 安全，第二次起静默
- 取消请求与 Final/Failed 事件可能 race：取消请求到达时任务刚好跑完，Pasture 已清理 `runningJobs`，cancel 找不到 job → 静默

### 8.3 coroutine 传播

- 牛马跑 ReAct 时正在 LLM 调用 suspend 中 → cancel 通过 `CancellationException` 透传
- 牛马跑 tool 时 → tool 内部如果 suspend 在 IO 上 → cancel 透传
- 牛马在 user input（如果有） → 同 subagent 处理

**框架不保证取消立即生效** — 只保证"最终一致"：coroutine 取消后，下一个挂起点会抛 `CancellationException` 终止 ReAct 循环。

---

## 9. 异常

### 9.1 `AgentEvent.Failed` 直接接 Throwable

```kotlin
// agent 模块
public sealed interface AgentEvent {
    /** 运行失败, 携带原始 Throwable. 不绑死领域异常类型, 让各模块自由抛出. */
    public data class Failed(val throwable: Throwable) : AgentEvent
    ...
}
```

**不引入 AgentException 类**: Failed 作为事件载体, 不该绑死具体异常层次. agent 模块自己的 LlmError / InvalidResponse 等内部异常, 抛出来直接 `Failed(e)` 传出, 不需要包装. team 模块同理 — 装配失败用 [error] 抛 `IllegalStateException` (内部控制流, 不外传), 取消直接用 stdlib `CancellationException`.

**约束**:
- 只为"需要外部消费者识别"的异常命名 — 当前 v1 没有这种需求, 所以不定义任何 team 异常类
- boss / beast 收到 `Failed(throwable)` 只往外报 message, 不做 `when (e)` 分支, 所以不需要分类

### 9.2 异常传播路径

| 异常源 | 传播路径 |
|---|---|
| beast LLM 错误 | `Beast.run` 抛任意 `Throwable` → `Pasture.handleAssignment` catch → `TaskUpdate(Failed(throwable))` |
| beast Tool 错误 | 同上（被 `toolRegistry.dispatch` 包装为 `isError=true`，LLM 看到后继续；如果 LLM 决定放弃 → `Final` 不会出现错误，由 `Failed` 表达） |
| beast 被取消 | `Pasture.handleAssignment` `catch (e: Throwable)`, `if (e is CancellationException) throw e` 透传取消; 其余 → `TaskUpdate(Failed(throwable))` |
| 派活 selection 找不到 | `Pasture.assembleHorse` 经 [error] 抛 [IllegalStateException] → `handleAssignment` catch fallback Ox (静默降级, 不报错) |
| 派活含任何 subagent | `Pasture.assembleHorse` 经 [error] 抛 [IllegalStateException] → `handleAssignment` catch fallback Ox (静默降级); Ox 持全 registry 让 LLM 自主调 subagent |
| 派活到 Subagent (v1) | 不支持直接派活到 subagent — 见 § 7 不支持的功能; boss 选 subagent 退 Ox, Ox 在通用循环里 capability invoke |
| boss LLM 错误 | `innerAgent.run()` 内部 catch → `AgentEvent.Failed` (走 ReActAgent 既有路径) |

---

## 10. 多意图与依赖

### 10.1 同轮并发派活（一次 publish_task 多个 task）

LLM 在一次 `publish_task` tool 调用里传 `tasks` 数组（多个 task）→ `PublishTaskTool.execute` 为每个 task 生成独立 taskId 并 publish 多个 `TaskAssignment` → `Pasture` 启动多个独立 `job` 并发执行 → 多个 `TaskUpdate` 独立回流 → boss 状态机按到达顺序触发新 `run()`。

**框架支持**：`MutableSharedFlow` + 多个独立 `job` + 状态机合并策略自然处理。LLM 显式表达"无依赖、同时执行"，语义最清晰。

### 10.2 跨轮串行依赖（多次 publish_task, 每次 1 个 task）

LLM 在第一轮派任务 A → 决定 final 文本（不写 A 的结果） → boss `run()` 完成 → `TaskUpdate(Final_A)` 回来 → 触发新 `run()` → LLM 看到 A 的结果 → 决定派任务 B（依赖 A 的结果）。

**框架支持**：靠 `BossAgent` 状态机的"WAITING 状态收到 TaskUpdate → 触发新 run"机制自然支持。LLM 显式表达"任务 B 依赖任务 A 的结果"，由分多次 publish_task + 跨轮 run() 实现。

### 10.3 两种派活的语义对照

| 调用方式 | 语义 | 适用场景 | 框架支持 |
|---|---|---|---|
| 一次 `publish_task(tasks=[A, B, C])` | A/B/C **无依赖、并发**执行 | 用户说"查天气+发邮件+翻译"等独立任务 | 多个 `TaskAssignment` 并发到 `Pasture`，独立 job 并发跑 |
| 多次 `publish_task(tasks=[A])`，等结果再 `publish_task(tasks=[B])` | B **依赖** A 的结果 | 用户说"查天气, 基于结果发邮件" | 第一轮 publish A，第二轮（被 ProgressEvent 触发）publish B |

**LLM 自主选择调用方式** — 工具 description 显式引导（"Pass an array of independent tasks to run them concurrently. For dependent tasks, make multiple calls."）。

### 10.4 不引入任务编排图

- **不**引入 DAG、workflow、状态机引擎等额外抽象
- LLM 自主决定怎么派活（并行/串行/取消）
- 框架只提供"批量派活 + 异步回流 + 取消"基础能力
- 复杂编排交给 LLM 决策

---

## 11. 测试策略

### 11.1 单元测试

| 类 | 测试点 |
|---|---|
| `BulletinBoard` | publishEvent / progressEvent / events subscribe / 缓冲行为 |
| `PublishTaskTool` | 正常派活 (1 selection / 多 selections) / 缺参数 / selection type 校验 / selections 为空报错 |
| `CancelTaskTool` | 正常取消 / 缺参数 / 幂等性 |
| `Beast` (`Ox` / `Horse`) | Ox run 正常 / Horse run 正常 (skill 路径 / toolset 路径 / 多 selection (skill+toolset+tool) 路径, 不含 subagent) / run 异常 / run 被取消 |
| `Pasture` | handleAssignment 正常 / assembleHorse 抛 IllegalStateException (空 selection / 含 subagent / 单 selection 找不到) → fallback Ox / beast 异常 / 取消 |
| `BossAgent` 状态机 | WAITING→RUNNING / RUNNING→WAITING / TaskUpdate 触发 / 缓存合并 |
| `Pasture.assembleHorse` 单元测试 | skill tool 文本匹配绑定 (text 提到 get_weather → 自动注入) / 多 selection 拼装 (skill+toolset+tool, 不含 subagent) / 空 selections → error() 抛出 / 含 subagent → error() 抛出 / 单 selection 找不到 → error() 抛出 / 同名 tool 不去重 / buildOx() 构造等价 |

### 11.2 端到端测试

- 真实 `LlmProvider` mock + `BulletinBoard` + `Pasture` + `BossAgent` + `Beast` (Ox/Horse)
- 场景：用户发请求 → boss 派活 → 马/牛执行 → TaskUpdate 回流 → boss 二次回复
- 场景：一次 `publish_task(tasks=[A, B])` 并发派两个独立任务
- 场景：派活后取消
- 场景：派活的 selection 找不到 (单 selection 缺失 / 多 selection 部分缺失 → 整体 Failed)
- 场景：派活多 selections (toolset + 直接 tool 组合) — Pasture.assembleHorse 一次性解析
- 场景：派活到 subagent — selections 含任何 subagent → 全部 selection 丢弃, 退到 Ox 让 LLM 自主决定何时调 subagent
- 场景：派活 skill 含 tool — skill 文本提到 tool 名 → 自动绑定到 Horse.tools
- 场景：派活 selection 找不到 → assembleHorse 抛 IllegalStateException → handleAssignment fallback Ox 通用模式 (静默降级, 不报错)
- 场景：跨轮次派活（第一个结果回来后再派第二个，串行依赖）
- 场景：**同名 selection 在不同 type** — `Selection.Skill("analyzer")` 和 `Selection.Toolset("analyzer")` 派发到不同 registry, 验证 [Pasture.assembleHorse] 按 selection sealed 子类精确路由

### 11.3 状态机测试

- 用 fake clock / controlled event flow
- 验证 state 转换、input 合并、cache 行为

### 11.4 取消测试

- 牛马跑长任务时取消
- 验证 `TaskUpdate(Failed(CancellationException))` 出现
- 验证 `runningJobs` 清理
- 验证幂等性

### 11.5 不在测试范围

- 多进程、跨设备
- 持久化布告栏
- 性能压测（不是 v1 目标）

---

## 12. 兼容性

### 12.1 现有模块的 API 变化

| 模块 | 变化 | 类型 | 说明 |
|---|---|---|---|
| `agent` | `AgentEvent.Failed` 入参由 `AgentException` 改为 `Throwable`; `AgentException` 类删除 | **破坏性** | 让各模块自由携带原始异常, 不再要求包成领域异常. agent 模块内部异常 (LlmError / InvalidResponse 等如有) 抛出来直接 `Failed(e)` 传出 |
| `agent` | `AgentBuilder.subagents(SubagentRegistry)` / `AgentBuilder.toolsets(ToolsetRegistry)` | 新增 DSL | **前提**: 现有 AgentBuilder 未提供; 仅 Ox 通过 `agent { }` DSL 注册用, **不**影响其它用户 |
| `toolset` | `Toolset.all(): List<Tool>` 新增 | 新增方法 | 让 Pasture 从 toolset 抽 subTools 注入 Horse |
| `skill` | `SkillRegistry.allTools(): List<Tool>` 新增 | 新增方法 | 让 Pasture 在 skill 文本匹配时拿到所有 tool |
| `skill` | `Skill.load()` 重构为无 ctx (旧 `load(context: SkillContext): String`) | **破坏性** | Subagent 走同样改造, 见下; 详见 § 12.2 |
| `subagent` | `Subagent.load()` 重构为无 ctx (旧 `load(context: SubagentContext): String`) | **破坏性** | 同上 |
| `subagent` | `Subagent.activate()` 保留 ctx (`activate(arguments, context)`) | 不变 | 运行时调用仍需要 ctx |
| `subagent` | `Subagent.tools: List<Tool>?` 语义扩展: null = 父 ToolRegistry 由 `Selection.Tool` 路径处理, 非 null = v1 不注入 Horse (含 subagent 直接退 Ox, Ox 持全 registry) | 语义细化 | 已有字段, 文档化新语义 |
| `mcp` | 不动 | 不变 | 内部仍走 Toolset 路径 |
| `capability` | 不动 | 不变 | Pasture.assembleHorse 用 `Selection` sealed 子类分发, 不依赖 `CapabilityRegistry.findByName` |
| `session` | 不动 | 不变 | `team` 用 `Session.memory` 作为 boss memory |

### 12.2 破坏性变更 (load() 去 ctx)

**问题**: `Skill.load(context: SkillContext): String` / `Subagent.load(context: SubagentContext): String` 当前需要 context 参数, 而 pre-load 模式 (Pasture 在 boss run 之前调用 `load()` 拼 persona) 没有可用的 context.

**方案**: 把 `load()` 重构为无 ctx. 仅当 ctx 未被使用 (实际检查: 现有所有 `Skill` / `Subagent` 实现都不读 context 字段, 只返回静态文本或纯函数) 时, 这是安全的破坏性变更.

**影响范围**:
- `Skill` 已有实现 (如 `WeatherSkill`, `NewsSkill`): 把 `override fun load(context: SkillContext): String` 改成 `override fun load(): String`
- `Subagent` 已有实现: 同样改造
- 调用方 (`SkillToolLoader` / `Pasture.assembleHorse` / `Subagent.activate` 等) 同步改

**回退方案**: 若 `load()` 必须保留 ctx, 改用 pre-load mode 之外的另一条路径 (e.g., 让 boss LLM 通过 `load_skill` 工具动态加载). 暂不需要.

### 12.3 新增 API (`team` 模块)

**公开 (public)**:
- `TeamAgent` / `teamAgent { }` DSL (入口表面)
- `TeamAgentBuilder` (DSL 接收器, 构造器 `internal`)

**模块内部 (internal)**:
- `BulletinBoard` / `BulletinEvent` / `PublishEvent` / `ProgressEvent` / `TaskAssignment` / `TaskUpdate` / `Cancellation`
- `Selection` (sealed: `Skill` / `Toolset` / `Subagent` / `Tool`)
- `Beast` (interface) / `Ox` / `Horse`
- `Pasture` (含 `assembleHorse(selections)`)
- `BossAgent` / `TaskState` (`BossState` 升级为 public, 因 `TeamAgent.state: StateFlow<BossState>` 需对外暴露)
- `PublishTaskTool` / `CancelTaskTool` / `NamedCapability`

### 12.4 builder 字段变化

| 字段 / DSL | 旧 | 新 |
|---|---|---|
| `beastPersona: Persona` | 存在, 用户可配 | **移除** — Pasture 内部 baseRole 替代 |
| `memory` / `llmProvider` / `maxIterations` / `maxRounds` | `public var` 属性 | 方法式 `memory(...)` / `llmProvider(...)` / `maxIterations(...)` / `maxRounds(...)` |
| `tool(tool: Tool, quick: Boolean)` + `tool(registry: ToolRegistry)` | 两个重载 | 合并为单一 `tools(registry, quick: Boolean = false)`; `quick = true` 是 boss 快速可调 (合并到 innerAgent ToolRegistry); `quick = false` 是 tool 池 (供 Selection.Tool 选用) |
| `skill(registry)` / `subagent(registry)` / `toolset(registry)` | 存在 | 重命名为复数 `skills(registry)` / `subagents(registry)` / `toolsets(registry)`, 语义不变 |
| `mcps(registry)` | 接口预留, 静默忽略 | **保持 no-op** — MCP 能力委托给 toolsetRegistry, `mcps()` 仅 DSL 完整性 |
| `capabilitiesByType` 类型 | `Map<String, List<Capability<*, *>>>` | `Map<String, List<NamedCapability>>` (4 类含 tool) |

---

## 13. 不在本设计范围（YAGNI）

| 项 | 原因 |
|---|---|
| 多牧场分布式 | 单牧场够用，未来需要时再加 |
| 跨进程持久化布告栏 | 进程内事件总线够用 |
| 限流 / beast pool 复用 | 不限流，保留扩展点（未来在 `Pasture` 加 `Semaphore`） |
| 任务超时 | 信任 coroutine 取消 + LLM 自身的超时 |
| 任务优先级 | LLM 自主决定派活顺序 |
| 资源隔离 | 单一进程，单一 LLM provider，足够 |
| boss 远程加载 | v1 全部本地配置 |
| 派活到 Subagent | v1 不直接派活到 subagent — selections 含任何 subagent → 全部 selection 丢弃, 退 Ox 让 LLM 自主调 subagent (capability invoke); boss 看不到 subagent.tools 等私有细节, Horse 装配破坏 subagent 闭环 |
| 派活到 MCP server-level | v1 只支持把 MCP 暴露的 toolset 拆给 ox，server-level 派活等同 subagent 模式 |
| 派活 selection 部分失败 (partial success) | 任一 selection 找不到 → `assembleHorse` 抛 IllegalStateException → `handleAssignment` fallback Ox (静默降级, 不向 boss 报错); 不做"少一个还能跑"语义 — 让 LLM 用全 registry 自主补齐 |
| 多 Subagent 协同 per task | v1 不支持 — 任何含 subagent 的 selection 一律退 Ox (单一 subagent 也退); 真正的多 subagent 协同留 v2 |
| 任务进度可视化 | UI 关注，team 框架不感知 |
| 任务重试 / 死信队列 | LLM 自主重试（再调一次 `publish_task`） |
| 任务的资源占用统计 | v1 不需要 |
| 任务的状态查询 API | boss 内部维护 `Map<taskId, TaskState>` 即可，不暴露给外部 |

---

## 附录 A · 关键决策摘要

| # | 决策 | 备注 |
|---|---|---|
| A | 命名：`TeamAgent` / `teamAgent { }` / 4 组件 `BulletinBoard` / `Pasture` / `BossAgent` / `Beast` (Ox/Horse) | 外部单一装配点 |
| B | `BossAgent` 包装 `ReActAgent`，不重写 ReAct 循环 | 包装模式 |
| C | `Beast` 接口 — `Ox` (通用, 持 4 个单 capability registry + Persona) + `Horse` (专项, 持 Persona + 派生 tools 列表) — 包装 `ReActAgent`；每次任务 new, 不维护 pool | 双实现, 按工作模式分 |
| D | team 整体配置：每个 capability 类别至多 1 个 registry (`ToolRegistry` / `SkillRegistry` / `SubagentRegistry` / `ToolsetRegistry`), 4 个单 setter 一次配置, TeamAgent 内部分配给 boss (菜单) 和 pasture (路由). MCP 通过 `McpRegistry` 内部注册到 `ToolsetRegistry`. baseRole 由 Pasture 内部决定 (不暴露给用户) | team 是一整体, 内部封装 base role |
| E | `tools(registry, quick: Boolean = false)` 单一形态 — `quick = true` 把 registry 内容合并到 innerAgent 的 ToolRegistry (boss 快速可调, 同步阻塞当前 run, 执行耗时短), `quick = false` (默认) 是 tool 池, 经 `Selection.Tool` 选用 | 合并两个重载, 统一通过 ToolRegistry 注册; 方法名复数化 (skills/subagents/toolsets/tools/mcps) 与 AgentBuilder DSL 对齐 |
| F | 多意图/依赖：`publish_task(tasks=[...])` 一次批量并发派多个无依赖任务; 多次 `publish_task` 跨轮串行依赖任务; **同一 task 多 selections** 组合资源 — LLM 显式表达, 框架不特殊处理 | 状态机 + LLM 自主 + 工具 description 引导 |
| G | 消息分层：3 个并列顶层 sealed interface (`BulletinEvent` / `PublishEvent` / `ProgressEvent`); `BulletinBoard` 发布入口拆成 `publishEvent` / `progressEvent`, 类型系统强制方向防污染 | 性质分层 + API 边界 |
| H | 闲聊 vs 派活：靠 `PublishTaskTool.description` 引导 LLM，框架不干预 | description 引导 |
| I | 未知 selection: 任一 selection 找不到 → `assembleHorse` 抛 IllegalStateException → handleAssignment fallback Ox (静默降级); 含任何 subagent → assembleHorse 抛 IllegalStateException → 退 Ox; 空 selections 同样抛异常退 Ox. 不向 boss 报错, 不做 partial success | 牧场不反向干预, 全部静默降级到 Ox 通用模式 |
| J | YAGNI: 多牧场、跨进程、持久化、限流、beast pool 全部不在范围 | 保留扩展点 |
| K | `Beast` 双实现 + pre-load 模式: Ox 持全 registry (通用), Horse 只持 Persona + 派生 tools 列表 (专项, 不持 registry). Pasture.assembleHorse 一次性解析所有 selections, 拼出 Beast | 通用 vs 专项, 装配下沉到 Pasture |
| L | 派活含 Subagent v1 不走 Horse — boss 看不到 `subagent.tools` 等私有细节, 装配会破坏 subagent 闭环; selections 含任何 subagent → 全部 selection 丢弃, 退 Ox. Ox 持全 registry 让 LLM 在通用循环里 capability invoke 调 subagent (无 SubagentInvoker 包装层, Subagent 通过 Capability 机制被 invoke) | subagent 是原子能力, 不与外部 selection 混合装配 |
| M | 取消通过 `publishEvent(Cancellation)` + pasture 取消 beast job 实现 | 事件总线 + coroutine cancel |
| N | 取消失败（job 已完成）静默忽略（幂等） | 取消是事件不是事务 |
| O | `BossAgent` 四态：WAITING / RUNNING / INPUTTING / COLLECTING | INPUTTING 预留，UI 控制；COLLECTING 等 1s 合并 |
| P | `INPUTTING` 状态由 UI 层通过 `inputting(true/false)` 控制 | boss 框架不感知 UI |
| Q | `publish_task` 每条 task 必须显式指定 `selections` 数组, 每条 selection 形如 `{"type": "skill\|toolset\|subagent\|tool", "name": "..."}` — type 是路由键, name 是 registry 内查找键 | 4 类 sealed 分发, 取代旧的 `(type, name)` tuple |
| R | Skill 文本中的 tool 名 → 自动绑定到 Horse.tools (经 `SkillRegistry.allTools()` 全词匹配), LLM 在 Horse 中直接调, 绕过 `skill_tool_loader` + `skill_tool_caller` 二段式 | pre-load 模式下的特殊处理, 约定 Skill 作者在 load() 文本里把 tool 名作为独立词写出 |
| S | 容器为 `TeamAgent` (实现 [Agent]), `teamAgent { }` DSL 单次装配 — boss / pasture / bulletinBoard 全部私有, 外部仅通过 `team.run` / `team.runStream` / `team.state` / `team.shutdown` 与之交互 | 单一对外表面, 屏蔽内部协作细节 |
| T | 不引入 team / boss 名称字段 — 1 team 1 boss 隐式成立, `TaskAssignment` / `TaskUpdate` / `Cancellation` 不携带 bossName; 取消用 stdlib `CancellationException`, 不引入 `Cancelled` 异常类 | 避免唯一约束冗余; 派发 → 路由按 taskId 关联 |
| U | `maxIterations` / `maxRounds` 是 team 层级统一配置 — 同时作用于 boss innerAgent 和 beast (Ox/Horse) 内部 ReActAgent; Pasture 透传, builder 仍只暴露一份参数 | ReAct 护栏语义对等, 避免"boss 受限 / beast 不受限"的不一致; 统一对外表面 |

---

**文档结束 · 实现为准**
