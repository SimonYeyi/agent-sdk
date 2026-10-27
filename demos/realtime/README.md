# :demos:realtime

Android 端实时语音双工(S2S)Agent Demo。以 S2S 语音为前台交互方式：
`:realtime` 模块提供实时语音输入输出，`:team` 模块的 BossAgent 承担语音
委托任务的编排执行（智能座舱场景，与 `:demos:team` 共享同一套配置）。

界面结构：左侧任务看板（抽屉）+ 主语音聊天页面。

## 配置

复制 `local.properties.example` 为 `local.properties` 并按要求完成配置。真实密钥文件已被根 `.gitignore` 忽略。
- MODEL_*: 委托任务的 LLM 配置（智能座舱 Boss 使用）
- VOLC_API_KEY: 火山引擎 S2S 语音密钥
