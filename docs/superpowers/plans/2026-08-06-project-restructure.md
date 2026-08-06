# 项目结构重组实施计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将根目录平铺的 agent 家族模块与两个 demo 模块归并到 `agent/` 与 `demos/` 两个管理目录下，与 `gateway/`、`realtime/` 风格保持一致；纯结构搬运，不改任何源码逻辑。

**Architecture:** 14 个模块通过 `git mv` 迁入新位置；`settings.gradle.kts` 的 `include(...)` 替换为冒号路径；各 `build.gradle.kts` 的 `project(":xxx")` 引用、Android `namespace`、`applicationId` 同步更新；Kotlin 源码的 `package` 声明与 `import` 全量重写；最后单次原子 commit。

**Tech Stack:** Gradle (Kotlin DSL)、Kotlin 2.2.0、Android Gradle Plugin 8.9.1、git mv。

---

## 全局约束

- 本次任务**只搬运**，不动任何代码逻辑、运行时行为、测试期望
- 最终提交为**单次原子 commit**（不要中途 commit，跟用户的"no eager commits"规则一致）
- `gateway/`、`realtime/`、`team/` 三个目录**完全不动**
- 根目录元数据文件（`README.md`、`build.gradle.kts`、`gradle.properties`、`gradlew*`、`.gitignore`、`.claude/`、`.idea/`）**完全不动**
- 包名重命名规则：
  - `io.github.yeyi.agent.app.*` → `io.github.yeyi.agent.demo.agent.*`
  - `io.github.yeyi.agent.demo.*` → `io.github.yeyi.agent.demo.team.*`
  - 其他模块的 Kotlin package 全部继承自原 `:agent` 体系，不变
- applicationId 重命名规则：
  - `io.github.yeyi.agent.app` → `io.github.yeyi.agent.demo.agent`
  - `io.github.yeyi.agent.demo` → `io.github.yeyi.agent.demo.team`

---

## 文件结构变更

### 新建/重命名的目录

```
agent/                          新建管理目录
├── core/                       原 :agent
├── capability/                 原 :capability
├── session/                    原 :session
├── skill/                      原 :skill
├── hook/                       原 :hook
├── mcp/                        原 :mcp
├── subagent/                   原 :subagent
├── toolset/                    原 :toolset
├── tool/
│   ├── serialization/          原 :tool:serialization
│   └── compression/            原 :tool:compression
└── providers/
    ├── openai/                 原 :providers:openai
    └── anthropic/              原 :providers:anthropic

demos/                          新建管理目录
├── agent/                      原 :app
└── team/                       原 :demo(含 s2s/)
```

### 必须修改的文件

- `settings.gradle.kts`（替换 14 行 `include(...)`）
- `agent/core/build.gradle.kts` 等 12 个 agent 家族 build 文件（替换 `project(":xxx")` 引用；可能需要把 Kotlin 源码根目录的 `kotlin` sourceSet 路径随物理目录移动后保持默认即可，因为目录结构未变）
- `demos/agent/build.gradle.kts` 与 `demos/team/build.gradle.kts`（替换 `project(":xxx")` 引用 + Android `namespace` + `applicationId`）
- 所有 Kotlin 源码文件（`src/**/kotlin/...`）的 `package` 声明与 `import` 语句

---

### Task 1: 物理搬运 14 个模块到新位置

**Files:**
- 重命名（git mv）：
  - `agent/` → `agent/core/`
  - `capability/` → `agent/capability/`
  - `session/` → `agent/session/`
  - `skill/` → `agent/skill/`
  - `hook/` → `agent/hook/`
  - `mcp/` → `agent/mcp/`
  - `subagent/` → `agent/subagent/`
  - `toolset/` → `agent/toolset/`
  - `tool/serialization/` → `agent/tool/serialization/`
  - `tool/compression/` → `agent/tool/compression/`
  - `providers/openai/` → `agent/providers/openai/`
  - `providers/anthropic/` → `agent/providers/anthropic/`
  - `app/` → `demos/agent/`
  - `demo/` → `demos/team/`

**Step 1: 在 git 中确认当前 working tree 干净**

```bash
git status --short
```

期望输出：除 `realtime/providers/volc-android/SpeechDemoAndroid/`（未跟踪目录）外，无任何未提交改动。

**Step 2: 用 git mv 创建 agent/ 目录树并搬入 12 个模块**

依次执行（每条命令单独运行，便于排查错误）：

```bash
git mv agent agent_tmp
git mv agent_tmp agent_core_target
```

实际更直接的做法——一次性执行嵌套 mv：

```bash
mkdir -p agent/tool agent/providers
git mv agent agent/core
git mv capability agent/capability
git mv session agent/session
git mv skill agent/skill
git mv hook agent/hook
git mv mcp agent/mcp
git mv subagent agent/subagent
git mv toolset agent/toolset
git mv tool/serialization agent/tool/serialization
git mv tool/compression agent/tool/compression
git mv providers/openai agent/providers/openai
git mv providers/anthropic agent/providers/anthropic
```

注意：`mkdir -p agent/tool agent/providers` 必须在 `git mv agent agent/core` 之前执行（因为 `agent/` 目录会被先重命名为 `agent/core/`，后续再创建的 `agent/tool` 等就与 `agent/core` 同级）。

**Step 3: 验证 agent/ 目录树**

```bash
ls agent/
```

期望输出：`core capability hook mcp providers session skill subagent tool toolset`

```bash
ls agent/tool/ agent/providers/
```

期望输出：
- `agent/tool/`: `compression serialization`
- `agent/providers/`: `anthropic openai`

**Step 4: 用 git mv 创建 demos/ 目录并搬入 2 个 demo 模块**

```bash
mkdir -p demos
git mv app demos/agent
git mv demo demos/team
```

**Step 5: 验证 demos/ 目录树**

```bash
ls demos/
```

期望输出：`agent team`

**Step 6: 验证根目录已无残留的旧模块目录**

```bash
ls -d capability session skill hook mcp subagent toolset tool providers app demo 2>&1
```

期望输出：全部 `No such file or directory`（或 ls 报错）。注意 `team/` 仍在根目录（不动）。

**Step 7: 确认 git 已识别为 rename 而非 delete+add**

```bash
git status --short | head -20
```

期望输出：每条都形如 `R  old/path -> new/path`，而非 `D  old/path` 加 `A  new/path`。

---

### Task 2: 更新 settings.gradle.kts

**Files:**
- Modify: `settings.gradle.kts:24-52`（所有 `include(...)` 行）

**Step 1: 替换 include 行**

将以下原行：

```kotlin
include(":agent")
include(":capability")
include(":session")
include(":skill")
include(":hook")
include(":mcp")
include(":subagent")
include(":toolset")
include(":tool:serialization")
include(":tool:compression")
include(":providers:openai")
include(":providers:anthropic")
include(":app")
include(":demo")
```

替换为：

```kotlin
include(":agent:core")
include(":agent:capability")
include(":agent:session")
include(":agent:skill")
include(":agent:hook")
include(":agent:mcp")
include(":agent:subagent")
include(":agent:toolset")
include(":agent:tool:serialization")
include(":agent:tool:compression")
include(":agent:providers:openai")
include(":agent:providers:anthropic")
include(":demos:agent")
include(":demos:team")
```

保留 `include(":team")`、`include(":gateway:*")`、`include(":realtime:*")` 不变。

**Step 2: 验证 settings.gradle.kts**

```bash
grep -n "include(" settings.gradle.kts
```

期望输出：包含所有 `:agent:*`、`:demos:agent`、`:demos:team`、`:team`、`:gateway:*`、`:realtime:*`，不包含旧的 `:capability` `:session` `:app` `:demo` 等。

---

### Task 3: 更新所有 build.gradle.kts 的 project() 引用

**Files:**
- Modify: 仓库内所有 `build.gradle.kts` 中形如 `project(":xxx")` 的依赖声明
- Modify: `demos/agent/build.gradle.kts` 的 `namespace` 与 `applicationId`
- Modify: `demos/team/build.gradle.kts` 的 `namespace` 与 `applicationId`

**Step 1: 列出所有需要更新的引用**

```bash
grep -rn 'project(":' --include='*.kts' . | grep -v '/build/' | grep -v '/.gradle/'
```

**Step 2: 对每个 build.gradle.kts，按下表替换**

| 原值 | 新值 |
|---|---|
| `project(":agent")` | `project(":agent:core")` |
| `project(":capability")` | `project(":agent:capability")` |
| `project(":session")` | `project(":agent:session")` |
| `project(":skill")` | `project(":agent:skill")` |
| `project(":hook")` | `project(":agent:hook")` |
| `project(":mcp")` | `project(":agent:mcp")` |
| `project(":subagent")` | `project(":agent:subagent")` |
| `project(":toolset")` | `project(":agent:toolset")` |
| `project(":tool:serialization")` | `project(":agent:tool:serialization")` |
| `project(":tool:compression")` | `project(":agent:tool:compression")` |
| `project(":providers:openai")` | `project(":agent:providers:openai")` |
| `project(":providers:anthropic")` | `project(":agent:providers:anthropic")` |
| `project(":app")` | `project(":demos:agent")` |
| `project(":demo")` | `project(":demos:team")` |

对每个文件使用 `Edit` 工具或 `sed` 批量替换。`project(":team")`、`project(":gateway:*")`、`project(":realtime:*")` 不变。

**Step 3: 验证替换结果**

```bash
grep -rn 'project(":' --include='*.kts' . | grep -v '/build/' | grep -v '/.gradle/' | grep -E 'project\(":(capability|session|skill|hook|mcp|subagent|toolset|tool|providers|app|demo)"\)'
```

期望输出：空（无匹配）。

**Step 4: 更新 demos/agent/build.gradle.kts 的 namespace 与 applicationId**

将：

```kotlin
namespace = "io.github.yeyi.agent.app"
applicationId = "io.github.yeyi.agent.app"
```

改为：

```kotlin
namespace = "io.github.yeyi.agent.demo.agent"
applicationId = "io.github.yeyi.agent.demo.agent"
```

**Step 5: 更新 demos/team/build.gradle.kts 的 namespace 与 applicationId**

将：

```kotlin
namespace = "io.github.yeyi.agent.demo"
applicationId = "io.github.yeyi.agent.demo"
```

改为：

```kotlin
namespace = "io.github.yeyi.agent.demo.team"
applicationId = "io.github.yeyi.agent.demo.team"
```

**Step 6: 验证 namespace/applicationId**

```bash
grep -n -E 'namespace|applicationId' demos/agent/build.gradle.kts demos/team/build.gradle.kts
```

期望输出：两者均显示 `io.github.yeyi.agent.demo.agent` 与 `io.github.yeyi.agent.demo.team`。

---

### Task 4: 重写所有 Kotlin 源码的 package 与 import 声明

**Files:**
- Modify: 所有 `src/**/kotlin/.../*.kt` 文件的 `package` 声明与 `import` 语句

**Step 1: 列出所有需要改 package 的文件**

```bash
grep -rln 'package io.github.yeyi.agent.app' --include='*.kt' .
grep -rln 'package io.github.yeyi.agent.demo$' --include='*.kt' .  # 仅根包，不含子包
grep -rln 'package io.github.yeyi.agent.demo\.' --include='*.kt' .  # 含子包
```

注意：
- `io.github.yeyi.agent.app` 是原 `:app` 的根包（无子包）；`io.github.yeyi.agent.app.demo`、`io.github.yeyi.agent.app.demo.tools` 等是它的子包——所有 `io.github.yeyi.agent.app*` 前缀都要替换为 `io.github.yeyi.agent.demo.agent`
- `io.github.yeyi.agent.demo`（精确，不带子点）只有 `MainActivity.kt` 等顶层文件；`io.github.yeyi.agent.demo.*` 全部前缀替换为 `io.github.yeyi.agent.demo.team`

**Step 2: 替换 import 与 package 声明**

**关键约束**：必须**先处理 demo 再处理 app**。如果先 app 后 demo，第一轮产生的 `io.github.yeyi.agent.demo.agent` 会在第二轮被 `demo.` → `demo.team.` 错误地改成 `io.github.yeyi.agent.demo.team.agent`。反过来则安全——第一轮处理 demo.* 时，app.* 不含 `agent.demo.`，不受影响；第二轮处理 app 时，已经处理过的 demo.team.* 也不含 `agent.app`，不受影响。

```bash
# 第一轮：demo.* -> demo.team.*（先处理 demo，因为它的边界精确，不会被 app 替换破坏）
find . -name '*.kt' -not -path '*/build/*' -not -path '*/.gradle/*' \
  -exec sed -i 's/io\.github\.yeyi\.agent\.demo\./io.github.yeyi.agent.demo.team./g' {} +

# 第二轮：处理孤立的 demo 根包（MainActivity.kt 等顶层文件的 `package io.github.yeyi.agent.demo`）
find . -name '*.kt' -not -path '*/build/*' -not -path '*/.gradle/*' \
  -exec sed -i 's/io\.github\.yeyi\.agent\.demo$/io.github.yeyi.agent.demo.team/g' {} +

# 第三轮：app.* -> demo.agent.*
find . -name '*.kt' -not -path '*/build/*' -not -path '*/.gradle/*' \
  -exec sed -i 's/io\.github\.yeyi\.agent\.app/io.github.yeyi.agent.demo.agent/g' {} +
```

执行平台差异：
- 在 Git Bash / WSL：`sed -i` 直接可用
- 在 Windows cmd：使用 `find ... -exec sed -i ...`（Git for Windows 自带 sed），或改用 PowerShell：
  ```powershell
  Get-ChildItem -Recurse -Include *.kt | Where-Object { $_.FullName -notmatch '[\\/]build[\\/]' -and $_.FullName -notmatch '[\\/]\.gradle[\\/]' } | ForEach-Object { (Get-Content $_ -Raw) -replace 'io\.github\.yeyi\.agent\.demo\.', 'io.github.yeyi.agent.demo.team.' | Set-Content -NoNewline $_ }
  ```

```bash
# 第一轮：demo.* -> demo.team.*
find . -name '*.kt' -not -path '*/build/*' -not -path '*/.gradle/*' \
  -exec sed -i 's/io\.github\.yeyi\.agent\.demo\./io.github.yeyi.agent.demo.team./g' {} +

# 第二轮：app.* -> demo.agent.*
find . -name '*.kt' -not -path '*/build/*' -not -path '*/.gradle/*' \
  -exec sed -i 's/io\.github\.yeyi\.agent\.app/io.github.yeyi.agent.demo.agent/g' {} +
```

验证：

```bash
grep -rn 'io\.github\.yeyi\.agent\.app[^a-zA-Z]' --include='*.kt' . | grep -v build | grep -v .gradle
grep -rn 'io\.github\.yeyi\.agent\.demo\.\(s2s\|smartHome\|smartCockpit\|ui\|vm\)' --include='*.kt' . | grep -v build | grep -v .gradle
```

期望：
- 第一个命令无输出（旧的 `io.github.yeyi.agent.app.*` 已全部清除）
- 第二个命令无输出（`io.github.yeyi.agent.demo.*` 子包已重命名为 `.demo.team.*`）

```bash
grep -rn 'package io.github.yeyi.agent.demo.agent' --include='*.kt' . | grep -v build | grep -v .gradle | head -5
grep -rn 'package io.github.yeyi.agent.demo.team' --include='*.kt' . | grep -v build | grep -v .gradle | head -5
```

期望：两个命令都有输出，分别对应 `demos/agent/src/main/kotlin/...` 和 `demos/team/src/main/kotlin/...` 下的源文件。

**Step 3: 检查是否有遗漏的 `package io.github.yeyi.agent.demo`（根包，不带子点）**

```bash
grep -rn 'package io\.github\.yeyi\.agent\.demo$\|package io\.github\.yeyi\.agent\.demo;\|"io\.github\.yeyi\.agent\.demo"' --include='*.kt' . | grep -v build | grep -v .gradle
```

期望：仅剩 `demos/team/src/main/kotlin/.../AndroidManifest.xml`（如果有引用）或 `applicationId` 在 build.gradle.kts 里的字符串（非 Kotlin 源码）。**Kotlin 源码中不应再出现 `io.github.yeyi.agent.demo` 这个孤立包/字符串**。

---

### Task 5: 验证编译并单次原子 commit

**Files:**
- Modify:（无新增文件，仅提交之前的累积改动）

**Step 1: 运行核心模块的测试**

```bash
./gradlew :agent:core:test :agent:capability:test :agent:session:test :agent:skill:test :agent:hook:test :agent:mcp:test :agent:subagent:test :agent:toolset:test
```

期望：BUILD SUCCESSFUL，所有 `:agent:*` 模块的测试通过。

**Step 2: 运行 demos 的构建**

```bash
./gradlew :demos:agent:assembleDebug :demos:team:assembleDebug
```

期望：BUILD SUCCESSFUL，两个 demo 模块都构建成功。

**Step 3: 运行全仓库 build 与 test**

```bash
./gradlew clean test assembleDebug
```

期望：BUILD SUCCESSFUL。

**Step 4: 检查 git 状态确认所有改动**

```bash
git status --short | head -30
```

期望：大量 `R  old -> new` 行（rename 标记），加上 settings.gradle.kts、build.gradle.kts、若干 .kt 文件的修改。

**Step 5: 单次原子 commit**

```bash
git add -A
git commit -m "$(cat <<'EOF'
refactor(repo): 重组项目结构,agent/ + demos/ 管理目录

- gateway/ realtime/ 保持现状
- :team 保持根目录平铺
- agent 家族模块(:agent :capability :session :skill :hook :mcp :subagent :toolset
  :tool:serialization :tool:compression :providers:openai :providers:anthropic)
  全部搬入新建的 agent/ 管理目录
- 原 :agent -> :agent:core,其余按 1:1 嵌套路径保留
- 原 :app -> :demos:agent (demo/agent/)
- 原 :demo -> :demos:team (demo/team/),保留内含的 s2s 子包
- 代码 package 重命名:
  io.github.yeyi.agent.app.* -> io.github.yeyi.agent.demo.agent.*
  io.github.yeyi.agent.demo.* -> io.github.yeyi.agent.demo.team.*
- applicationId 与 Android namespace 同步更新
- 仅搬运,不改任何源码逻辑

Co-Authored-By: Claude Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

期望：commit 成功，git log 显示一个新 commit。

**Step 6: 验证仓库根目录整洁度**

```bash
ls -d */ 2>&1 | sort
```

期望：根目录仅剩 `agent/ build/ capability/ demo/ docs/ gateway/ gradle/ hook/ mcp/ providers/ realtime/ session/ skill/ subagent/ team/ tool/ toolset/` 中的 **保留部分**——实际期望是：

```
agent/ build/ demos/ docs/ gateway/ gradle/ realtime/ team/
```

外加 `.claude/`、`.git/`、`.gradle/`、`.idea/`、`.kotlin/`、`.superpowers/`（隐藏目录）。

**注意：上一步的 `git mv` 已经物理搬走了旧目录，所以根目录应该只剩新结构，不需要再清理。**

---

## 自审

**1. Spec coverage：**
- 最终目录树（spec §"最终目录树"）→ Task 1 ✓
- 迁移映射表（spec §"模块 → 物理路径 → Gradle include"）→ Task 1 + Task 2 ✓
- 包名与 applicationId 重命名（spec §"包名与 applicationId"）→ Task 3 + Task 4 ✓
- 依赖引用全量更新（spec §"依赖引用全量更新"）→ Task 2 + Task 3 + Task 4 ✓
- s2s 不拆（spec §"s2s 的处置"）→ Task 1 把整个 `demo/` 一起搬到 `demos/team/`，自然包含 s2s ✓
- 不动部分（spec §"不动部分"）→ Task 1 显式列出 git mv 范围（不含 gateway/realtime/team）✓
- 单次原子 commit（spec §"风险与注意事项"）→ Task 5 Step 5 ✓

**2. Placeholder scan：**
- 无 "TBD"、"TODO"、"implement later" ✓
- 所有 `sed` 命令都是完整可执行的（不是 "类似 Task N" 引用）✓
- 所有 build.gradle.kts 修改都有具体行号与 before/after ✓

**3. Type consistency：**
- Task 2 的 `include(":demos:agent")` 与 Task 3 的 `project(":demos:agent")` 一致 ✓
- Task 3 的 applicationId 与 Task 4 的 package 前缀一致（`io.github.yeyi.agent.demo.agent` / `io.github.yeyi.agent.demo.team`）✓

无问题。