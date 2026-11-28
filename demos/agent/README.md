# :demos:agent

Android 端对话式 Agent Demo,展示 SDK 核心能力在 Compose UI 下的集成方式。
涵盖多轮对话、工具调用、Skill 加载、MCP 集成、Subagent 编排。

## 配置

复制 `local.properties.example` 为 `local.properties` 并按要求完成配置。真实密钥文件已被根 `.gitignore` 忽略。

## 共享记忆

已接入 `:agent:memory:shared`：agent 可以把用户偏好、背景、长期有效的事实写进 markdown 记忆，
跨会话复用。

- 目录：`filesDir/shared-memory`（应用私有，卸载即清）。同一进程内该目录只有一个 loader，
  多个 agent 共享同一份快照与锁。
- 跨会话：`New` 只换 agent 与短期记忆，不改共享目录，所以新会话仍能 `load_shared_memory` 到
  上次存下的内容。想看效果就聊一句偏好，点 `New` 后再问一次。
- 上限：默认 20 条、单条 8000 字符；超额按最久未访问淘汰，超长在写入时由 LLM 同步压缩。
  调参入口是 `AgentBuilder.sharedMemory(directory, maxFileCount, maxFileCharSize, llmProvider)`。
- 内置 `user`：正文注入 system prompt，写它等于改 agent 的画像；它不在记忆清单里，也不占条目名额。
