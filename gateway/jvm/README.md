# :gateway:jvm

JVM 守护进程入口，加载 `:gateway:engine` 并注册飞书适配器。

## 配置

复制 `application.properties.example` 为 `application.properties` 并按需修改。真实配置已被本模块 `.gitignore` 忽略。

任意键可被同名环境变量覆盖（点号转下划线、全大写，例如 `anthropic.api.key` ↔ `ANTHROPIC_API_KEY`）。要指定配置文件路径，设置环境变量 `GATEWAY_CONFIG=<path>`。缺失必填键时启动直接失败。
