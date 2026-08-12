# Agent 多模态输入 — 实施完成总结

## 改了什么

- 新增 `MediaSource` (Http/Data/FileId) / `ContentPart` (Text/Image/Audio/Video) / `AgentQuery` 三层类型
- `Agent.run / runStream` 入参从 `String` → `AgentQuery`（删除重载）
- `ChatMessage.User.content` → `parts`；`AgentEvent.Initial.userInput` → `agentQuery`
- OpenAI / Anthropic provider mapping 按 ContentPart 分流；Video+Data 在 provider 层抛 `UnsupportedContent`
- `RoundsBoundedMemory` 摘要路径按多模态 parts 处理（文本直取 + 多模态压占位）
- 加 `AgentException.UnsupportedContent`

## 没改什么

- `LlmProvider` / `Memory` / `AgentBuilder` / `AgentContext` / `AgentHook` 契约不动
- 不引入 `LocalPath` / `AgentQuery` metadata / Assistant 多模态输出
- 不在 type 层禁止 Video+Data（保留扩展性）

## 验证

- `./gradlew build` 通过（550 actionable tasks 全 SUCCESS，含 assemble + test + lint）
- 新增测试：`MediaSourceTest` / `ContentPartTest` / `AgentQueryTest` / `OpenAiMappingMultimodalTest` / `AnthropicMappingMultimodalTest` / `RoundsBoundedMemorySummaryTest` / `ReActAgentMultimodalTest`
- 现有调用点同步: agent/{subagent,team,skill,capability,toolset,mcp,hook,tool/} + demos + gateway

## 风险与缓解

- 破坏性 API 变更: 所有 caller 同步更新（Task 14 + 3 个 follow-up commits 修完所有漏网）
- OpenAI Chat Completions 视频不支持: 清晰错误信息 + 替代方案指引
- 摘要丢失图片细节: 占位保留"含附件"语义，未来独立提案

## Commit 链路（按时间顺序）

1. `8c04c7b` — Pre-Flight Plan Review (4 个小修正，含 Task 7 合并入 Task 6)
2. `4fc302d` — Task 1: 新增 MediaSource
3. `c96a85c` — Task 2: 新增 ContentPart
4. `1a9fa9e` — Task 3: 新增 AgentException.UnsupportedContent
5. `3b191a1` — Task 4: 迁移 ChatMessage.User.content → parts
6. `1abdf0f` — Task 5: 新增 AgentQuery
7. `ec9113b` — Task 6 (含原 Task 7 合并): Agent.run/runStream 入参迁移到 AgentQuery + Initial.agentQuery rename
8. `5a32fcc` — Task 8: RoundsBoundedMemory 摘要按 ContentPart 多模态处理
9. `6d8a7a0` — Task 9: OpenAI DTO 加 OpenAiContent/OpenAiContentPart 多态
10. `2cb2e03` — Task 10 + Task 9 wire-format fix 合并: mapToOpenAi 支持多模态 + 修复 3 个 wire-format 缺陷 (hand-rolled KSerializer for OpenAiContent, ImageUrlDetail/InputAudioDetail nested objects)
11. `5ba89f8` — Task 11 + Task 12 合并: Anthropic DTO Image/Audio/Video + mapToAnthropic 多模态分支 (并修复 Task 4 的 AnthropicMapping.kt:27 漏同步)
12. `3ff85da` — 补 AnthropicMappingMultimodalTest.kt 末尾换行 (review Minor)
13. `0abfffb` — Task 13 + Task 14 合并: ReActAgent 端到端集成测试 + 4 个测试文件 caller 同步 (~32 wrap sites)
14. `1207d0e` — Task 14 follow-up #1: 2 个 production caller (Subagent.kt:59 + gateway/jvm/DefaultAgentRunner.kt:24)
15. `3c3c53f` — Task 14 follow-up #2: 39 caller fixes across 5 modules (team BossAgent.run 签名迁移 + Beast 内部接口签名迁 + 其它模块 caller wrap)
16. `d18db84` — Task 14 follow-up #3: 12 Android caller fixes (gateway/app + demos/{agent,team}) — Initial.userInput → Initial.agentQuery.parts 提取 text
17. `a82db45` — Task 15: demos/agent 测试遗留 it.content 改 parts.firstOrNull() as Text

## 经验教训（对未来 brief/plan 有用）

1. **Plan 阶段的 caller survey 必须细化到模块** — Plan Task 14 列出 13 个模块/demos/gateway，但实际只有 4 个 `:agent:core/src/test/` 文件 + 7 个 Android 文件需要修改（其它模块早已迁移或没用到）。Plan 高估 scope 但低估了 Android 编译的分离 task（compileDebugKotlin vs compileKotlin）。
2. **Pre-flight review 必须跑一次完整 `./gradlew build`** — Plan 阶段只跑 `./gradlew compileKotlin compileTestKotlin` 会漏掉 Android demos 与 gateway-app 的 breakage。下次 plan 阶段应跑 `./gradlew build` 一次来确认 baseline。
3. **Brief 必须明示"成功标准" > "文件列表"** — Follow-up #1/2/3 三个 brief 都遭遇 "plan 列的文件清单" vs "实际编译通过" 的冲突。Implementer 不得不偏离 brief 加修未被列出的文件。修正方式：brief 应先列成功标准 (full repo compile SUCCESS)，文件清单作为提示而非约束。
4. **OpenAI wire-format 必须在 Plan 阶段就考虑** — Task 9 的 `JsonContentPolymorphicSerializer` 在 Plan 阶段被认为是正确的，但实际是 read-only。Task 10 实现时才发现需 hand-rolled KSerializer。下次 plan 涉及 kotlinx 多态 wire format 必须先 write 一段序列化测试验证。
5. **Task 6 caller-sync 应在 Task 6 之内完成** — Task 6 引入 `Agent.run(AgentQuery)` 签名变更，但 Task 6 本身只迁移了 agent-level 调用点，遗漏了 provider mapping (AnthropicMapping.kt:27) 与 50+ 测试文件。Plan 应在 Task 6 末尾强制要求 `./gradlew build` SUCCESS 作为 done criteria。