# Agent SDK (Kotlin / Android)

一个面向 Android 的 Agent SDK,围绕四个垂直领域构建:**Agent 核心**、
**IM 消息网关**、**实时语音对话**、**多 Agent 编排**。这四块在同一个
Kotlin 仓库里同源演进,共享底层抽象,各取所需。

## 这是什么

四块能力,对应仓库里四个根目录:

| 领域 | 目录 | 一句话 |
| --- | --- | --- |
| **Agent 核心** | `agent/` | 完整的 ReAct 循环 + Tool + Skill + Subagent + Hook,以及 Toolset 批量装配、Toolset 共享 `sub_tool_delegate`、记忆轮次压缩等生产可用性优化。给"想自己造 Agent 的 App"用。 |
| **IM 网关** | `gateway/` | 守护进程形态的多平台消息网关。把飞书 / Telegram / 微信等聊天平台的私聊 / 群消息路由到 Agent 推理,统一引擎屏蔽各平台协议差异。给"想在聊天平台里挂 Bot"用。 |
| **实时语音** | `realtime/` | S2S(Speech-to-Speech)双向流式会话,把对话从文本扩展到"边听边说"。`core` 抽象 + `audio:android` 音频采集/播放 + `providers:volc*` 火山引擎实现。给"想做语音 Agent"用。 |
| **多 Agent 编排** | `team/` | Boss + Worker(牛马)多 Agent 协同。Boss 面对用户、做决策、把任务委派给 Worker 并行的执行,工作进度通过 BulletinBoard 同步。给"想做一个团队而不是一个 Agent"用。 |

四个领域相对独立:`agent/` 是地基,其余三块都跑在它之上。要么只用
Agent 核心(SDK 形态),要么叠加 Gateway 做 IM Bot,要么叠加 Realtime
做语音,要么叠加 Team 做多 Agent——可以按需挑。

## 为什么需要它

在做 Android 上的 AI 应用时,大多数现有方案都会卡在两个常见痛点上:

1. **现有 Agent 框架几乎都是 Python / 服务端**。如果你的产品形态是
   Android App,你只能选择"自己包一层后端代理"或"用裸 LLM SDK 重新造
   轮子"。前者牺牲了端侧隐私、延迟、离线性;后者把 ReAct 循环、工具
   调度、记忆压缩、Hook 生命周期这些通用问题都留给每个应用各自实现。
   这个 SDK 把这些通用问题在 Kotlin 端收敛成可复用的库,让 Android
   工程师像调普通 Android 库一样构建 Agent。

2. **聊天平台 Bot 和 App 内 Agent 是两套互不通用的技术栈**。一个团队
   往往既要做"App 里的对话功能",又要做"飞书 / 企微 / Telegram 上的
   客服机器人",但这两件事在大多数代码库里是分开维护的,各写一套消息
   路由、各接一套 LLM、各管一套会话。本仓库把 Agent 核心抽象与消息
   平台适配器分到两层:Gateway 用 SDK 跑 Agent,平台适配器只负责消息
   事件 ↔ SDK 输入的翻译,新增一个聊天平台只需要写一个适配器。

设计上还有几条贯穿整个仓库的原则,这些是写代码时的硬约束:

- **本地优先**。核心场景是设备内 Agent,SDK 不强制要求服务端。Gateway
  是另一个独立的使用形态,不是 SDK 的运行时依赖。
- **协议无关**。业务代码不直连任何 LLM SDK、不直连任何消息平台 SDK。
  所有外部协议都通过接口层隔离,换实现不需要改业务。
- **正交扩展**。Tool、Memory、Hook、Skill、Subagent 五个扩展点互不耦合,
  可以独立替换或叠加,组合方式在 DSL 中显式表达。
- **流式是一等公民**。核心 API 形态是 `Flow`,批模式只是流模式的一个
  子集,不是"先有非流式再补一个流式"。
- **强类型 DSL**。Agent 用 `agent { ... }` DSL 构建,配置即代码,编译
  期就能发现错配。

下文按四个领域分别讲。

---

## 1. Agent 核心(`agent/`)

**这是 SDK 的本体**。其他三块都跑在它之上,直接用这块也行。
你将得到一组完整的构建 Agent 所需的组件:`agent { }` DSL、ReAct 推理
循环、Tool 调用、Skill 加载、Subagent 委派、Hook 生命周期钩子、Memory
抽象、LLM Provider 抽象,以及让这些组件在真实业务里"跑得起来"的一
堆工程优化。

### 设计意图

Agent 不是"一个会聊天的对象",而是一组**正交扩展点**的有机组合:

- **Tool** —— 单次调用的能力,LLM 在每次推理里可以选择调用。
- **Skill** —— 按需加载的指令包,指导 LLM 如何在某个场景下表现。
- **Subagent** —— 把任务委派给更专精的子 Agent,主 Agent 收回结果。
- **Hook** —— 在 Agent 的完整生命周期(LLM 调用、Tool 调用、记忆压缩、
  终态)上做可注入的副作用。日志、监控、审计、安全策略都挂在这里。
- **MCP** —— 把外部 Model Context Protocol Server 包装成 LLM 可调用的工具。
- **Toolset** —— 把一组相关 Tool 作为一个"集合"管理,支持按需启用 / 关闭。
- **Memory** —— 多轮对话历史的存储抽象,负责自动压缩旧轮次。

这五个扩展点各自独立可替换:换 LLM 厂商不动 Tool,加新 Tool 不动
Memory,加一个审计 Hook 不动 Skill。它们在 DSL 中显式组合,而不是隐式
地塞在一个大配置文件里。

### 架构

```
                ┌──────────────────────────────────────────────┐
                │                  Agent (DSL)                 │
                │     agent { llmProvider(…) memory(…)        │
                │            tool(…) tools(…) hook(…) … }      │
                └────────────────────┬─────────────────────────┘
                                     │ build()
                                     ▼
                ┌──────────────────────────────────────────────┐
                │                  ReActAgent                  │
                │       (Reason + Act 循环,直到产出终态)         │
                └─┬──────┬──────┬──────┬───────┬───────┬───────┘
                  │      │      │      │       │       │
                  ▼      ▼      ▼      ▼       ▼       ▼
               LLM    Memory  Tool  Skill   Subagent  Hook
              Prov.  (历史压缩) (Tool) (按需加载) (委派) (生命周期)
                  │      │      │      │       │
                  │      │      └──────┴───────┴──── Toolset /
                  │      │                          MCP 包装
                  ▼      ▼
            (Anthropic / OpenAI / 任何兼容 OpenAI 协议的端点)
                    (InMemory / JSONL / 自定义)
```

横切关注点(日志、监控、限流、审计)不靠"埋点",统一挂在 Hook 上。

### 集成示例

构造一个能查本地天气、能调子 Agent、跨进程持久化历史、带审计 Hook 的
Agent。下面这段与 `:demos:agent` 的 `DemoAgentFactory` 同源,可对照参考:

```kotlin
// 1) 选 LLM Provider — OpenAI / Anthropic 任选其一
val llmProvider = OpenAiProvider(
    apiKey  = BuildConfig.MODEL_API_KEY,
    model   = BuildConfig.MODEL_NAME,
    baseUrl = BuildConfig.MODEL_BASE_URL,
)

// 2) 通过 SessionManager 拿一个 Session,持久化(默认 JSONL)由它内部管。
//    用户面向的是 session.memory,不需要自己 new 任何 Memory 实现。
//    注意:accountId 是 app 自身账户,不是聊天对方的 userId。
val sessionManager = SessionManager(File(baseDir, "sessions"), HookPipeline())
val session = sessionManager.create(
    accountId   = "<your-app-account>",
    sessionName = "为什么会有四季交替？"
)

// 3) 准备扩展点的 Registry
val skillRegistry = SkillRegistry().apply {
    register(NewsSkill())
    register(WeatherSkill())
}
val toolsetRegistry = ToolsetRegistry().apply {
    register(deviceToolset)
    register(iotToolset)
}
val subagentRegistry = SubagentRegistry().apply {
    register(BriefingSubAgent())
}

// 4) 组装 Agent
val agent: Agent = agent {
    persona(Persona("你是一个 helpful 助手,优先使用工具完成任务。"))
    llmProvider(llmProvider)
    memory(session.memory, maxRounds = 30)   // memory 由 session 持有

    tool(GetWeatherTool(weatherApiKey))   // 单个 Tool
    skills(skillRegistry)                  // 一组 Skill — 按场景加载的指令
    toolsets(toolsetRegistry)              // 一组 Toolset — 共享 sub_tool_delegate
    subagents(subagentRegistry)            // 一组 Subagent — 委派给专精子 Agent

    hook(HookPipeline(logging = true))     // 现成的日志 hook; 也可自己实现 AgentHook
}

// 5a) 流式跑:
agent.runStream(userInput).collect { event ->
    when (event) {
        is AgentEvent.ToolCallStart  -> ui.showToolStart(event.toolName)
        is AgentEvent.ToolCallEnd    -> ui.showToolEnd(event.result.content)
        is AgentEvent.TextDelta      -> ui.appendText(event.text)
        is AgentEvent.Final          -> ui.appendText(event.result.message.content ?: "")
        is AgentEvent.Failed         -> ui.showError(event.cause)
        else                         -> Unit
    }
}

// 5b) 或直接拿终态:
val final: AgentResult = agent.run(userInput).awaitResult()
val text: String? = final.message.content
```

实现自定义 `AgentHook` 时要实现完整 8 个回调(参见
`agent/core/.../AgentHook.kt`)。常见做法是组合现成的 `HookPipeline` 而
非全部手写,只在确实需要的地方挂自定义 `Hook`。

这套组件在生产环境里"跑得稳"靠几个关键工程细节:

- **Tool JSON Schema 走 `ToolParameters` sealed type**(`Empty` /
  `JsonSchema`),送给 LLM 时通过 `Tool.toDefinition()` 转成统一
  `ToolDefinition`,避免每个 Provider 各自序列化。
- **Toolset 共享 `sub_tool_delegate`** —— 多个 Toolset 不是各自暴露
  一堆 Tool,而是合并成一个 `load_toolset` Tool + 共享的
  `sub_tool_delegate`,LLM 一次选择 Toolset,二次再选具体子 Tool。
  这把 LLM 可见 Tool 数控制在合理范围。
- **Memory 自动轮次压缩** —— `memory(memory, maxRounds)` 内部用
  `RoundsBoundedMemory` 装饰,超过保留轮次的旧消息会被摘要压缩;持久化
  通过 `SessionManager` + 默认 JSONL 实现,业务代码面向 `session.memory`,
  不需要直接 new `Memory` 实现。
- **Tool 注册顺序保留** —— `ToolRegistry` 用 `LinkedHashMap`,
  LLM 看到 Tool 的顺序就是用户声明的顺序,便于在 prompt 设计里
  "把常用 Tool 放前面"。

---

## 2. IM 消息网关(`gateway/`)

**守护进程形态的多平台消息网关**。当你想让一个 Agent 跑在飞书 /
Telegram / 微信里、可以接受私聊和群消息时,用这一块。

### 设计意图

Gateway 把"消息平台"和"Agent 推理"严格切成两层:

- **Gateway Core**(`gateway:core`)—— 无平台依赖的引擎。它定义
  `GatewayEngine`(主循环)、`AgentRunner`(把消息委托给 Agent)、`HookPipeline`、
  `GatewaySessionManager`(会话存储),完全不感知飞书协议。
- **Gateway Platforms**(`gateway:platforms:feishu` 等)—— 各平台的
  协议适配器。它只做"平台事件 ↔ 引擎消息"的翻译,不碰 Agent。
- **Gateway 守护进程**(`gateway:jvm`)—— 长跑在服务器上的进程入口。
  加载 Core + 平台适配器 + Agent 工厂,提供一个 `./gradlew run` 就能起
  的 daemon。
- **Gateway Android 客户端**(`gateway:app`)—— 让 Android 设备以客户
  端身份连接到 Gateway,可作为轻量远程 Agent 入口。

新增一个聊天平台只需要写一个适配器,不需要重写 Core 或 Agent 部分。
Core 与 Agent 之间通过 `AgentRunner` 接口解耦,Gateway 完全不知道下
面跑的是什么形态的 Agent。

### 架构

```
                    ┌─────────────────────────────────┐
                    │       飞书 / Telegram / 微信     │  ← 平台侧
                    └────────────────┬────────────────┘
                                     │ WebSocket / 长轮询
                                     ▼
        ┌────────────────────────────────────────────────┐
        │       平台适配器 (gateway:platforms:feishu)      │  ← 翻译层
        │   平台事件  ──▶  IncomingMessage                │     (只翻译, 不决策)
        │   平台回包  ◀──  OutgoingMessage                │
        └────────────────┬───────────────────────────────┘
                         │
                         ▼
        ┌────────────────────────────────────────────────┐
        │             GatewayEngine (gateway:core)        │  ← 引擎
        │   ┌──────────────────────────────────────┐     │
        │   │ AgentRunner ──▶ 你的 Agent (agent { }) │     │
        │   └──────────────────────────────────────┘     │
        │   HookPipeline / GatewaySessionManager / 配置    │
        └────────────────────────────────────────────────┘
```

`gateway:jvm` 把以上全部装配起来跑成 daemon。`gateway:app` 是 Android
端的反向用法:不是守护进程,而是把手机里的 Agent 暴露给外部消息平台。

### 集成示例

构建一个飞书 Bot,要求 daemon 把每条消息路由到 Agent,并按 account /
session 维度持久化:

```kotlin
val llmProvider: LlmProvider = resolveLlmProvider(
    providerRaw = config.modelProvider,
    apiKey      = config.modelApiKey,
    baseUrl     = config.modelBaseUrl,
    model       = config.modelName,
)

val baseDir = File(config.appStorageDir)
val sessionManager = SessionManager(baseDir)

// 每个 (accountId, sessionId) 维度拿一个 Agent。
val createAgent: suspend (String, String, String) -> Agent = { accountId, sessionId, sessionName ->
    val session = sessionManager.getOrCreate(accountId, sessionName, sessionId)
    agent {
        memory(session.memory)
        llmProvider(llmProvider)
        tool(GetWeatherTool(weatherApiKey))
    }
}

// 1. 配引擎
val engine = GatewayEngineBuilder()
    .withConfig(GatewayConfig(maxConcurrentSessions = 10))
    .withFileSessionStorage(baseDir)
    .withAgentRunner(DefaultAgentRunner(createAgent))
    .build()

// 2. 挂上飞书适配器(同理可挂 Telegram / 微信)
engine.registerAdapter(FeishuAdapter(FeishuConfig(appId, appSecret), scope))

// 3. 启动
engine.start()
```

`gateway:jvm` 把上面这段封成一个可启动的守护进程(命令行入口 +
`application.properties` + env 覆盖),直接 `./gradlew :gateway:jvm:run`
就能跑。

---

## 3. 实时语音对话(`realtime/`)

**S2S(Speech-to-Speech)双向流式会话**。把对话从"打字"扩展到"边听边说"。

### 设计意图

Realtime 把"实时语音"切成三层,做到"换厂商不需要改业务":

- **`realtime:core`** —— 公开 API 与设备无关的协议抽象。核心是
  `RealtimeSession`(WebSocket 会话抽象)、`RealtimeAdapter`(协议适配器
  接口,把厂商协议帧统一成内部事件)、`RealtimeAppliance`(把麦克风
  采集 / 服务端音频 / 扬声器播放 / 委派注入串成一个开箱即用的会话)。
- **`realtime:audio:android`** —— Android 音频采集 / 播放实现,对接
  `AudioRecord` / `AudioTrack`。
- **`realtime:providers:volc*`** —— 火山引擎 S2S 协议实现(`RealtimeAdapter`
  的具体厂商实现)。火山之外再加厂商只需写一个新的 `:providers:*` 模块。

核心 API 形态是 `Flow<RealtimeEvent>`:用户开始说话、模型开始响应、
模型输出音频、模型中断等所有事件都作为流项出现,业务侧用 `collect`
做反应。

### 架构

```
      麦克风 (MicrophoneAdapter)
            │
            │ PCM 帧
            ▼
┌────────────────────────────────────────────┐
│              RealtimeAppliance             │
│   ┌──────────────┐  ┌──────────────────┐   │
│   │ RealtimeSes- │  │ RealtimeSpeaker  │   │
│   │ sion(WS)     │  │ (SpeakerAdapter) │   │
│   └──────┬───────┘  └───────▲──────────┘   │
│          │ events          │ audio         │
│          │                 │               │
│          ▼                 │               │
│   RealtimeAdapter  ───────▶│               │
│  (协议帧 ↔ 内部事件)        │               │
└────────────────────────────│───────────────┘
                             │
                  ┌──────────┴───────────┐
                  │ RealtimeDelegation?  │  ← 可选:把 S2S 内部事件
                  │ (注入文本 / 中断响应)  │     路由回 agent 推理
                  └──────────────────────┘
```

`RealtimeDelegation` 是 S2S 与 Agent 之间的桥:它让"模型当前说的内容"
和"用户当前听到的内容"可以被另一个 Agent 实时介入,做打断 / 改写 / 注
入等高级语义控制。

### 集成示例

把火山引擎 S2S 会话接到 Android 麦克风 / 扬声器,启用"语音 Agent"委派
(让主 Agent 接管对话语义)。`RealtimeTool` 是 realtime 模块自己定义
的轻量工具接口(`name` / `description` / `parametersSchema` +
`suspend fun execute(arguments): String`),与 `agent:core` 的 `Tool` 解
耦:

```kotlin
val adapter    = VolcRealtimeAdapter()              // 火山协议适配器
val session    = RealtimeSession(HttpClient(), adapter)
val microphone = AndroidMicrophoneAdapter()
val speaker    = AndroidSpeakerAdapter()

// 自定义 RealtimeDelegation,把 S2S 内部事件路由回你的 Agent
val delegation = object : RealtimeDelegation {
    override val capabilities = listOf("调暗客厅灯", "调节空调温度")
    override val replies = MutableSharedFlow<DelegationReply>()
    override suspend fun run(task: String) {
        // 交给你的 Agent 处理这条任务,完成后 push 一条 Success/Failure
    }
}

val appliance = RealtimeAppliance(
    session       = session,
    sessionConfig = SessionConfig(
        apiKey       = "your-volc-key",
        endpoint     = "wss://openspeech.bytedance.com/api/v3/realtime",
        model        = "Doubao-语音",
        instructions = "你是智能家居管家,简短回答,不要啰嗦。",
        voice        = "female-shaonv",
        tools        = listOf(LightOnTool, LightOffTool),
        turnDetection = TurnDetection.ServerVad(thresholdMs = 500),
    ),
    microphone    = microphone,
    speaker       = speaker,
    delegation    = delegation,
)

appliance.start()

// 订阅会话事件流做 UI 反馈
appliance.events.collect { event ->
    when (event) {
        is RealtimeEvent.UserTranscriptCompleted -> ui.appendUser(event.text)
        is RealtimeEvent.AssistantAudioDelta     -> ui.showSpeaking()
        is RealtimeEvent.AssistantAudioDone      -> ui.hideSpeaking()
        is RealtimeEvent.ResponseDone            -> ui.markTurnEnd()
        is RealtimeEvent.Error                   -> ui.showError(event.message)
        else                                     -> Unit
    }
}

// 关闭时
appliance.close()
```

`RealtimeSession` 是更底一层的门面,允许在不需要 Appliance 装配时
直接用 WebSocket 会话发音频 / 注入文本 / 取消响应,适合自定义 UI 流程。

---

## 4. 多 Agent 编排(`team/`)

**Boss + Worker(牛马)协同**。当一个 Agent 装不下你的业务,需要让多个
Agent 分工协作时,用这一块。

### 设计意图

Team 的核心抽象不是"消息总线",而是一个**任务看板 + 双层 Agent**:

- **Boss** —— 对用户。负责对话 / 决策 / 任务拆解 / 委派。它本身仍然
  是个普通的 `agent { }`,但多了两个 Tool:`publish_task`(把任务发到看
  板,被某个 Worker 接走)和 `cancel_task`(取消已发布的任务)。
- **Worker / Beast** —— 不对用户,只对 Boss。每个 Worker 仍然是一个
  `agent { }`,但跑在 Boss 委派的上下文中,通常有更专精的工具集。
- **BulletinBoard** —— Boss 与 Worker 之间的唯一信道。所有任务发布 /
  取消 / 进度汇报 / 完成回调都走这里,没有别的直接调用。
- **Pasture** —— BulletinBoard 的执行侧:订阅看板,把任务分给合适的
  Worker,并把 Worker 的产出以"系统汇报"的形式回写给 Boss。

这套设计让 Boss 始终能保持"指挥者"姿态——它不需要懂 Worker 的实现,
只需要看 BulletinBoard 上的状态;Worker 也不需要懂 Boss 的对话历史,
只需要看自己被分配到的任务。两者天然解耦。

### 架构

```
                       ┌───────────────────────┐
                       │        用户            │
                       └───────────┬───────────┘
                                   │ 私聊 / 群消息
                                   ▼
                       ┌───────────────────────┐
                       │     Boss (agent { })   │
                       │   Tools:               │
                       │     publish_task       │
                       │     cancel_task        │
                       └───────────┬───────────┘
                                   │
                                   ▼
       ┌────────────────────────────────────────────────────┐
       │                  BulletinBoard                     │
       │   (任务发布 / 取消 / 进度汇报 / 完成回调 的唯一信道)    │
       └─────────────────┬────────────────────┬─────────────┘
                         │ subscribe          │ write
                         ▼                    ▼
              ┌──────────────────┐    ┌──────────────────┐
              │     Pasture      │    │   Pasture        │
              │  (任务分发 + 汇报) │    │  (任务分发 + 汇报) │
              └────────┬─────────┘    └─────────┬────────┘
                       │                        │
                       ▼                        ▼
              ┌──────────────────┐    ┌──────────────────┐
              │   Worker/Beast 1 │    │   Worker/Beast 2 │
              │   (agent { })    │    │   (agent { })    │
              │   IoT 专精       │    │   日程专精       │
              └──────────────────┘    └──────────────────┘
```

Boss 与 Worker 都用 `agent { }` DSL 构造,只是配置侧重不同。这种"一个
DSL 走天下"的做法避免了在 Team 模块里再发明一套 Agent 配置语法。

### 集成示例

构造一个 IoT 场景的 Boss:面对用户的闲聊自己接住,复杂任务委派给 IoT
专精的 Worker,并启用若干 Skill / Toolset。这段与
`:demos:team/smartHome` 的 `SmartHomeAgent` 同源,可对照参考:

```kotlin
// boss 自己能快速调的工具(同步路径,工具必须短平快)
val quickToolRegistry = ToolRegistry().apply {
    register(GetCurrentTimeTool())
    register(QuickStatusTool())
}

// 委派给 Worker 的工具(异步路径,经 BulletinBoard)
val delegatedToolRegistry = ToolRegistry().apply {
    register(AdjustLightTool())
    register(AdjustAcTool())
}

// 一组相关 Tool 的集合(共享 sub_tool_delegate)
val iotToolsetRegistry = ToolsetRegistry().apply {
    register(homeControlToolset)
    register(applianceControlToolset)
}

// 按场景加载的指令包
val skillRegistry = SkillRegistry().apply {
    register(GoodMorningSkill())
    register(GoodNightSkill())
}

// 子 Agent 池(更专精的内层 Agent)
val subagentRegistry = SubagentRegistry().apply {
    register(SecurityExpertSubagent())
    register(EnvironmentExpertSubagent())
}

val boss: BossAgent = bossAgent {
    // 注意:BossAgent 强制 Persona.role 为空白 — role 由框架填充,
    // 用户通过 personality/domain/constraints/extra 注入个性。
    persona(Persona(role = "").personality("友好、简洁").domain("智能家居"))
    llmProvider(llmProvider)
    memory(InMemoryMemory(), maxRounds = 40)
    maxIterations(40)

    quickTools(quickToolRegistry)
    tools(delegatedToolRegistry)
    toolsets(iotToolsetRegistry)
    skills(skillRegistry)
    subagents(subagentRegistry)
    hook(HookPipeline(logging = true))
}

// 1) 用户轮:AgentEvent 流(同 agent { } 的 run/runStream)
boss.run(userInput).collect { event ->
    when (event) {
        is AgentEvent.Final  -> ui.appendText(event.result.message.content ?: "")
        is AgentEvent.Failed -> ui.showError(event.cause)
        else                 -> Unit
    }
}

// 2) 任务看板:独立 Flow,推送当前 round 的所有任务状态
boss.tasksState.collect { snapshot ->
    ui.updateTaskBoard(snapshot)
}

// 3) 续轮事件:Worker 完成触发的多轮 AgentEvent(同用户轮的事件形态)
boss.report.collect { event ->
    ui.showDelegated(event)
}

// 用完后关闭后台协程
boss.shutdown()
```

`BulletinBoard` 是这套架构的关键:Boss 与 Worker 之间除了它以外没有
任何直接调用,这让 Worker 池可以独立扩展(增加 Worker 数量、迁移到不
同节点、甚至跑成 gRPC 远程)而不影响 Boss 的决策逻辑。

---

## 怎么从这里开始

按你打算做什么挑一个入口:

- **想最快看到一个 Agent 跑起来** —— 看 `:demos:agent`,按 README 填
  好 `local.properties`,装到 Android 设备,UI 里切换 STREAM / BATCH 即
  可看到 Tool 调度、记忆压缩、Subagent 委派等的实际行为。
- **想跑一个飞书 / IM Bot** —— 看 `:gateway:jvm`,填好
  `application.properties` 与飞书应用凭据,`./gradlew :gateway:jvm:run`
  启动守护进程。
- **想把 SDK 集成到自己的 App** —— 引入 `agent:core` + 你要的 LLM
  Provider(OpenAI / Anthropic),从 `agent { ... }` DSL 起步,参考
  `:demos:agent` 的 `DemoAgentFactory` 找组合感觉。
- **想做实时语音 Agent** —— 看 `realtime:core` 抽象 + `:demos:team`
  完整 Demo。
- **想做多 Agent 编排** —— 看 `team` 模块,以及 `:demos:team` 的编排
  样例。
- **想了解 SDK 内部细节** —— 看 `docs/ARCHITECTURE.md`。
- **想了解 Gateway 内部细节** —— 看 `gateway/ARCHITECTURE.md`。

## 状态与路线图

- **v1.0** —— Kotlin/Android + OpenAI/Anthropic Provider + ReAct + Memory
  + Skills + Hooks。
- **v1.1** —— 核心 API 统一(批/流都基于 Flow),Hook 在批与流两条路径上
  行为一致,新增 `awaitResult` 便捷入口。
- **v1.x(当前)** —— Team 多 Agent 编排、Realtime 实时语音(S2S)、
  Gateway 多平台适配(飞书已上线)、Agent 工具调用生产化(Toolset 装配 +
  共享 `sub_tool_delegate`、Tool JSON Schema 统一序列化)。
- **下一步** —— Token 计数与限流、磁盘 Memory、多 Agent 编排完善、
  Gateway 平台适配器扩展(Telegram / 微信)。