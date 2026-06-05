# Agent SDK (Kotlin / Android)

一个轻量、可嵌入 Android 的 LLM Agent SDK。基于 ReAct (Reason + Act) 循环、多轮 Memory、
工具调用、Streaming、Hook 生命周期与 Skill 加载机制。

仓库当前为 v1.0 里程碑:核心抽象 + OpenAI/Anthropic 双 Provider + Android Compose Demo App。

## 快速开始

```kotlin
import io.github.yeyi.agent.AgentResult
import io.github.yeyi.agent.agent
import io.github.yeyi.agent.memory.InMemoryMemory
import io.github.yeyi.agent.providers.openai.OpenAiClient

val client = OpenAiClient(apiKey = "...", model = "gpt-4o-mini")

val agent = agent {
    systemPrompt = "你是一个 helpful 助手。"
    llmClient = client
    tool(GetCurrentTimeTool())
    tool(CalculatorTool())
    maxIterations = 8
}

val memory = InMemoryMemory()
val result: AgentResult = agent.run("现在几点？", memory)
println(result.finalMessage.content)
```

## 核心特性

- **ReAct 循环**:`ReActAgent` 在 reason/act 之间迭代,直到模型给出终态回答或耗尽 `maxIterations`。
- **工具调用 (Tool)**:`Tool` 接口描述 JSON Schema 入参与返回值,由 Agent 在循环中按需分发。
- **多轮 Memory**:`Memory` 抽象当前默认实现为 `InMemoryMemory`,可被自定义工厂替换。
- **Streaming**:除 `run()` 外,`Agent` 暴露 `runStream()` 返回 `Flow<AgentEvent>`,可消费
  `TextDelta` / `ToolCallStarted` / `ToolCallFinished` / `Final` / `Failed` 等事件。
- **Skill 加载**:一组 `systemPromptFragment + tools` 的可复用包,通过 `skill(...)` 注入,
  展开为最终 systemPrompt 与工具列表(详见 `agent/src/main/kotlin/io/github/yeyi/agent/skill/Skill.kt`)。
- **Hook 生命周期**:`AgentHook` 允许在 `beforeLlmCall` / `afterLlmResponse` /
  `beforeToolCall` / `afterToolCall` / `onError` / `onRunFinished` 六个时点插入横切逻辑
  (日志、监控、安全审计等)。
- **多协议 Provider**:OpenAI 与 Anthropic 均实现同一 `LlmClient` 接口,二者都支持
  非流式 `chat()` 与 SSE 流式 `chatStream()`。

## 模块结构

```text
agent-sdk/
├── agent/                # 抽象层:LlmClient / Tool / Memory / Agent / AgentHook / Skill
├── providers/
│   ├── openai/           # OpenAI 协议实现 (chat + SSE 流式)
│   └── anthropic/        # Anthropic 协议实现 (chat + SSE 流式)
└── app/                  # Android Demo App(Compose UI + 3 个演示 Tool)
```

`agent` 模块对外暴露公共 API;`providers/*` 与 `app` 都依赖 `agent`,但彼此不耦合。

## 演示场景 (Demo App)

启动 `:app` 后可试下列提示词,观察 Tool 调用与多步推理的展开过程:

1. "现在几点?" — 触发 `GetCurrentTimeTool`
2. "算 (3+5)*7" — 触发 `CalculatorTool`
3. "搜下 kotlin coroutines 教程" — 触发 `WebSearchMockTool`,展示多步推理链路

UI 基于 Jetpack Compose,提供 `ChatScreen` / `MessageBubble` / `ToolCallIndicator`,
通过 `ChatViewModel` 与 `DemoAgentFactory` 注入 agent 实例。

## 版本

| 项                       | 值                  |
|--------------------------|---------------------|
| Gradle                   | 9.2.1               |
| AGP                      | 8.9.1               |
| Kotlin                   | 2.2.0               |
| Java (source/target)     | 17                  |
| compileSdk               | 36                  |
| minSdk                   | 24                  |
| targetSdk                | 36                  |
| Ktor (client)            | 3.0.3               |
| kotlinx-coroutines       | 1.10.1              |
| kotlinx-serialization    | 1.9.0               |
| Jetpack Compose BOM      | 2025.04.00          |

## 路线图

- v1.0 (本仓库) — Kotlin/Android + OpenAI/Anthropic + ReAct + Memory + Skills + Hooks
- v2.0 — 核心算法在 Python 端重新实现
- v2.1+ — Token 计数、Token 限流、磁盘 Memory、多 Agent 编排

详细设计见 `docs/superpowers/specs/2026-06-03-agent-sdk-design.md`。
