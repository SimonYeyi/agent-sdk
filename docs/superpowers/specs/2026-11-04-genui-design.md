# 生成式 UI（genui）模块 —— 设计

## 背景与目标

让 Agent 在对话中产出可交互的卡片式 UI（天气卡、导航卡、统计报表等），用户点击卡片动作后事件回流 Agent 继续推理。

**目标**：
- 只新增一个模块 `:agent:genui`，现有模块零改动（含 core）
- v1 先覆盖「绑定原生工具的卡片」：数据与卡片逻辑内聚在同一工具内，工具执行时对外发卡片
- 渲染/协议适配按通用做法：IR 协议无关，UI 侧组件注册表直映射，未来可接入 OpenUI Lang / A2UI 适配器

**非目标**（v1 不做）：
- 无工具场景（模型自由渲染：多轮推理聚合数据后想渲染 UI）——v1 不在 core/协议层加结构化输出字段，见「v2 演进」
- 双协议（OpenUI Lang / A2UI）适配——v1 只提供自研 IR 一种实现
- 编排层集中编译（flymeclaw 的出口编译层拓扑）——本 SDK 无编排层，采用工具内嵌旁路通道

## 约束

1. 只增加一个模块（`:agent:genui`），所有新增代码都在该模块内
2. 不修改 core（`agent/core`、`agent/config` 等现有模块一律不动）
3. 先完成绑定原生工具的卡片
4. 渲染/协议适配等按通用做法

## 核心决策

| 决策点 | 结论 |
|---|---|
| 模块归属 | `:agent:genui`，纯 JVM（`kotlin.jvm` + `kotlin.serialization`），`api(project(":agent:core"))` |
| UI 事件通道 | 旁路 `UiSink`（普通类，按实例注入：demo/会话持有实例注入工具并订阅 `cards: SharedFlow<CardIr>`，`emit` 挂起发射）+ `UiGenTool` 抽象基类（实现 Tool，`uiSink` 构造器必传注入，`execute` final 模板接线） |
| 卡片记忆策略 | 卡片 IR 完全绕过记忆，工具回执文本照常进记忆，避免 token 污染 |
| IR 形态 | 统一组件树（TREE），模板概念下沉为「工具内代码复用」，IR 层不做模板注册表 |
| 动作契约 | 动作集合在卡片 IR 内静态声明（白名单），渲染引擎只绑定白名单内动作 |
| 渲染归属 | `RenderEngine` 引擎在 genui（组件注册表/树遍历/降级/动作绑定），组件渲染实现由客户端注册，外部极简调用 |
| 回流路径 | 卡片动作事件 → demo 组装 AgentQuery → 新一轮推理（BossAgent input / ReActAgent 新 run） |
| 会话恢复 | `CardIr.instanceId` = 工具 `toolCallId`，demo 本地卡片记录按锚点归位 |
| ReActAgent / BossAgent 接入 | 零改动：工具是外部注入的，demo 订阅 agent 事件流 + UI 流自行合并 |

## 架构总览

```
agent/genui（新模块，纯 JVM）                demos/agent（Android Compose）
──────────────────────────                 ──────────────────────────────
CardIr（组件树 IR，@Serializable）          engine.register("Text") { node, _ -> Text(...) }
UiSink（旁路发射通道，实例注入）    ───────→  // 仅组件渲染实现注册（Compose 闭包）
Action / UiActionEvent（动作契约/回流）      CardView(card, engine, onAction) // 极简渲染入口
校验器 + RenderEngine                     onAction: UiActionEvent → AgentQuery（回流桥）
  （注册表/树遍历/降级/动作绑定）

工具侧（外部注入）                          agent 内核（零改动）
继承 UiGenTool（构造器注入 sink）：          ReActAgent / BossAgent
getResult 业务执行 → buildCard 映射 →      只负责既有事件流（ToolCallStart/End、Final）
  sink.emit，回执照常进记忆
demo 侧：sink.cards.collect { 渲染 }         // 订阅监听（sink 为 demo 自持实例）
```

四条路径共用同一通道：工具内嵌卡片（v1 主干）、生成式长尾卡（v2）、确认卡（v2）、模型自由渲染（v2）——全部走 `UiSink` emit `CardIr`，消费方只订阅一条 UI 流。

## 逐节设计

### 节 1 — IR 协议：组件树

```kotlin
@Serializable
public data class CardIr(
    public val instanceId: String,        // 与工具 toolCallId 一一对应，会话恢复锚点
    public val root: TreeNode,
    public val actions: List<Action> = emptyList()
)

@Serializable
public data class TreeNode(
    public val component: String,
    public val bindings: Map<String, JsonElement> = emptyMap(), // 组件属性（文本、数据、样式）
    public val children: List<TreeNode> = emptyList(),
    public val action: String? = null     // 可点击节点绑定的动作 id，必须在 actions 白名单内
)

@Serializable
public data class Action(
    public val id: String,                // 动作唯一 id，回流事件携带
    public val label: String              // 按钮/可点击区域文案
)
```

- 组件名由渲染层注册表解释（`"Text"`、`"Button"`、`"BarChart"`…），IR 层不感知具体组件
- 模板 = 工具内静态 `TreeNode` 骨架 + 运行期数据填充，属工具作者代码复用，不进协议
- 未知组件由渲染层降级为文本块，不中断渲染

### 节 2 — UiSink 通道 + UiGenTool 抽象基类

**UiSink 是普通类旁路通道**，按实例注入（非全局单例）——作用域由持有者掌控：demo 为应用或单个会话创建实例，注入给工具并订阅监听，多会话卡片流天然隔离，测试可各自持有实例互不污染：

```kotlin
public class UiSink {
    private val _cards = MutableSharedFlow<CardIr>(extraBufferCapacity = 64)

    /** 外部监听入口：demo 直接 collect 渲染。实时广播，不保留历史；会话恢复依赖 demo 本地自持卡片记录。 */
    public val cards: SharedFlow<CardIr> = _cards.asSharedFlow()

    /** 挂起发射：仅 genui 模块内部收口调用（UiGenTool.execute 接线）；外部只有订阅权，无发卡权，杜绝伪造卡片流。 */
    internal suspend fun emit(card: CardIr) {
        _cards.emit(card)
    }
}
```

- `replay = 0`（默认）不保留历史：仅订阅后发射的卡片可见，语义实时广播，会话恢复由 demo 本地自持卡片记录完成
- `extraBufferCapacity = 64` + SUSPEND：活动订阅者暂未就绪时卡片入缓冲不丢；缓冲积压满时 `emit` 挂起等待而非丢弃；无订阅者时立即返回直接丢弃
- 与项目既有先例（BossAgent report 流：replay=0 + 缓冲 + SUSPEND + `emit`）口径一致
- 实例注入：demo 自持 `UiSink()` 实例，注入给工具并订阅 `cards`；多会话各持实例，卡片流不串流
- 发卡权收口：`emit` internal，只允许 genui 模块内部（`UiGenTool.execute` 接线）发卡；外部只有订阅权，杜绝伪造卡片流

**UiGenTool 是抽象基类（实现 Tool）**，`uiSink` 构造器必传注入，`execute` final 化统一接线，子类只需实现业务与 UI 映射两个钩子：

```kotlin
/** 声明「本工具产出可交互卡片」的抽象基类，实现 Tool；execute 模板负责接线发射。 */
public abstract class UiGenTool(
    private val uiSink: UiSink
) : Tool {

    /** 真实业务逻辑，返回本轮结构化数据（供 buildCard 映射），回执文本由 execute 依据它生成。 */
    protected abstract fun getResult(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): JsonElement

    /** 将本轮结构化数据映射为卡片 IR。正常数据必须返回非空卡片；仅当数据不合法/为空时返回 null，表示本轮不产卡、降级为纯文本回执。 */
    protected abstract fun buildCard(
        data: JsonElement,
        context: ToolExecutionContext
    ): CardIr?

    /** 模板接线：业务执行 → 产卡发射 → 回执，时序固定在 ToolCallStart/End 区间内；buildCard 返回 null（数据异常/为空）时跳过发射，仅返回回执文本。 */
    final override suspend fun execute(
        arguments: JsonElement,
        context: ToolExecutionContext
    ): ToolExecutionResult {
        val result = getResult(arguments, context)
        buildCard(result, context)?.let { uiSink.emit(it) }
        return ToolExecutionResult.success(result.toString())
    }
}
```

工具侧用法：子类实现 `getResult`（业务 + 结构化数据）+ `buildCard`（UI 映射），发射接线由 final `execute` 收口：

```kotlin
class WeatherTool(uiSink: UiSink) : UiGenTool(uiSink) {
    override val name = "get_weather"
    override val description = "查询天气"
    override val parametersSchema = ToolParameters.Empty

    override fun getResult(arguments: JsonElement, context: ToolExecutionContext): JsonElement {
        val city = arguments.jsonObject["city"]?.jsonPrimitive?.content ?: "未知城市"
        return JsonObject(mapOf(
            "city" to JsonPrimitive(city),
            "summary" to JsonPrimitive("晴天 26°C")
        ))
    }

    override fun buildCard(data: JsonElement, context: ToolExecutionContext): CardIr {
        val city = data.jsonObject["city"]?.jsonPrimitive?.content ?: "未知城市"
        return CardIr(
            instanceId = context.toolCallId,
            root = TreeNode("WeatherCard", bindings = mapOf(
                "city" to JsonPrimitive(city),
                "summary" to JsonPrimitive("晴天 26°C")
            )),
            actions = listOf(Action("refresh", "刷新"))
        )
    }
}
```

- 时序由 final `execute` 保证：`ToolCallStart → getResult → buildCard → emit → 工具返回 → ToolCallEnd`，卡片落在区间内，锚点天然对齐
- `uiSink` 构造器必传注入（private）：持有者（demo/会话）显式决定卡片流作用域；仅基类 `execute` 收口使用，子类不可见、无发卡权
- `buildCard`/`getResult` protected：UI 映射细节封装在子类，`Tool` 对外契约（execute/name/description/schema）不受影响
- `getResult` 直接返回结构化 `JsonElement`（不引入新类型体系），`buildCard` 返回 `CardIr?`：正常数据必产非空卡片，仅数据不合法/为空时返回 null 跳过发射，降级为纯文本回执
- 卡片走旁路不经过 agent 事件主干，core 零感知；回执文本照常返回 → 记忆清洁
- demo 侧：`val sink = UiSink()` 自持实例，`WeatherTool(sink)` 注入工具，`sink.cards.collect { render(it) }` 订阅监听

### 节 3 — 校验（动作白名单）

- 渲染前校验 `CardIr.actions`：动作 id 非空、唯一；`TreeNode.action` 必须引用白名单内 id，未引用/不存在的动作直接不可点击（渲染为静态文本）
- 安全红线：动作集合由工具作者在 emit 时声明，渲染引擎只绑定白名单动作，杜绝自由发明
- 校验失败不抛异常，降级为纯展示（无动作），保证 UI 流永不中断

### 节 4 — RenderEngine 渲染引擎（genui）+ 组件实现注册（demo）

引擎逻辑全部在 genui 模块，框架无关（不依赖 Compose）；demo 只注册组件渲染实现并调用极简入口。

genui 侧（纯 JVM）：

```kotlin
public fun interface Renderer {
    /**
     * 渲染单个节点。
     *
     * @param node 当前节点，实现方读取 bindings 渲染内容
     * @param action 已通过白名单校验的动作规格；null 表示不可点击，实现方渲染为静态内容
     * @param emit 触发动作回流：仅当 action 非 null 时可用，引擎自动构造 UiActionEvent，实现方零组装
     */
    public fun render(node: TreeNode, action: Action?, emit: () -> Unit)
}

public class RenderEngine {
    private val registry = mutableMapOf<String, Renderer>()
    private var fallback: Renderer = { _, _, _ -> } // 默认空降级，demo 可注册文本块

    public fun register(component: String, renderer: Renderer)
    public fun registerFallback(renderer: Renderer)

    /** 校验动作白名单后递归渲染整棵树（先序）；可点击节点按白名单绑定 emit。 */
    public fun render(card: CardIr, emit: (UiActionEvent) -> Unit)
}
```

- 组件注册表、树遍历、未知组件降级、动作绑定（`node.action` 必须命中 `card.actions` 白名单，否则渲染为静态内容）全部由引擎收口
- `UiActionEvent` 由引擎构造（`instanceId` 取自 card，`actionId`/`payload` 取自节点与绑定数据），实现方零组装
- 白名单本身非法（动作 id 为空或重复）时全卡降级为纯展示；校验失败不抛异常，UI 流永不中断

demo 侧（Compose 只需两件事）：

```kotlin
val engine = RenderEngine().apply {
    register("Text") { node, _, _ -> Text(node.bindings["content"]?.jsonPrimitive?.content ?: "") }
    register("Button") { node, action, emit ->
        if (action == null) { Text(node.label()) } // 未命中白名单：静态文本
        else Button(onClick = emit) { Text(action.label) }
    }
}

@Composable
fun CardView(card: CardIr, engine: RenderEngine, onAction: (UiActionEvent) -> Unit) {
    engine.render(card, onAction)
}
```

- 组件实现是普通闭包，满足框架无关的 `Renderer` 接口；Compose 只是 v1 的一种实现
- 外部使用：注册组件实现 + `engine.render(card, onAction)`，引擎内部细节对消费方不可见

### 节 5 — 回流契约

```kotlin
@Serializable
public data class UiActionEvent(
    public val instanceId: String,
    public val actionId: String,
    public val payload: JsonObject = JsonObject(emptyMap())
)
```

回流链路（demo 侧）：

```
用户点击卡片按钮
  → UiActionEvent(instanceId, actionId, payload)
  → demo 组装 AgentQuery（如 "用户点击了【选择此路线】"）
  → BossAgent input 新轮次 / ReActAgent 新 run
  → 模型感知用户选择，继续推理
```

- 动作语义由工具作者在卡片 IR 中声明，回流事件是结构化数据，不依赖模型转述
- 点击卡片不产生模型推理轮，仅回流触发新一轮

### 节 6 — 记忆清洁与锚点恢复

- **记忆清洁**：`CardIr` 只走 `UiSink` 旁路，永不写入记忆；记忆里只有工具回执短文本
- **锚点**：`CardIr.instanceId` = `ToolExecutionContext.toolCallId` = 记忆 `ToolResult.toolCallId`
- **会话恢复**：demo 本地按 dialog id 自持卡片记录，重启后遍历记忆找到对应 `ToolResult` 条目，按锚点将卡片插入正确位置
- 时序：工具在 `ToolCallStart` 与 `ToolCallEnd` 之间 emit（挂起发射，正常场景不阻塞执行），发射顺序由执行位置决定；收集侧合流即使存在调度竞态，锚点机制免疫

### 节 7 — ReActAgent / BossAgent 接入

两处接入均为零代码改动：

- **ReActAgent**：工具外部注入（构造时无需感知通道），core 不感知卡片；demo 订阅 agent 事件流（气泡排序）+ UI 流（`sink.cards` 卡片渲染，sink 为 demo 自持实例）自行合并
- **BossAgent**：同上；卡片回流经 `AgentQuery` 进入新轮次，报告轮次语义不受影响

## v1 范围与 v2 演进

**v1（本次）**：
- `:agent:genui`：`CardIr` / `TreeNode` / `Action` / `UiActionEvent` / `UiSink`（实例通道）/ `UiGenTool` / 校验器 / `RenderEngine`
- demo 侧：组件渲染实现注册 + `sink.cards` 订阅（demo 自持 `UiSink()` 实例注入工具）+ 回流桥（`UiActionEvent` → AgentQuery）+ 锚点恢复
- 覆盖场景：绑定原生工具的卡片（数据 + 布局 + 动作内聚在工具内）

**v2（演进方向，本次不动）**：
- 模型自由渲染：Assistant 输出增加可选结构化字段（flymeclaw `output.structured` 同构），与 content 分离，结构化绕过记忆天然成立——需改 core 协议，违背 v1 约束，延后
- 生成式路径：brief → 专职 UI 模型 → 三重校验 → 降级链（oui-1 接入点，经 LLM 网关 provider 变体）
- 协议适配：OpenUI Lang / A2UI 适配器，IR 保持协议无关，渲染层换适配器即可
- 确认卡（CONFIRM）与多工具汇总卡：合成工具或协议层出口

## 测试策略

- genui 模块（JVM）：`CardIr` 序列化/反序列化、动作白名单校验、未知组件降级、`RenderEngine` 树遍历顺序与 `UiActionEvent` 构造、`UiSink` 发射与订阅时序（订阅后 emit 可见 / 无订阅不阻塞不缓存）、`UiGenTool` execute 接线（getResult → buildCard → emit 与回执语义）
- demo 侧：人工验证渲染与点击回流；锚点恢复用本地卡片记录 + 记忆 ToolResult 模拟
