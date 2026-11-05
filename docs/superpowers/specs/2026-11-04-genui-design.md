# 生成式 UI（genui）模块 —— 设计

## 背景与目标

让 Agent 在对话中产出可交互的卡片式 UI（天气卡、导航卡、统计报表等），用户点击卡片动作后事件回流 Agent 继续推理。

**目标**：
- 在 `agent/genui` 下新增 `core/` 与 `adapters/` 两级目录：`core` 为 `:agent:genui:genui-core`（叶级项目名唯一，规避与 `:agent:core` 同名导致的 Gradle 依赖解析混淆；projectDir 指向 `agent/genui/core`）纯 JVM（协议、校验、自研渲染底座，包名 `io.github.yeyi.agent.genui` 不带 core 后缀）；`adapters` 为 Android 适配层，下分 `api/`（插槽接口）、`default/`（自研后端）、`a2ui/`（A2UI 后端）三个独立子模块，现有模块零改动（含 core）
- core 保持纯 JVM、零平台依赖：协议、校验、自研渲染底座都不绑定任何 UI 框架
- 渲染后端可插拔：自研渲染与三方协议（A2UI）以统一适配器接口平级接入，demo 组装一次选择，业务代码无感
- v1 先覆盖「绑定原生工具的卡片」：数据与卡片逻辑内聚在同一工具内，工具执行时对外发卡片

**非目标**（v1 不做）：
- 无工具场景（模型自由渲染）——v1 不在 core/协议层加结构化输出字段，见「v2 演进」
- 协议适配（A2UI 官方渲染器接入）——作为可插拔后端的一个实现，`A2uiRenderAdapter` 已交付投影 + 引擎状态机 + 回流映射；官方 Compose 渲染制品（A2uiSurface）待 androidx.a2ui.compose 发布后接入
- 编排层集中编译——本 SDK 无编排层，采用工具内嵌旁路通道

## 约束

1. 新增 `agent/genui/core` 子模块（纯 JVM）与 `agent/genui/adapters` 目录（内含 `api/`、`default/`、`a2ui/` 三个独立子模块），现有模块零改动（含 core）
2. core 纯 JVM、零平台依赖：协议/校验/引擎不引入 Compose 等平台类型；core 源码包名 `io.github.yeyi.agent.genui`，不带 core 后缀
3. 平台类型（`@Composable`、androidx.a2ui）只允许出现在 genui adapters 及其子模块，不污染 core
4. 渲染后端可插拔：自研（RenderEngine）与三方协议（A2UI）以统一适配器接口平级接入，demo 组装点一次性选择
5. demo 只组装（注册组件实现 + 选择后端 + 回流桥），不写适配器实现

## 核心决策

| 决策点 | 结论 |
|---|---|
| 模块归属 | 嵌套结构：`:agent:genui:genui-core`（纯 JVM：`kotlin.jvm` + `kotlin.serialization`，`api(project(":agent:core"))`）→ adapters 下三个独立子模块：`:agent:genui:adapters:api`（Android：`com.android.library` + Compose，`api(project(":agent:genui:genui-core"))`，承载 `UiRenderAdapter` 插槽接口）→ `:agent:genui:adapters:default`（自研后端）与 `:agent:genui:adapters:a2ui`（A2UI 后端，依赖 androidx.a2ui），两者平级依赖 api |
| UI 事件通道 | 旁路 `UiSink`（普通类，按实例注入：demo/会话持有实例注入工具并订阅 `cards: SharedFlow<CardIr>`，`emit` 挂起发射）+ `UiGenTool` 抽象基类（实现 Tool，`uiSink` 构造器必传注入，`execute` final 模板接线） |
| 卡片记忆策略 | 卡片 IR 完全绕过记忆，工具回执文本照常进记忆，避免 token 污染 |
| IR 形态 | 统一组件树（TREE），模板概念下沉为「工具内代码复用」，IR 层不做模板注册表 |
| 动作契约 | 动作集合在卡片 IR 内静态声明（白名单），任何渲染后端只绑定白名单内动作 |
| 校验归属 | 白名单/降级校验独立为公开校验器 `CardIrValidator`（协议层语义收口），自研后端与投影后端共用；A2UI 官方校验不覆盖 genui 动作白名单语义 |
| 渲染后端插槽 | genui adapters 的 `api` 子模块定义平台版 `UiRenderAdapter`（@Composable）插槽接口，实现分两个并列后端子模块：`adapters:default` 的 `DefaultRenderAdapter`（包装 RenderEngine）与 `adapters:a2ui` 的 `A2uiRenderAdapter`（CardIr→A2UI 投影 + androidx.a2ui 官方引擎状态机 + 回流映射）；demo 组装点一次性选择，业务代码统一走 `adapter.RenderCard(card, onAction)` 无感 |
| 自研渲染 | `Renderer` 命令式契约 + `RenderEngine`（注册表/树遍历/降级/动作绑定）作为自研底座，以 `DefaultRenderAdapter` 适配器形态出场，与其他后端平级 |
| 组件契约 | 自研后端用 RenderEngine 注册表；A2UI 后端用 catalog（目录即契约），投影时组件名映射到目录内组件 |
| 回流路径 | 卡片动作事件 → demo 组装 AgentQuery → 新一轮推理（BossAgent input / ReActAgent 新 run） |
| 会话恢复 | `CardIr.instanceId` = 工具 `toolCallId`，demo 本地卡片记录按锚点归位 |
| ReActAgent / BossAgent 接入 | 零改动：工具是外部注入的，demo 订阅 agent 事件流 + UI 流自行合并 |

## 架构总览

```
agent/genui（两级嵌套）                       demos/agent（消费端）
─────────────────────                     ──────────────────────────────
core/（纯 JVM，零平台依赖，                 genui/GenUi（组装点：一次性选择后端）
  包名 io.github.yeyi.agent.genui）            组件渲染实现注册（自研场景）
  CardIr / TreeNode / Action /                回流桥：UiActionEvent → AgentQuery
    UiActionEvent（协议 IR，@Serializable）
  UiSink（旁路通道，实例注入）  ────────▶
  CardIrValidator（白名单/降级校验）
  Renderer + RenderEngine（自研底座）
adapters/（Android 适配层，三个独立子模块）
  ├─ api/：UiRenderAdapter（@Composable 插槽接口）
  ├─ default/：DefaultRenderAdapter（包装 RenderEngine）
  └─ a2ui/：A2uiRenderAdapter（投影 + androidx.a2ui 引擎）

工具侧（外部注入）                           UI 使用（无感）
继承 UiGenTool（构造器注入 sink）：            sink.cards.collect { card ->
getResult 业务执行 → buildCard 映射 →            adapter.RenderCard(card) { event ->
  sink.emit，回执照常进记忆                          UiActionEvent → AgentQuery → 新一轮推理
```

链路：工具只写 CardIr → UiSink 旁路 → 消费端统一入口 `adapter.RenderCard(card, onAction)`；换后端只改组装点一行，工具、UI、回流全不动。

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

- 组件名由渲染层解释（自研注册表 / A2UI 目录），IR 层不感知具体组件
- 模板 = 工具内静态 `TreeNode` 骨架 + 运行期数据填充，属工具作者代码复用，不进协议
- 未知组件由渲染层降级，不中断渲染

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

白名单/降级校验独立为公开校验器，从 RenderEngine 剥离，协议层语义收口：

```kotlin
/** 动作白名单校验：动作 id 非空且唯一。 */
public class CardIrValidator {

    /**
     * 校验卡片动作白名单。
     *
     * @return 白名单 id 到动作规格的映射；白名单非法（id 为空或重复）时返回 null，
     * 消费方应将整卡降级为纯展示。
     */
    public fun validateActions(actions: List<Action>): Map<String, Action>?
}
```

- **归属 genui（协议层）**：动作 id 非空唯一、节点动作命中白名单、白名单非法整卡降级——是 genui 的安全红线，任何渲染后端都必须遵守
- **自研后端**：RenderEngine 渲染前调用
- **A2UI 投影后端**：投影 CardIr→A2UI 前调用（A2UI 官方校验只认目录 schema，不覆盖 genui 动作白名单语义）
- 校验失败不抛异常，降级为纯展示（无动作），保证 UI 流永不中断

### 节 4 — 自研渲染底座：Renderer + RenderEngine

引擎逻辑全部在 genui 模块，框架无关（不依赖 Compose）。组件渲染实现由 demo 注册。

```kotlin
public fun interface Renderer {
    /**
     * 组件渲染实现：框架无关，实现方可调起任意 UI 框架（Compose、View 等）。
     *
     * @param node 当前节点，实现方读取 [TreeNode.bindings] 渲染内容
     * @param action 已通过白名单校验的动作规格；null 表示该节点不可点击，实现方应渲染为静态内容
     * @param emit 触发动作回流：仅当 [action] 非 null 时可用，由引擎自动构造 [UiActionEvent]，实现方零组装
     */
    public fun render(node: TreeNode, action: Action?, emit: () -> Unit)
}

public class RenderEngine {
    private val registry: MutableMap<String, Renderer> = HashMap()
    private var fallback: Renderer = Renderer { _, _, _ -> }

    public fun register(component: String, renderer: Renderer)
    public fun registerFallback(renderer: Renderer)

    /** 校验动作白名单后递归渲染整棵组件树（先序）。 */
    public fun render(card: CardIr, emit: (UiActionEvent) -> Unit)
}
```

- 组件注册表、树遍历、未知组件降级、动作绑定（`node.action` 必须命中 `card.actions` 白名单，否则渲染为静态内容）由引擎收口
- `UiActionEvent` 由引擎构造（`instanceId` 取自 card，`actionId`/`payload` 取自节点与绑定数据），实现方零组装
- **定位：自研渲染底座**。对外不直接暴露为渲染入口，以 `DefaultRenderAdapter` 适配器形态接入 `UiRenderAdapter` 插槽
- **命令式→声明式桥**（SubcomposeLayout 等）只存在于 `adapters:default` 的 `DefaultRenderAdapter` 内部，不进公共入口

### 节 5 — 渲染后端插槽：UiRenderAdapter（genui adapters）

**平台边界**：`UiRenderAdapter` 带 `@Composable`（平台类型），定义在 genui adapters 的 `api` 子模块（插槽接口），实现分两个并列后端子模块 `adapters:default` 与 `adapters:a2ui`——core 承诺「协议和语义」的平台无关，adapters 承诺「渲染」的平台相关。

```kotlin
// agent/genui/adapters/api —— 插槽接口子模块（UiRenderAdapter.kt，包名 io.github.yeyi.agent.genui.adapter）
interface UiRenderAdapter {
    @Composable
    fun RenderCard(card: CardIr, onAction: (UiActionEvent) -> Unit)
}

// agent/genui/adapters/default —— 自研后端子模块（DefaultRenderAdapter.kt，包名 …/genui/adapter/default）
/** 自研后端：桥接 RenderEngine + @Composable 组件渲染实现注册（含命令式→声明式桥）。 */
class DefaultRenderAdapter(private val engine: RenderEngine) : UiRenderAdapter {
    fun register(component: String, renderer: UiComponentRenderer) { ... }
    override fun RenderCard(card: CardIr, onAction: (UiActionEvent) -> Unit) { ... }
}

// agent/genui/adapters/a2ui —— A2UI 后端子模块（A2uiRenderAdapter.kt，包名 …/genui/adapter/a2ui）
/** A2UI 后端：CardIr→A2UI 投影（投影前过 CardIrValidator）+ androidx.a2ui 官方引擎状态机 + 回流映射。 */
class A2uiRenderAdapter(
    val catalog: A2uiCoreCatalog,
    val scope: CoroutineScope,
) : UiRenderAdapter {
    fun project(card: CardIr, onAction: (UiActionEvent) -> Unit) { ... }
    override fun RenderCard(card: CardIr, onAction: (UiActionEvent) -> Unit) = project(card, onAction)
}
```

**配置一次、使用无感**（组装点）：

```kotlin
// demos/agent —— GenUi.kt 组装点（一次性选择后端）
data class GenUi(val sink: UiSink, val adapter: UiRenderAdapter)

fun createGenUi(): GenUi {
    val sink = UiSink()
    val adapter: UiRenderAdapter = DefaultRenderAdapter(
        RenderEngine().apply {
            register("Text") { node, _, _ -> Text(...) }
            register("Button") { node, action, emit -> ... }
            registerFallback { ... }
        }
    ) // 或 A2uiRenderAdapter(MaterialA2uiCatalog()) —— 换后端只改这一行
    return GenUi(sink, adapter)
}
```

**使用**（UI 层唯一入口）：

```kotlin
sink.cards.collect { card ->
    adapter.RenderCard(card, onAction = vm::onCardAction) // 后端无感
}
// onCardAction: UiActionEvent → AgentQuery → 新一轮推理（与后端无关）
```

- **插槽语义**：适配层定义、实现可插拔、组装点一次性选择；业务代码只接触 `sink.cards` 与 `adapter.RenderCard(card, onAction)`
- **组件名映射**：A2UI 后端投影时，工具产出的组件名必须映射到 catalog 内组件（`Column`/`Text`/`Button`…），否则官方渲染器不识别；v1 建议工具直接产出语义等价的 catalog 内组件名，避免自定义目录
- **校验收敛**：两个后端各自的 `RenderCard` 入口前都过 `CardIrValidator`，保证「使用无感」覆盖安全语义，业务代码不会绕过校验

### 节 6 — 回流契约

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
- 回流事件构造由渲染后端负责（自研引擎 / A2UI action 回调），demo 统一收 `UiActionEvent`，后端无感

### 节 7 — 记忆清洁与锚点恢复

- **记忆清洁**：`CardIr` 只走 `UiSink` 旁路，永不写入记忆；记忆里只有工具回执短文本
- **锚点**：`CardIr.instanceId` = `ToolExecutionContext.toolCallId` = 记忆 `ToolResult.toolCallId`
- **会话恢复**：demo 本地按 dialog id 自持卡片记录，重启后遍历记忆找到对应 `ToolResult` 条目，按锚点将卡片插入正确位置
- 时序：工具在 `ToolCallStart` 与 `ToolCallEnd` 之间 emit（挂起发射，正常场景不阻塞执行），发射顺序由执行位置决定；收集侧合流即使存在调度竞态，锚点机制免疫

### 节 8 — ReActAgent / BossAgent 接入

两处接入均为零代码改动：

- **ReActAgent**：工具外部注入（构造时无需感知通道），core 不感知卡片；demo 订阅 agent 事件流（气泡排序）+ UI 流（`sink.cards` 卡片渲染）自行合并
- **BossAgent**：同上；卡片回流经 `AgentQuery` 进入新轮次，报告轮次语义不受影响

## 目录结构

```
agent/genui/
├── core/                                          ← :agent:genui:genui-core（纯 JVM，零平台依赖）
│   └── src/main/kotlin/io/github/yeyi/agent/genui/   ← 包名不带 core
│       ├── CardIr.kt / TreeNode.kt / Action.kt / UiActionEvent.kt   ← 协议 IR
│       ├── UiSink.kt / UiGenTool.kt                                  ← 旁路通道与工具基类
│       ├── CardIrValidator.kt                                        ← 白名单/降级校验（独立公开）
│       └── Renderer.kt / RenderEngine.kt                             ← 自研渲染底座
└── adapters/                                                      ← Android 适配层（平台类型）
    ├── api/                                                      ← :agent:genui:adapters:api（插槽接口）
    │   └── src/main/kotlin/io/github/yeyi/agent/genui/adapter/UiRenderAdapter.kt
    ├── default/                                                  ← :agent:genui:adapters:default（自研后端）
    │   └── src/main/kotlin/io/github/yeyi/agent/genui/adapter/default/DefaultRenderAdapter.kt
    └── a2ui/                                                     ← :agent:genui:adapters:a2ui（A2UI 后端）
        └── src/main/kotlin/io/github/yeyi/agent/genui/adapter/a2ui/A2uiRenderAdapter.kt

demos/agent/.../genui/GenUi.kt               ← 组装点（一次性选择后端，组件实现注册）
```

- 平台边界钉死：core 承诺「协议和语义」的平台无关，adapters 承诺「渲染」的平台相关；api 只承载 Compose 平台版插槽接口，default/a2ui 分别依赖 api 提供各自后端实现
- 每平台一份 adapters 层（api 定义平台版 `UiRenderAdapter`，default/a2ui 提供该平台的后端实现），core 与 RenderEngine 跨平台复用零改动
- 叶级项目名规避：genui 下 core 子项目名为 `genui-core`（projectDir 指向 `agent/genui/core`），避免与 `:agent:core` 同名导致 Gradle 依赖解析混淆（任务级循环依赖）

## v1 范围与 v2 演进

**v1（本次）**：
- `:agent:genui:genui-core`：`CardIr` / `TreeNode` / `Action` / `UiActionEvent` / `UiSink`（实例通道）/ `UiGenTool` / `CardIrValidator` / `Renderer` + `RenderEngine`
- `:agent:genui:adapters:api`：`UiRenderAdapter` 插槽接口
- `:agent:genui:adapters:default`：`DefaultRenderAdapter`（自研后端）
- `:agent:genui:adapters:a2ui`：`A2uiRenderAdapter`（CardIr→A2UI 投影 + 官方引擎状态机 + 回流映射）
- demo 侧：组件渲染实现注册 + `sink.cards` 订阅 + 回流桥 + `GenUi` 组装点 + 锚点恢复
- 覆盖场景：绑定原生工具的卡片（数据 + 布局 + 动作内聚在工具内），自研后端渲染

**v2（演进方向，本次不动）**：
- A2UI 官方 Compose 渲染制品（A2uiSurface）接入：投影 + 引擎状态机 + 回流映射已交付，渲染入口待 androidx.a2ui.compose 发布后接入，换后端只改 `GenUi` 一行
- 模型自由渲染：Assistant 输出增加可选结构化字段，与 content 分离——需改 core 协议，违背 v1 约束，延后
- 生成式路径：brief → 专职 UI 模型 → 三重校验 → 降级链（oui-1 接入点，经 LLM 网关 provider 变体）
- 确认卡（CONFIRM）与多工具汇总卡：合成工具或协议层出口

## 测试策略

- core 模块（JVM）：`CardIr` 序列化/反序列化、`CardIrValidator` 白名单校验（空/重复/降级）、未知组件降级、`RenderEngine` 树遍历顺序与 `UiActionEvent` 构造、`UiSink` 发射与订阅时序（订阅后 emit 可见 / 无订阅不阻塞不缓存）、`UiGenTool` execute 接线（getResult → buildCard → emit 与回执语义、buildCard 返回 null 不发射）
- adapters 侧：`DefaultRenderAdapter`（default）渲染与点击回流；`A2uiRenderAdapter`（a2ui）引擎层纯 JVM 状态机独立验证——投影落地（createSurface + updateComponents 后 activeSurfaces 出现对应 surface）、动作回流映射（客户端事件消息 → UiActionEvent）、白名单外节点不绑定动作、白名单非法整卡降级、重复投影幂等且仅更新回调；demo 锚点恢复用本地卡片记录 + 记忆 ToolResult 模拟
