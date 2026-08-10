# Agent SDK

一个面向 Android 的 Agent SDK,同时承载多平台消息网关守护进程与实时语音能力,
全部在同一个 Kotlin 仓库中。

## 这是什么

这个仓库由三块组成,服务于不同的使用场景,共享底层抽象:

- **Agent SDK(`agent/`)** —— 可嵌入 Android 应用的 Agent 框架。
  业务方在自己的 App 里通过 DSL 组装 Agent,直接复用 SDK 的 ReAct 循环、Tool 调度、
  Memory、Hook、Skill 等能力,不需要为每一种 LLM 协议、每一种工具接入方式分别造轮子。
- **Gateway(`gateway/`)** —— 长跑在服务器上的多平台消息网关守护进程。
  把飞书 / Telegram / 微信等聊天平台的消息路由到 Agent 推理,
  通过统一的核心引擎屏蔽各平台的协议差异。
- **Realtime(`realtime/`)与 Team(`team/`)** —— 在 SDK 之上构建的两类可选能力。
  Realtime 负责把对话从文本扩展到实时语音,Team 负责多 Agent 之间的任务编排。

每个模块都可以独立使用:Demo App 只用 SDK,Gateway 守护进程同时使用 SDK 和平台适配器,
`:demos:team` 同时使用 SDK、Realtime、Team 三块。

## 为什么需要它

在做 Android 上的 AI 应用时,大多数现有方案都会卡在两个常见痛点上:

**1. 现有 Agent 框架几乎都是 Python / 服务端**。如果你的产品形态是 Android App,
你只能选择"自己包一层后端代理"或"用裸 LLM SDK 重新造轮子"。前者牺牲了端侧隐私、
延迟、离线性,后者把 ReAct 循环、工具调度、记忆压缩、Hook 生命周期这些通用问题
都留给每个应用各自实现。这个 SDK 把这些通用问题在 Kotlin 端收敛成可复用的库,
让 Android 工程师像调普通 Android 库一样构建 Agent。

**2. 聊天平台 Bot 和 App 内 Agent 是两套互不通用的技术栈**。
一个团队往往既要做"App 里的对话功能",又要做"飞书 / 企微 / Telegram 上的客服机器人",
但这两件事在大多数代码库里是分开维护的,各写一套消息路由、各接一套 LLM、各管一套会话。
本仓库把 Agent 核心抽象与消息平台适配器分到两层:Gateway 用 SDK 跑 Agent,
平台适配器只负责消息事件 → SDK 输入的翻译,新增一个聊天平台只需要写一个适配器,
不需要重复实现 Agent 部分。

除了上面两个核心动机,还有几条设计层面的小决策值得一提:

- **不绑定任何 LLM 厂商**。业务代码只面向统一的 Provider 接口,OpenAI / Anthropic /
  任何自部署的兼容服务都可以即插即用。
- **扩展点互相正交**。Tool、Memory、Hook、Skill、Subagent 各自独立可替换,
  不必因为要加一个新能力而重写已有代码。
- **流式是一等公民,不是补丁**。核心 API 形态是 Flow,而不是"先有非流式再补一个流式",
  批模式只是流模式的一个子集。
- **强类型 DSL**。Agent 是用 `agent { ... }` DSL 构建的,配置即代码,编译期就能发现错配。

## 设计思想

设计上有几条贯穿整个仓库的原则,这些不是口号,而是写代码时的硬约束:

- **本地优先**。核心场景是设备内 Agent,SDK 不强制要求服务端。Gateway 是另一个独立的使用形态,不是 SDK 的运行时依赖。
- **协议无关**。业务代码不直连任何 LLM SDK、不直连任何消息平台 SDK。所有外部协议都通过接口层隔离,换实现不需要改业务。
- **正交扩展**。Tool、Memory、Hook、Skill、Subagent 五个扩展点互不耦合,可以独立替换或叠加,组合方式在 DSL 中显式表达。
- **生命周期透明**。所有可观察的转折点都有 Hook 接入,日志、监控、审计、安全策略都通过 Hook 注入,不靠"埋点"或字节码改写。
- **可测试**。核心模块对 LLM Provider 抽象出接口,测试期间可以注入伪 Provider,不需要网络也能跑通 Agent 流程。
- **失败显式**。Hook 异常被吞掉并降级,但 Agent 主流程的失败会显式发出终态事件并携带原始 Throwable,不静默吞错。

## 架构层次

从下到上,整个仓库可以分成这几层,每层都是独立可替换的:

- **LLM 层** —— 决定谁提供模型。统一接口屏蔽 OpenAI / Anthropic / 自部署服务的协议差异。
- **能力层** —— 决定 Agent 能做什么。Tool(单次调用的能力)、Skill(按需加载的指令包)、Subagent(把任务委派给更专精的子 Agent)、MCP(把外部 Model Context Protocol Server 包装成 LLM 可调用的工具)。
- **行为层** —— 决定 Agent 怎么思考和决策。ReAct 循环把"推理 → 调用 → 观察"反复迭代,直到产出终态回答。
- **记忆层** —— 决定 Agent 记得什么。Memory 抽象(内存 / 持久化 JSONL / 自定义),Session 抽象则把多轮对话与 Agent 生命周期绑定。
- **横切层** —— 决定跨切关注点放在哪。Hook 系统在 Agent 完整生命周期(包括记忆压缩、LLM 调用、工具调用、终态)上提供可注入点。
- **平台层** —— 决定消息从哪儿来。Gateway Core 提供统一的引擎,各平台适配器只负责消息事件 ↔ Agent 输入的翻译。
- **应用层** —— 真正可运行的程序。Demo App(:demos:agent / :demos:team)、Gateway 守护进程(:gateway:jvm)、Gateway 客户端(:gateway:app)。

这些层在 Gradle 上对应一个个模块,但更重要的是它们在依赖关系上严格单向:上层依赖下层,下层对上层一无所知。

## 仓库结构

按"目的"分组,而不是按 Gradle 模块全展开:

- `agent/` —— SDK 核心。`agent:core` 是无内部依赖的抽象层,其他 `agent:*` 子模块围绕它正交展开(Provider、Tool、Subagent、Skill、Hook、Session、MCP 等)。
- `gateway/` —— 多平台消息网关。`gateway:core` 是无平台依赖的引擎,`gateway:platforms:*` 是各聊天平台适配器,`gateway:jvm` 是守护进程入口,`gateway:app` 是 Android 端客户端。
- `team/` —— 多 Agent 编排。在 `agent:*` 之上做任务分派与协同,独立成模块,使用方按需引入。
- `realtime/` —— 实时语音 Agent 能力。把对话从文本扩展到 S2S 双向流(`realtime:core` 抽象,`realtime:audio:android` 是 Android 音频采集/播放,`realtime:providers:volc*` 是火山引擎实现)。
- `demos/` —— 可运行的演示应用。`:demos:agent` 是纯文本对话 Demo,`:demos:team` 是叠加了 Team + Realtime 的语音 Demo。
- `docs/` —— 详细架构与设计文档。SDK 内部细节在 `docs/ARCHITECTURE.md`,Gateway 内部细节在 `gateway/ARCHITECTURE.md`。

## 从这里开始

按你打算做什么挑一个入口:

- **想最快看到一个 Agent 跑起来** —— 打开 `:demos:agent`,按 README 填好 `local.properties`,装到 Android 设备,UI 里切换 STREAM / BATCH 即可看到 Tool 调度、记忆压缩、Subagent 委派等的实际行为。
- **想跑一个聊天平台 Bot** —— 打开 `:gateway:jvm`,按 README 填好 `application.properties` 与飞书应用凭据,`./gradlew :gateway:jvm:run` 启动守护进程。
- **想把 SDK 集成到自己的 App** —— 引入 `agent:core` + 你要的 LLM Provider(OpenAI / Anthropic),从 `agent { ... }` DSL 起步,参考 `:demos:agent` 的 `DemoAgentFactory` 找组合感觉。
- **想接 MCP / 自定义 Tool / 自定义 Skill / 自定义 Subagent** —— 看 `agent:core` 的接口,以及 `:demos:agent` 里 Tool / Subagent / Skill 的实现样例。
- **想做实时语音 Agent** —— 看 `realtime:core` 抽象 + `:demos:team` 完整 Demo。
- **想做多 Agent 编排** —— 看 `team` 模块,以及 `:demos:team` 的编排样例。
- **想了解 SDK 内部细节** —— 看 `docs/ARCHITECTURE.md`。
- **想了解 Gateway 内部细节** —— 看 `gateway/ARCHITECTURE.md`。

## 状态与路线图

- **v1.0** —— Kotlin/Android + OpenAI/Anthropic Provider + ReAct + Memory + Skills + Hooks。
- **v1.1** —— 核心 API 统一(批/流都基于 Flow),Hook 在批与流两条路径上行为一致,新增 `awaitResult` 便捷入口。
- **进行中** —— Team 多 Agent 编排、Realtime 实时语音(S2S)、Gateway 多平台适配(飞书 / Telegram / 微信)。
- **下一步** —— Token 计数与限流、磁盘 Memory、多 Agent 编排完善、Gateway 平台适配器扩展。
