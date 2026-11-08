# 生成式 UI（genui）模块 —— 设计

## 背景与目标

让 Agent 在对话中产出可交互的卡片式 UI（天气卡、导航卡、统计报表等），用户点击卡片动作后事件回流 Agent 继续推理。

**目标**：
- 在 `agent/genui` 下新增 `ir/`、`tool/` 与 `adapters/` 结构：`ir` 为 `:agent:genui:ir`（叶级项目名唯一，规避与 `:agent:core` 同名导致的 Gradle 依赖解析混淆；projectDir 指向 `agent/genui/ir`）纯 JVM 协议层（序列化 IR + 校验器，包名 `io.github.yeyi.agent.genui.ir`）；`tool` 为 `:agent:genui:tool`（projectDir 指向 `agent/genui/tool`）旁路通道与工具基类（UiSink/UiGenTool，包名 `io.github.yeyi.agent.genui.tool`）；`adapters` 为 Android 适配层，下分 `api/`（插槽接口）、`default/`（自研后端）、`a2ui/`（A2UI 后端）三个独立子模块，现有模块零改动（含 ir/tool）
- ir 保持纯 JVM、零平台依赖、退化为纯协议层：序列化 IR、校验器不绑定任何 UI 框架；通道（UiSink）与工具基类（UiGenTool）独立进 tool 模块
- 渲染后端可插拔：自研渲染与三方协议（A2UI）以统一适配器接口平级接入，demo 组装一次选择，业务代码无感
- v1 先覆盖「绑定原生工具的卡片」：数据与卡片逻辑内聚在同一工具内，工具执行时对外发卡片

**非目标**（v1 不做）：
- 无工具场景（模型自由渲染）——v1 不在 ir/协议层加结构化输出字段，见「v2 演进」
- 协议适配（A2UI 官方渲染器接入）——作为可插拔后端的一个实现，`A2uiRenderAdapter` 已交付投影 + 引擎状态机 + 回流映射；官方 Compose 渲染制品（A2uiSurface）待 androidx.a2ui.compose 发布后接入
- 编排层集中编译——本 SDK 无编排层，采用工具内嵌旁路通道

## 约束

1. 新增 `agent/genui/ir`（纯 JVM 协议层）与 `agent/genui/tool`（旁路通道与工具基类）两个子模块，以及 `agent/genui/adapters` 目录（内含 `api/`、`default/`、`a2ui/` 三个独立子模块），现有模块零改动（含 ir/tool）
2. ir 纯 JVM、零平台依赖、退化为纯协议层：协议 IR/校验不引入 Compose 等平台类型，也不承载渲染实现（组件注册绑定平台 @Composable，留不到 ir），更不依赖 agent 内核（`:agent:core` 零依赖）；ir 源码包名 `io.github.yeyi.agent.genui.ir`
3. 平台类型（`@Composable`、androidx.a2ui）只允许出现在 genui adapters 及其子模块，不污染 ir
4. 渲染后端可插拔：自研（DefaultRenderAdapter）与三方协议（A2UI）以统一适配器接口平级接入，demo 组装点一次性选择
5. demo 只组装（注册组件实现 + 选择后端 + 回流桥），不写适配器实现
6. tool 模块承载依赖 agent 内核的类型（UiGenTool 实现 `Tool`），包名 `io.github.yeyi.agent.genui.tool`；ir 与 tool 的模块语义完全进包名，跨模块无包名混淆

## 核心决策

| 决策点 | 结论 |
|---|---|
| 模块归属 | 嵌套结构：`:agent:genui:ir`（纯 JVM：`kotlin.jvm` + `kotlin.serialization`，零 agent 依赖，只依赖 kotlinx-serialization）承载协议 IR（CardIr/TreeNode/Action/ActionEvent）+ 校验器（CardIrValidator）；`:agent:genui:tool`（纯 JVM，`api(project(":agent:genui:ir"))` + `api(project(":agent:core"))`）承载旁路通道与工具基类（UiSink/UiGenTool）→ adapters 下三个独立子模块：`:agent:genui:adapters:api`（Android：`com.android.library` + Compose，`api(project(":agent:genui:ir"))`，承载 `UiRenderAdapter` 插槽接口）→ `:agent:genui:adapters:default`（自研后端）与 `:agent:genui:adapters:a2ui`（A2UI 后端，依赖 androidx.a2ui），两者平级依赖 api |
| 协议与工具分层 | ir 只承载序列化 IR 与校验器（零平台、零 agent 依赖）；UiSink/UiGenTool 单独进 tool 模块（UiGenTool 依赖 agent 内核 `Tool` 接口），依赖方向单向：tool → ir → 无，跨模块无包名混淆 |
| UI 事件通道 | 旁路 `UiSink`（普通类，按实例注入：demo/会话持有实例注入工具并订阅 `cards: SharedFlow<CardIr>`，`emit` 挂起发射）+ `UiGenTool` 抽象基类（实现 Tool，`uiSink` 构造器必传注入，`execute` final 模板接线） |
| 卡片记忆策略 | 卡片 IR 完全绕过记忆，工具回执文本照常进记忆，避免 token 污染 |
| IR 形态 | 统一组件树（TREE），模板概念下沉为「工具内代码复用」，IR 层不做模板注册表；模板卡下工具只交 root + 数据 |
| 动作契约 | 动作由「拥有者」声明，一个动作只有一处声明：通用组件路径由节点自带（`TreeNode.action`），模板组件路径由组件注册时声明（渲染层）。卡内 id 唯一（同 id 必须同 label），任何渲染后端只绑定已声明动作 |
| 校验归属 | 动作校验独立为公开校验器 `CardIrValidator`（协议层语义收口），自研后端与投影后端共用；A2UI 官方校验不覆盖 genui 动作语义 |
| 渲染后端插槽 | genui adapters 的 `api` 子模块定义平台版 `UiRenderAdapter`（@Composable）插槽接口，实现分两个并列后端子模块：`adapters:default` 的 `DefaultRenderAdapter`（自研声明式后端，组件注册绑定平台 @Composable，渲染底座整体落于适配器）与 `adapters:a2ui` 的 `A2uiRenderAdapter`（CardIr→A2UI 投影 + androidx.a2ui 官方引擎状态机 + 回流映射）；demo 组装点一次性选择，业务代码统一走 `adapter.RenderCard(card, onAction)` 无感 |
| 自研渲染 | 组件注册绑定平台渲染实现（@Composable），注定留不在纯 JVM 的 ir——ir 退化为纯协议层（序列化 IR + 校验），渲染底座整体并入 `DefaultRenderAdapter`（@Composable 注册表 + 声明式递归渲染），与其他后端平级 |
| 组件契约 | 自研后端用 @Composable 组件注册表（DefaultRenderAdapter），组件注册时可声明自己的动作集（模板卡路径）；A2UI 后端用 catalog（目录即契约），投影时组件名映射到目录内组件——A2UI 侧 v1 只承载节点自带动作，模板卡需组件→动作表，未实现 |
| 回流路径 | 卡片动作事件 → demo 组装 AgentQuery → 新一轮推理（BossAgent input / ReActAgent 新 run） |
| 会话恢复 | `CardIr.instanceId` = 工具 `toolCallId`，demo 本地卡片记录按锚点归位 |
| ReActAgent / BossAgent 接入 | 零改动：工具是外部注入的，demo 订阅 agent 事件流 + UI 流自行合并 |

## 架构总览

```
agent/genui（两级嵌套）                       demos/agent（消费端）
─────────────────────                     ──────────────────────────────
ir/（纯 JVM 协议层，零 agent 依赖，         genui/GenUi（组装点：一次性选择后端）
  包名 io.github.yeyi.agent.genui.ir）        组件渲染实现注册（自研场景）
  CardIr / TreeNode / Action /                回流桥：ActionEvent → AgentQuery
    ActionEvent（协议 IR，@Serializable）
  CardIrValidator（动作校验/降级）
tool/（纯 JVM，依赖 ir + :agent:core，
  包名 io.github.yeyi.agent.genui.tool）
  UiSink（旁路通道，实例注入）  ────────▶
  UiGenTool（抽象工具基类，实现 Tool）
adapters/（Android 适配层，三个独立子模块）
  ├─ api/：UiRenderAdapter（@Composable 插槽接口）
  ├─ default/：DefaultRenderAdapter（自研声明式后端，
  │            组件契约 = 名字 + 动作集 + 渲染实现）
  └─ a2ui/：A2uiRenderAdapter（投影 + androidx.a2ui 引擎）

工具侧（外部注入）                           UI 使用（无感）
继承 UiGenTool（构造器注入 sink）：            sink.cards.collect { card ->
getResult 业务执行 → buildCard 映射 →            adapter.RenderCard(card) { event ->
  sink.emit，回执照常进记忆                          ActionEvent → AgentQuery → 新一轮推理
```

链路：工具只写 CardIr → UiSink 旁路 → 消费端统一入口 `adapter.RenderCard(card, onAction)`；换后端只改组装点一行，工具、UI、回流全不动。

## 逐节设计

### 节 1 — IR 协议：组件树

```kotlin
@Serializable
public data class CardIr(
    public val instanceId: String,        // 与工具 toolCallId 一一对应，会话恢复锚点
    public val root: TreeNode,
)

@Serializable
public data class TreeNode(
    public val component: String,
    public val bindings: Map<String, JsonElement> = emptyMap(), // 组件属性（文本、数据、样式）
    public val children: List<TreeNode> = emptyList(),
    public val action: Action? = null     // 可点击节点自带的动作声明（id + label 同一处）
)

@Serializable
public data class Action(
    public val id: String,                // 动作唯一 id，回流事件携带（程序用，不需可读）
    public val label: String              // 按钮/可点击区域文案（用户可见），随事件回流供模型共读
)
```

- 组件名由渲染层解释（自研注册表 / A2UI 目录），IR 层不感知具体组件
- 模板 = 渲染层注册的模板组件（名字 + 动作集 + 渲染实现），工具只交 root + 业务数据；模板不进协议
- 未知组件由渲染层降级，不中断渲染

**动作归属：谁拥有可点击物，谁声明动作**

动作只有一处声明，不再「卡级声明一遍 + 节点引用一遍」：

| 卡片形态 | IR 产出 | 动作声明处 |
|---|---|---|
| 声明式卡（`Card`/`Column`/`Button` 等通用组件） | 整棵组件树 | 节点自带 `TreeNode.action`（动作名每卡不同，通用组件无法预声明） |
| 模板卡（root 为自研模板组件） | 单 root 节点 + 业务数据 | 组件注册时声明（动作名是组件的常量），工具不出现按钮节点、不声明动作 |

- 模板卡下工具只交 root + 数据：`CardIr(instanceId, TreeNode("Location", bindings = 数据))`，
  可点击物与动作全在模板里——卡片定义不再一分为二
- 卡内动作 id 唯一：同 id 视为同一动作（多个触发点共享），label 必须一致；id 为空或同 id 不同 label → 整卡降级为纯展示
- 两条路径的差异只在「谁拥有动作」，渲染引擎一律只绑定已声明动作

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

- 时序由 final `execute` 保证：`ToolCallStart → getResult → buildCard → emit → 工具返回 → ToolCallEnd`，卡片落在区间内，锚点天然对齐
- `uiSink` 构造器必传注入（private）：持有者（demo/会话）显式决定卡片流作用域；仅基类 `execute` 收口使用，子类不可见、无发卡权
- `getResult` 直接返回结构化 `JsonElement`（不引入新类型体系），`buildCard` 返回 `CardIr?`：正常数据必产非空卡片，仅数据不合法/为空时返回 null 跳过发射，降级为纯文本回执
- 工具作者只写 CardIr，不感知任何渲染后端（自研/A2UI）——后端无感从工具侧成立

### 节 3 — 校验（CardIrValidator）

动作校验独立为公开校验器，协议层语义收口：

```kotlin
/** 动作校验：id 非空；同 id 必须同 label。 */
public class CardIrValidator {

    /**
     * 校验一批动作声明。
     *
     * @return id 到动作规格的映射；声明非法（id 为空，或同 id 不同 label）时返回 null，
     * 消费方应将整卡降级为纯展示。
     */
    public fun validateActions(actions: List<Action>): Map<String, Action>?

    /**
     * 收集组件树内节点自带的动作并校验。
     *
     * @return id 到动作规格的映射；卡内动作非法时返回 null，消费方应将整卡降级为纯展示。
     */
    public fun collectActions(root: TreeNode): Map<String, Action>?
}
```

- **归属 genui（协议层）**：动作 id 非空、卡内同 id 必须同 label、卡内动作非法整卡降级——是 genui 的安全红线，任何渲染后端都必须遵守
- **自研后端**：DefaultRenderAdapter 渲染前调用（`collectActions`）；组件注册时校验组件自己声明的动作集（`validateActions`），非法直接拒注册——代码错误不留到运行期
- **A2UI 投影后端**：投影 CardIr→A2UI 前调用（A2UI 官方校验只认目录 schema，不覆盖 genui 动作语义）
- 校验失败不抛异常，降级为纯展示（无动作），保证 UI 流永不中断

### 节 4 — 自研渲染后端：DefaultRenderAdapter（声明式）

组件注册绑定平台渲染实现（@Composable），注定留不在纯 JVM 的 ir——渲染底座整体落于 `adapters:default`，ir 退化为纯协议层。

**组件契约 = 组件名 + 组件自己声明的动作集 + 渲染实现**。模板卡下可点击物与动作都归组件，工具只交 root + 数据。

```kotlin
// agent/genui/adapters/default —— 自研后端子模块（DefaultRenderAdapter.kt，包名 …/genui/adapter/default）
/** Compose 组件渲染实现签名：组件拿到动作出口，自行决定要不要发起回流。 */
public typealias UiComponentRenderer = @Composable (
    node: TreeNode,
    scope: UiActionScope,
    children: @Composable () -> Unit,
) -> Unit

/** 组件的动作出口：节点自带动作与组件声明动作合成一个出口。 */
public class UiActionScope {
    public val action: Action?              // 本节点的有效动作（卡内动作非法时为 null）
    public fun emit(actionId: String, payload: JsonObject = ...)   // 发起动作回流
}

/** 自研后端：声明式组件树递归渲染（@Composable 注册表），动作校验复用 ir 的 CardIrValidator。 */
public class DefaultRenderAdapter(
    private val validator: CardIrValidator = CardIrValidator(),
) : UiRenderAdapter {
    public fun register(component: String, actions: List<Action> = emptyList(), renderer: UiComponentRenderer)
    public fun registerFallback(renderer: UiComponentRenderer)

    @Composable
    override fun RenderCard(card: CardIr, onAction: (ActionEvent) -> Unit) {
        val valid = validator.collectActions(card.root) != null   // 卡内动作非法 → 整卡降级
        Column { RenderNode(card, card.root, valid, onAction) }  // 声明式先序递归
    }
}
```

模板卡写法（可点击物与动作都在模板里，工具侧只出 root + 数据）：

```kotlin
register("Location", actions = listOf(Action("navigate", "开启导航"))) { node, scope, _ ->
    Card(node) {
        FieldRow("城市", node.bindingText("city"))
        Button(onClick = { scope.emit("navigate") }) { Text("开启导航") }
    }
}
```

- 注册表、树遍历、未知组件降级、动作绑定由适配器收口：节点自带动作按节点绑定，组件声明动作按组件契约绑定；两者都只经 [UiActionScope] 出口
- `ActionEvent` 由适配器构造（`instanceId` 取自 card，`action` 取自命中的声明，`payload` 取自节点绑定数据），实现方零组装
- **声明式递归**：以 @Composable 组件树替代命令式推模型，天然贴合 Compose 组合模型与布局系统（SubcomposeLayout 桥需在 measure 阶段手算坐标，会丢失声明式嵌套布局能力）
- **ir/tool 退化为协议 + 工具分层**：ir 只承载协议 IR（CardIr/TreeNode/Action/ActionEvent）+ CardIrValidator，零平台、零 agent 依赖；UiSink/UiGenTool 归 tool 模块（依赖 agent 内核 `Tool`）；组件注册与渲染是平台行为（绑定 @Composable），全部收敛在适配器

### 节 5 — 渲染后端插槽：UiRenderAdapter（genui adapters）

**平台边界**：`UiRenderAdapter` 带 `@Composable`（平台类型），定义在 genui adapters 的 `api` 子模块（插槽接口），实现分两个并列后端子模块 `adapters:default` 与 `adapters:a2ui`——ir 承诺「协议和语义」的平台无关，adapters 承诺「渲染」的平台相关。

```kotlin
// agent/genui/adapters/api —— 插槽接口子模块（UiRenderAdapter.kt，包名 io.github.yeyi.agent.genui.adapter）
interface UiRenderAdapter {
    @Composable
    fun RenderCard(card: CardIr, onAction: (ActionEvent) -> Unit)
}

// agent/genui/adapters/default —— 自研后端子模块（DefaultRenderAdapter.kt，包名 …/genui/adapter/default）
/** 自研后端：声明式组件树递归渲染（@Composable 注册表），动作校验复用 ir 的 CardIrValidator。 */
class DefaultRenderAdapter(private val validator: CardIrValidator = CardIrValidator()) : UiRenderAdapter {
    fun register(component: String, actions: List<Action> = emptyList(), renderer: UiComponentRenderer) { ... }
    override fun RenderCard(card: CardIr, onAction: (ActionEvent) -> Unit) { ... }
}

// agent/genui/adapters/a2ui —— A2UI 后端子模块（A2uiRenderAdapter.kt，包名 …/genui/adapter/a2ui）
/** A2UI 后端：CardIr→A2UI 投影（投影前过 CardIrValidator）+ androidx.a2ui 官方引擎状态机 + 回流映射。 */
class A2uiRenderAdapter(
    val catalog: A2uiCoreCatalog,
    val scope: CoroutineScope,
) : UiRenderAdapter {
    fun project(card: CardIr, onAction: (ActionEvent) -> Unit) { ... }
    override fun RenderCard(card: CardIr, onAction: (ActionEvent) -> Unit) = project(card, onAction)
}
```

**配置一次、使用无感**（组装点）：

```kotlin
// demos/agent —— AgentRuntime.kt 组装点（一次性选择后端）
data class GenUi(val sink: UiSink, val adapter: UiRenderAdapter)

fun createGenUi(): GenUi {
    val sink = UiSink()
    val adapter: UiRenderAdapter = DefaultRenderAdapter().apply {
        register("Text") { node, _, _ -> Text(node.bindings["content"]?.jsonPrimitive?.content ?: "") }
        register("Button") { node, scope, _ -> if (scope.action == null) Text(node.label()) else Button(onClick = { scope.emit(scope.action!!.id) }) { Text(scope.action!!.label) } }
        registerDemoCardTemplates()   // 模板卡注册：组件自带动作集，工具侧无感
        registerFallback { _ -> }
    } // 或 A2uiRenderAdapter(MaterialA2uiCatalog(), scope) —— 换后端只改这一行
    return GenUi(sink, adapter)
}
```

**使用**（UI 层唯一入口）：

```kotlin
sink.cards.collect { card ->
    adapter.RenderCard(card, onAction = vm::onCardAction) // 后端无感
}
// onCardAction: ActionEvent → AgentQuery → 新一轮推理（与后端无关）
```

- **插槽语义**：适配层定义、实现可插拔、组装点一次性选择；业务代码只接触 `sink.cards` 与 `adapter.RenderCard(card, onAction)`
- **组件名映射**：A2UI 后端投影时，工具产出的组件名必须映射到 catalog 内组件（`Column`/`Text`/`Button`…），否则官方渲染器不识别；v1 建议工具直接产出语义等价的 catalog 内组件名，避免自定义目录
- **校验收敛**：两个后端各自的 `RenderCard` 入口前都过 `CardIrValidator`，保证「使用无感」覆盖安全语义，业务代码不会绕过校验

### 节 6 — 回流契约

```kotlin
@Serializable
public data class ActionEvent(
    public val instanceId: String,
    public val action: Action,                 // 命中的动作声明（id 程序标识，label 模型共读）
    public val payload: JsonObject = JsonObject(emptyMap())
)
```

回流链路（demo 侧）：

```
用户点击卡片按钮
  → ActionEvent(instanceId, action, payload)
  → demo 组装 AgentQuery（如 "用户点击了卡片按钮『是』"）
  → BossAgent input 新轮次 / ReActAgent 新 run
  → 模型结合卡片与工具调用上下文理解意图，继续推理
```

- **意图由工具调用层承载，卡片是其可视化呈现**：模型调用 `deleteOrder(orderId)` 即表达意图，卡片标题 + 按钮 label（"是/取消"）是给人和模型共读的自然语言，模型无需专用意图描述
- `action` 携带完整动作声明：`action.id` 是程序标识（渲染绑定与回流路由），`action.label` 是可读文案（模型共读）；`payload` 是对象参数（orderId、city 等）——三者分工，模型从卡片 + 历史自然理解意图
- 回流事件构造由渲染后端负责（自研引擎 / A2UI action 回调），demo 统一收 `ActionEvent`，后端无感

### 节 7 — 记忆清洁与锚点恢复

- **记忆清洁**：`CardIr` 只走 `UiSink` 旁路，永不写入记忆；记忆里只有工具回执短文本
- **锚点**：`CardIr.instanceId` = `ToolExecutionContext.toolCallId` = 记忆 `ToolResult.toolCallId`
- **会话恢复**：demo 本地按 dialog id 自持卡片记录，重启后遍历记忆找到对应 `ToolResult` 条目，按锚点将卡片插入正确位置
- 时序：工具在 `ToolCallStart` 与 `ToolCallEnd` 之间 emit（挂起发射，正常场景不阻塞执行），发射顺序由执行位置决定；收集侧合流即使存在调度竞态，锚点机制免疫

### 节 8 — ReActAgent / BossAgent 接入

两处接入均为零代码改动：

- **ReActAgent**：工具外部注入（构造时无需感知通道），内核不感知卡片；demo 订阅 agent 事件流（气泡排序）+ UI 流（`sink.cards` 卡片渲染）自行合并
- **BossAgent**：同上；卡片回流经 `AgentQuery` 进入新轮次，报告轮次语义不受影响

## 目录结构

```
agent/genui/
├── ir/                                                             ← :agent:genui:ir（纯 JVM 协议层，零平台、零 agent 依赖）
│   └── src/main/kotlin/io/github/yeyi/agent/genui/ir/               ← 包名带 ir 后缀
│       ├── CardIr.kt / TreeNode.kt / Action.kt / ActionEvent.kt   ← 协议 IR
│       └── CardIrValidator.kt                                       ← 动作校验/降级（独立公开）
├── tool/                                                            ← :agent:genui:tool（纯 JVM，依赖 ir + :agent:core）
│   └── src/main/kotlin/io/github/yeyi/agent/genui/tool/             ← 包名带 tool 后缀
│       ├── UiSink.kt                                                ← 旁路通道（实例注入）
│       └── UiGenTool.kt                                             ← 工具抽象基类（实现 Tool）
└── adapters/                                                        ← Android 适配层（平台类型）
    ├── api/                                                      ← :agent:genui:adapters:api（插槽接口）
    │   └── src/main/kotlin/io/github/yeyi/agent/genui/adapter/UiRenderAdapter.kt
    ├── default/                                                  ← :agent:genui:adapters:default（自研后端）
    │   └── src/main/kotlin/io/github/yeyi/agent/genui/adapter/default/
    │       ├── DefaultRenderAdapter.kt                              ← 组件契约（名字 + 动作集 + 渲染实现）
    │       ├── UiActionScope.kt                                     ← 组件的动作出口
    │       └── DefaultComponents.kt                                 ← 基础组件集
    └── a2ui/                                                     ← :agent:genui:adapters:a2ui（A2UI 后端）
        └── src/main/kotlin/io/github/yeyi/agent/genui/adapter/a2ui/A2uiRenderAdapter.kt

demos/agent/.../agent/runtime/AgentRuntime.kt   ← 组装点（一次性选择后端，组件实现注册）
demos/agent/.../agent/ui/CardTemplates.kt        ← 模板卡注册（组件自带动作集）
```

- 平台边界钉死：ir 退化为纯协议层（序列化 IR + 校验），tool 承载旁路通道与工具基类，adapters 承诺「渲染」的平台相关；api 只承载 Compose 平台版插槽接口，default/a2ui 分别依赖 api 提供各自后端实现
- 每平台一份 adapters 层（api 定义平台版 `UiRenderAdapter`，default/a2ui 提供该平台的后端实现），ir 协议与校验跨平台复用零改动
- 叶级项目名规避：ir/tool 子项目名 `ir`/`tool` 全局唯一（projectDir 分别指向 `agent/genui/ir`、`agent/genui/tool`），避免与 `:agent:core` 同名导致 Gradle 依赖解析混淆（任务级循环依赖）；模块语义完全进包名（`…genui.ir` / `…genui.tool`），跨模块无包名混淆

## v1 范围与 v2 演进

**v1（本次）**：
- `:agent:genui:ir`：`CardIr` / `TreeNode` / `Action` / `ActionEvent` / `CardIrValidator`（纯协议层，零渲染实现、零 agent 依赖）
- `:agent:genui:tool`：`UiSink`（实例通道）/ `UiGenTool`（抽象工具基类）
- `:agent:genui:adapters:api`：`UiRenderAdapter` 插槽接口
- `:agent:genui:adapters:default`：`DefaultRenderAdapter`（自研后端）
- `:agent:genui:adapters:a2ui`：`A2uiRenderAdapter`（CardIr→A2UI 投影 + 官方引擎状态机 + 回流映射）
- demo 侧：组件渲染实现注册 + `sink.cards` 订阅 + 回流桥 + `GenUi` 组装点 + 锚点恢复
- 覆盖场景：绑定原生工具的卡片，自研后端渲染，两种卡形态——
  声明式卡（`Card`/`Column`/`Button` 等通用组件）：工具出整棵组件树，动作由节点自带（`TreeNode.action`）；
  模板卡（`Location` 等模板组件）：工具只交 root + 数据，动作由组件注册时声明，工具不感知按钮

**v2（演进方向，本次不动）**：
- A2UI 官方 Compose 渲染制品（A2uiSurface）接入：投影 + 引擎状态机 + 回流映射已交付，渲染入口待 androidx.a2ui.compose 发布后接入，换后端只改 `GenUi` 一行
- 模型自由渲染：Assistant 输出增加可选结构化字段，与 content 分离——需改 ir 协议，违背 v1 约束，延后
- 生成式路径：brief → 专职 UI 模型 → 三重校验 → 降级链（oui-1 接入点，经 LLM 网关 provider 变体）
- 确认卡（CONFIRM）与多工具汇总卡：合成工具或协议层出口

## 测试策略

- ir 模块（JVM）：`CardIr` 序列化/反序列化、`CardIrValidator` 动作校验（id 为空/同 id 不同 label → 整卡降级，同 id 同 label → 同一动作）；tool 模块（JVM）：`UiSink` 发射与订阅时序（订阅后 emit 可见 / 无订阅不阻塞不缓存）、`UiGenTool` execute 接线（getData → buildCard → emit 与回执语义、buildCard 返回 null 不发射）
- adapters 侧：`DefaultRenderAdapter`（default）组件契约——组件声明的动作经 `UiActionScope` 回流（载荷为节点绑定数据）、节点自带动作同样可回流、卡内动作非法整卡降级、组件声明非法动作拒注册；`A2uiRenderAdapter`（a2ui）引擎层纯 JVM 状态机独立验证——投影落地（createSurface + updateComponents 后 activeSurfaces 出现对应 surface）、动作回流映射（客户端事件消息 → ActionEvent）、卡内动作非法整卡降级、重复投影幂等且仅更新回调；demo 锚点恢复用本地卡片记录 + 记忆 ToolResult 模拟
