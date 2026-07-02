# :gateway:jvm

JVM 守护进程入口，加载 `:gateway:engine` 并注册飞书适配器。

## 配置

复制 `application.properties.example` 为 `application.properties` 并按需修改。真实配置已被本模块 `.gitignore` 忽略。

任意键可被同名环境变量覆盖（点号转下划线、全大写，例如 `anthropic.api.key` ↔ `ANTHROPIC_API_KEY`）。要指定配置文件路径，设置环境变量 `GATEWAY_CONFIG=<path>`。缺失必填键时启动直接失败。

## 运行

完成上一节配置后，从仓库根目录：

    ./gradlew :gateway:jvm:run

或打 fat jar 后独立运行：

    ./gradlew :gateway:jvm:shadowJar
    java -jar gateway/jvm/build/libs/gateway-jvm-0.1.0-SNAPSHOT-all.jar

应用在当前目录查找 `application.properties`；要指向其他路径，先 `export GATEWAY_CONFIG=<path>`。Ctrl+C 触发 JVM shutdown hook，调 `daemon.stop()` 优雅退出。
