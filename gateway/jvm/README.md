# :gateway:jvm

JVM 守护进程入口,加载 `:gateway:core` 并注册飞书(默认)等平台适配器,
把消息平台的聊天事件路由到 Agent 推理并回送结果。

## 配置

将 `application.properties.example` 复制为 `application.properties`,按文件内注释填入:

- 必填:Anthropic LLM 凭据 + 飞书 App 凭据
- 选填:数据根目录、并发上限

该文件已被本模块 `.gitignore` 忽略,不会进入版本控制。

任意键可被同名环境变量覆盖(点号转下划线、全大写,例如
`anthropic.api.key` ↔ `ANTHROPIC_API_KEY`)。要指定配置文件路径,设置环境变量
`GATEWAY_CONFIG=<path>`。缺失必填键时启动直接失败并打印缺失项。

## 运行

完成配置后,从仓库根目录:

```bash
./gradlew :gateway:jvm:run
```

或打 fat jar 独立运行:

```bash
./gradlew :gateway:jvm:shadowJar
java -jar gateway/jvm/build/libs/gateway-jvm-0.1.0-SNAPSHOT-all.jar
```

应用在当前目录查找 `application.properties`;要指向其他路径,先
`export GATEWAY_CONFIG=<path>`。`Ctrl+C` 触发 JVM shutdown hook,调 `daemon.stop()` 优雅退出。
