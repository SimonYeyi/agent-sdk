# Demo Tools (教学/可复用)

这 3 个 Tool 是 `app` 模块的**演示代码**,不是 SDK 公开 API 的一部分。
它们用纯 Kotlin 写,不依赖 Android SDK,便于 v2 Python 移植时 1:1 对应。

| Tool | 教学点 |
|---|---|
| `GetCurrentTimeTool` | 零参数、无错误路径 |
| `CalculatorTool` | 多参数 + JSON schema 表达 + 错误处理 |
| `WebSearchMockTool` | 异步/IO 耗时(`delay`) |

复用建议: 直接 copy 到你自己的项目,替换实现即可。
