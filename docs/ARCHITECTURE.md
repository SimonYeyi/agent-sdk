# Agent SDK 架构概览

## 项目定位

Kotlin 多平台 Agent 开发框架，围绕四个垂直领域构建：

| 领域 | 模块 | 架构文档 | 一句话 |
|------|------|----------|--------|
| **Agent 核心** | `agent/` | [ARCHITECTURE-AGENT.md](ARCHITECTURE-AGENT.md) | ReAct 循环 + Tool/Memory/Hook/Skill/Subagent/Toolset/MCP 扩展点 |
| **IM 消息网关** | `gateway/` | [ARCHITECTURE-GATEWAY.md](ARCHITECTURE-GATEWAY.md) | 守护进程形态的消息网关，平台适配器可插拔 |
| **多 Agent 编排** | `team/` | [ARCHITECTURE-TEAM.md](ARCHITECTURE-TEAM.md) | Boss + Worker 双层 Agent + BulletinBoard 任务看板 + DAG 调度 |
| **实时语音对话** | `realtime/` | [ARCHITECTURE-REALTIME.md](ARCHITECTURE-REALTIME.md) | S2S 双向流式会话，厂商协议适配器可插拔 |

## 模块依赖拓扑

```
app (Android Demo) / gateway:jvm (守护进程)
 │
 ├── agent:core              ← 核心: Agent / Tool / Memory / LlmProvider / AgentHook
 ├── agent:capability        ← 能力框架: Capability/Registry/Adapter
 ├── agent:hook              ← Hook 生命周期
 ├── agent:session           ← 会话管理/持久化
 ├── agent:skill             ← 技能管理
 ├── agent:subagent          ← 子 Agent 委派
 ├── agent:toolset           ← 工具集批量装配
 ├── agent:mcp               ← MCP 协议客户端 (三种传输层)
 ├── agent:providers         ← LLM Provider (OpenAI / Anthropic)
 │
 ├── team                    ← 多 Agent 编排 (依赖 agent 各模块)
 │
 ├── gateway:core            ← 消息网关引擎
 ├── gateway:platforms       ← 平台适配器 (飞书/Telegram/微信)
 │
 └── realtime:core           ← 实时语音核心
     ├── realtime:audio:android  ← Android 音频
     └── realtime:providers:volc* ← 火山引擎
```

## 设计原则

- **本地优先** — 核心场景是设备内 Agent，SDK 不强制要求服务端
- **协议无关** — 业务代码不直连任何 LLM SDK / 消息平台 SDK，所有外部协议通过接口层隔离
- **正交扩展** — Tool、Memory、Hook、Skill、Subagent、Toolset、Capability 七个扩展点互不耦合
- **流式是一等公民** — 核心 API 形态是 `Flow`，批模式只是流模式的子集
- **强类型 DSL** — `agent { }` / `bossAgent { }` DSL 构建，配置即代码
- **能力框架统一** — Skill / Subagent / Toolset 通过统一的能力框架实现
- **单一职责** — 每个模块只做一件事

## 各模块入口

| 你的目标 | 入口 |
|----------|------|
| 了解 Agent 核心设计 | [ARCHITECTURE-AGENT.md](ARCHITECTURE-AGENT.md) |
| 了解 IM 消息网关 | [ARCHITECTURE-GATEWAY.md](ARCHITECTURE-GATEWAY.md) |
| 了解多 Agent 编排 | [ARCHITECTURE-TEAM.md](ARCHITECTURE-TEAM.md) |
| 了解实时语音对话 | [ARCHITECTURE-REALTIME.md](ARCHITECTURE-REALTIME.md) |