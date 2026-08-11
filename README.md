# Agent SDK — Kotlin 原生 Agent 框架 (Android / JVM)

[![Kotlin](https://img.shields.io/badge/Kotlin-2.0+-7F52FF?logo=kotlin)](https://kotlinlang.org)
[![Android](https://img.shields.io/badge/Android-API+24-3DDC84?logo=android)](https://developer.android.com)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

**在 Kotlin 端构建生产级 Agent 的全栈 SDK**：ReAct 推理循环、多平台 IM 消息网关 (Gateway)、实时语音对话 (S2S)、多 Agent 团队编排 (Team)。一个仓库，四块能力，自由组合。

---

## 核心特性

| 领域 | 关键词 | 一句话 |
|------|--------|--------|
| **Agent 核心** | `ReAct loop` `Tool` `Skill` `Subagent` `Hook` `MCP` `Toolset` | 完整的 ReAct 推理循环 + 正交扩展点，支持流式 (`Flow`) 与批式两种运行模式 |
| **IM 消息网关** | `Gateway` `多平台适配` `守护进程` | 守护进程形态的消息网关，将飞书 / Telegram / 微信等平台消息路由到 Agent 推理，新增平台只需写一个适配器 |
| **实时语音** | `Realtime` `S2S` `Speech-to-Speech` `双向流式` | S2S 双向流式会话，`core` 抽象 + `audio:android` 采集播放 + `providers:volc*` 火山引擎实现 |
| **多 Agent 编排** | `Team` `Boss/Worker` `编排` `异步任务` `BulletinBoard` | Boss + Worker 多 Agent 协同，任务通过 BulletinBoard 异步分发与进度同步，支持 DAG 任务依赖 |

### 为什么在 Kotlin / Android 上做？

现有 Agent 框架几乎都是 Python / 服务端，Android 工程师要么包一层后端代理（牺牲端侧隐私与延迟），要么用裸 LLM SDK 重新造轮子。本 SDK 把 ReAct 循环、工具调度、记忆压缩、Hook 生命周期、多 Agent 编排这些通用问题在 **Kotlin 端** 收敛成可复用的库，让 Android 工程师像调普通 SDK 一样构建 Agent。

---

## 架构概览

```
┌──────────────────────────────────────────────────────────┐
│                     Agent 核心 (agent/)                    │
│  ReAct Loop │ Tool │ Skill │ Subagent │ Hook │ MCP | Hook │
│  Memory(自动压缩) │ Toolset(sub_tool_delegate)             │
└──────────┬───────────────────────────────────────────────┘
           │
           ▼
┌─────────────────────┐  ┌─────────────────────┐  ┌─────────┐
│   IM 网关 (gateway/) │  │  实时语音 (realtime/)│  │ 多Agent  │
│ 飞书 / Telegram / 微信│  │  S2S 双向流式会话    │  │ 编排     │
│ 守护进程 / Android 端 │  │  火山引擎 / 自定义   │  │ (team/) │
│ 平台适配器可插拔      │  │  音频采集/播放/委派  │  │ Boss+   │
│                     │  │                    │  │ Worker  │
└─────────────────────┘  └─────────────────────┘  └─────────┘
```

四块领域相对独立：`agent/` 是地基，其余三块都跑在它之上。可以只用 Agent 核心（SDK 形态），也可以叠加 Gateway 做 IM Bot，叠加 Realtime 做语音，叠加 Team 做多 Agent 编排——按需挑选。

---

## 快速开始

### 1. Agent 核心 — 构造一个能查天气、调子 Agent 的助手

```kotlin
val agent: Agent = agent {
    persona(Persona("你是一个 helpful 助手，优先使用工具完成任务。"))
    llmProvider(OpenAiProvider(apiKey, model, baseUrl))
    memory(session.memory, maxRounds = 30)
    tool(GetWeatherTool(weatherApiKey))
    skills(skillRegistry)
    toolsets(toolsetRegistry)
    subagents(subagentRegistry)
    hook(HookPipeline(logging = true))
}

// 流式运行
agent.runStream(userInput).collect { event ->
    when (event) {
        is AgentEvent.TextDelta   -> ui.appendText(event.text)
        is AgentEvent.Final       -> ui.appendText(event.result.message.content ?: "")
        is AgentEvent.Failed      -> ui.showError(event.cause)
        else                      -> Unit
    }
}
```

### 2. IM 消息网关 — 把 Agent 挂到飞书 / Telegram

```kotlin
val engine = GatewayEngineBuilder()
    .withConfig(GatewayConfig(maxConcurrentSessions = 10))
    .withFileSessionStorage(baseDir)
    .withAgentRunner(DefaultAgentRunner(createAgent)) // Agent runtime 解耦
    .build()
engine.registerAdapter(FeishuAdapter(FeishuConfig(appId, appSecret), scope))
engine.start()
```

### 3. 实时语音 — S2S 双向流式会话

```kotlin
val appliance = RealtimeAppliance(
    session    = RealtimeSession(HttpClient(), VolcRealtimeAdapter()),
    microphone = AndroidMicrophoneAdapter(),
    speaker    = AndroidSpeakerAdapter(),
    sessionConfig = SessionConfig(
        apiKey = "your-key", model = "Doubao-语音",
        turnDetection = TurnDetection.ServerVad(thresholdMs = 500),
    ),
)
appliance.start()
appliance.events.collect { /* UserTranscript / AudioDelta / ResponseDone */ }
```

### 4. 多 Agent 编排 — Boss + Worker 团队协作

```kotlin
val boss: BossAgent = bossAgent {
    persona(Persona(role = "").personality("友好、简洁").domain("智能家居"))
    llmProvider(llmProvider)
    memory(memory, maxRounds = 40)
    quickTools(quickToolRegistry)       // Boss 同步执行的轻量工具
    tools(delegatedToolRegistry)        // 委派给 Worker 的异步任务工具
    subagents(subagentRegistry)
    hook(HookPipeline(logging = true))
}

boss.run(userInput).collect { /* AgentEvent 流 */ }
boss.tasksState.collect { /* 任务看板状态 */ }
boss.report.collect      { /* Worker 完成触发的续轮事件 */ }
```

---

## 模块结构

```
agent/              Agent 核心
  core/              ReAct 循环、Agent DSL、Hook 接口
  hook/              HookPipeline 及内置 Hook
  session/           会话管理与 Memory 持久化
  skill/             Skill 注册与加载
  subagent/          Subagent 委派机制
  tool/              工具定义与序列化
  toolset/           Toolset 批量装配与 sub_tool_delegate
  mcp/               MCP 协议适配
  providers/         LLM Provider（OpenAI / Anthropic）
  capability/        能力工厂（便捷装配入口）

gateway/            IM 消息网关
  core/              网关引擎、消息模型、HookPipeline
  platforms/feishu/  飞书平台适配器
  platforms/telegram/ Telegram 平台适配器
  platforms/weixin/  微信平台适配器
  jvm/               JVM 守护进程入口
  app/               Android 客户端

realtime/           实时语音对话
  core/              会话抽象、协议适配器接口
  audio/android/     Android 音频采集与播放
  providers/volc*/   火山引擎 S2S 实现

team/               多 Agent 编排
  BossAgent/         面向用户的决策 Agent
  Beast/             专精 Worker Agent
  BulletinBoard/     任务看板（异步任务分发与进度同步）
  Pasture/           任务分发与汇报执行侧
```

---

## 设计原则

- **本地优先** — 核心场景是设备内 Agent，SDK 不强制依赖服务端
- **协议无关** — 所有外部协议通过接口层隔离，换 LLM / 换平台不改业务
- **正交扩展** — Tool、Memory、Hook、Skill、Subagent 五个扩展点互不耦合
- **流式是一等公民** — 核心 API 形态是 `Flow`，批模式只是流模式的子集
- **强类型 DSL** — `agent { }` / `bossAgent { }` DSL 构建，配置即代码

---

## 从哪开始

| 你的目标 | 入口 |
|----------|------|
| 最快跑一个 Agent | `:demos:agent` Demo 模块 |
| 跑一个飞书 Bot | `:gateway:jvm` 守护进程 |
| 集成到自己的 App | 引入 `agent:core` + LLM Provider |
| 做实时语音 Agent | `realtime:core` + `:demos:team` |
| 做多 Agent 编排 | `team` 模块 + `:demos:team` |
| 了解内部设计 | `docs/ARCHITECTURE.md` |

---

## 路线图

- **v1.0** — Kotlin/Android + OpenAI/Anthropic + ReAct + Memory + Skills + Hooks
- **v1.1** — 核心 API 统一（批/流基于 Flow），Hook 双路径一致
- **v1.x（当前）** — Team 多 Agent 编排、Realtime S2S 语音、Gateway 多平台适配
- **下一步** — Token 限流、磁盘 Memory、Telegram / 微信适配器

---

## License

Apache 2.0