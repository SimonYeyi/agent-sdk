# 项目结构重组设计

- 日期：2026-08-06
- 类型：仓库结构重构（纯搬运，不改逻辑）

## 背景与目标

随着模块数量增长，根目录平铺的子模块目录越来越多，工程视图变得零散。本次重组将根目录的平铺模块归并到两类管理目录下，与已有的 `gateway/`、`realtime/` 保持一致的管理风格。

**不动**：`gateway/`、`realtime/`、根目录的 `:team` 模块。

**重组目标**：
- 所有 agent 家族模块（含 `:tool:*`、`:providers:*`）搬入新建的 `agent/` 管理目录，原 `:agent` 改为 `:agent:core`
- 两个 demo（原 `:app` 单 agent ReAct 演示 + 原 `:demo` team/boss 演示）统一搬入新建的 `demos/` 管理目录

## 最终目录树

```text
agent-sdk/
├── agent/                       # 新建：agent 家族管理目录
│   ├── core/                    # 原 :agent
│   ├── capability/
│   ├── session/
│   ├── skill/
│   ├── hook/
│   ├── mcp/
│   ├── subagent/
│   ├── toolset/
│   ├── tool/
│   │   ├── serialization/
│   │   └── compression/
│   └── providers/
│       ├── openai/
│       └── anthropic/
├── demos/                       # 新建：demo 集合管理目录（原 :demo + :app）
│   ├── agent/                   # 原 :app（单 agent ReAct 演示）
│   └── team/                    # 原 :demo（team/boss + s2s 演示）
├── gateway/                     # 不动
├── realtime/                    # 不动
├── team/                        # 根目录平铺，不动
├── build.gradle.kts
├── settings.gradle.kts
├── gradle.properties
└── gradle/, gradlew, gradlew.bat
```

## 迁移映射

### 模块 → 物理路径 → Gradle include

| 原模块          | 新物理路径                | 新 Gradle include      |
| --------------- | ------------------------- | ---------------------- |
| `:agent`        | `agent/core/`             | `:agent:core`          |
| `:capability`   | `agent/capability/`       | `:agent:capability`    |
| `:session`      | `agent/session/`          | `:agent:session`       |
| `:skill`        | `agent/skill/`            | `:agent:skill`         |
| `:hook`         | `agent/hook/`             | `:agent:hook`          |
| `:mcp`          | `agent/mcp/`              | `:agent:mcp`           |
| `:subagent`     | `agent/subagent/`         | `:agent:subagent`      |
| `:toolset`      | `agent/toolset/`          | `:agent:toolset`       |
| `:tool:serialization` | `agent/tool/serialization/` | `:agent:tool:serialization` |
| `:tool:compression`   | `agent/tool/compression/`   | `:agent:tool:compression`   |
| `:providers:openai`   | `agent/providers/openai/`   | `:agent:providers:openai`   |
| `:providers:anthropic`| `agent/providers/anthropic/`| `:agent:providers:anthropic`|
| `:app`          | `demos/agent/`            | `:demos:agent`         |
| `:demo`         | `demos/team/`             | `:demos:team`          |
| `:team`         | `team/`（不变）           | `:team`                |
| `:gateway:*`    | `gateway/...`（不变）     | `:gateway:*`           |
| `:realtime:*`   | `realtime/...`（不变）    | `:realtime:*`          |

### 包名与 applicationId

| 范围              | 原值                                | 新值                                       |
| ----------------- | ----------------------------------- | ------------------------------------------ |
| 原 `:app` package | `io.github.yeyi.agent.app.*`        | `io.github.yeyi.agent.demo.agent.*`        |
| 原 `:app` applicationId | `io.github.yeyi.agent.app`     | `io.github.yeyi.agent.demo.agent`           |
| 原 `:demo` package | `io.github.yeyi.agent.demo.*`      | `io.github.yeyi.agent.demo.team.*`         |
| 原 `:demo` applicationId | `io.github.yeyi.agent.demo`    | `io.github.yeyi.agent.demo.team`           |

要点：原 `:demo` 的 `io.github.yeyi.agent.demo` 命名空间被 `demos/` 复用为逻辑命名空间，新模块挂在 `.demo.agent` 与 `.demo.team` 下，单数 `demo` 保留作为锚点。

## 依赖引用全量更新

所有依赖以上 14 个被搬移模块的位置都要改：

1. `settings.gradle.kts` 的 `include(...)` 替换为新冒号路径
2. 各 `build.gradle.kts` 的 `project(":xxx")` 改为 `project(":agent:xxx")` 或 `project(":demos:xxx")`
3. Kotlin 源码 `import`：
   - `io.github.yeyi.agent.app.*` → `io.github.yeyi.agent.demo.agent.*`
   - `io.github.yeyi.agent.demo.*` → `io.github.yeyi.agent.demo.team.*`
4. Android `namespace` 字段跟随 applicationId 改

## s2s 的处置

原 `:demo` 内的 `s2s/` 子包（`BossDelegation`、`LlmIntentionClassifier`、`SmartHomeS2sScreen` 等）随原 `:demo` 一起搬入 `demos/team/`，**不**单独拆出。

理由：s2s 演示在 team 场景中扮演"快捷输入"或"外部成员"角色，不依赖 `:team` 模块本身，整体上仍属于 team 演示的扩展；当前不引入单独的 realtime demo。

## 不动部分

- `gateway/`、`realtime/`、`team/` 三个管理目录及子模块
- 根目录元数据文件：`README.md`、`build.gradle.kts`、`gradle.properties`、`gradlew`、`gradlew.bat`、`.gitignore`、`.claude/`、`.idea/` 等
- 任何源代码逻辑（仅搬运，不重构）

## 风险与注意事项

- `local.properties` / `local.properties.example` 跟着所属模块目录走，搬运后 `demos/agent/local.properties` 引用路径保持不变（Gradle 在模块根目录查找）
- `proguard-rules.pro` 跟着 `demos/agent` 和 `demos/team` 走
- 包名重命名后 IDE 索引会失效，建议搬完后清缓存（`.gradle/`、`build/`、`idea/`）并重启 IDE
- 改动是纯结构搬迁：一次原子 commit 即可，包含 `settings.gradle.kts` + 14 个被搬移模块的 `build.gradle.kts` + 全部 Kotlin 源码的 `import` 与 `package` 声明
- 涉及 `applicationId` 变更；如果 Android 设备已安装过原 app，迁移后需要卸载旧版本才能装新版本

## 执行步骤（高层）

1. 创建 `agent/` 子目录树并 git mv 各模块（含 `build.gradle.kts`、`src/`、`local.properties` 等）
2. 创建 `demos/` 子目录树并 git mv 原 `:app` → `demos/agent/`、原 `:demo` → `demos/team/`
3. 更新 `settings.gradle.kts`：替换所有 `include(...)` 行
4. 更新各 `build.gradle.kts` 的 `project(":xxx")` 引用和 Android `namespace` 字段
5. 全局替换 Kotlin 源码的 `package` 与 `import` 声明
6. 更新 `applicationId`（`app/build.gradle.kts` 和 `demo/build.gradle.kts` 改名后的版本）
7. 跑 `gradlew :agent:core:test` 等核心模块测试确认编译通过
8. 一次原子 commit