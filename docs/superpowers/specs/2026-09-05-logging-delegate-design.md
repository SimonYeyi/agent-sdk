# Logging 委托模式设计

## 概述

将 `agent/core` 中的封闭 logging 系统改为可注入的委托模式，允许外部日志框架注入自定义实现。

## 背景

当前 `io.github.yeyi.agent.log.Logging` 是一个封闭的单例，直接输出到 `System.err`，无法注入外部日志框架。需要修改为可注入的委托模式，类似于 `GatewayLogging` 的实现。

## 需求

1. 支持注入外部日志框架
2. 保持向后兼容性（可破坏性更改）
3. 默认实现：debug/info 输出到 System.out，warn/error 输出到 System.err
4. 接口和实现保持在同一模块
5. 提供测试替身用于单元测试

## 设计

### 1. LogDelegate 接口

定义 `LogDelegate` 接口，与 `GatewayLogDelegate` 类似：

```kotlin
public interface LogDelegate {
    public fun debug(tag: String, msg: String)
    public fun info(tag: String, msg: String)
    public fun warn(tag: String, msg: String? = null, e: Throwable? = null)
    public fun error(tag: String, msg: String? = null, e: Throwable? = null)
}
```

### 2. DefaultLogDelegate 实现

提供默认实现，debug/info 输出到 System.out，warn/error 输出到 System.err：

```kotlin
private class DefaultLogDelegate : LogDelegate {
    override fun debug(tag: String, msg: String) {
        System.out.println("[DEBUG] $tag: $msg")
    }

    override fun info(tag: String, msg: String) {
        System.out.println("[INFO] $tag: $msg")
    }

    override fun warn(tag: String, msg: String?, e: Throwable?) {
        System.err.println("[WARN] $tag: ${buildMessage(msg, e)}")
    }

    override fun error(tag: String, msg: String?, e: Throwable?) {
        System.err.println("[ERROR] $tag: ${buildMessage(msg, e)}")
    }

    private fun buildMessage(msg: String? = null, e: Throwable? = null): String {
        return buildString {
            if (msg != null) append(msg).append("\n")
            e?.let { ex ->
                val sw = StringWriter()
                ex.printStackTrace(PrintWriter(sw))
                append(sw.toString())
            }
        }.trimEnd()
    }
}
```

### 3. 修改 Logging 对象

修改 `Logging` 对象，添加 `setDelegate` 方法：

```kotlin
public object Logging {
    private var logDelegate: LogDelegate = DefaultLogDelegate()

    public fun setDelegate(delegate: LogDelegate) {
        logDelegate = delegate
    }

    internal fun debug(tag: String, msg: String) {
        logDelegate.debug(tag, msg)
    }

    internal fun info(tag: String, msg: String) {
        logDelegate.info(tag, msg)
    }

    internal fun warn(tag: String, msg: String? = null, e: Throwable? = null) {
        logDelegate.warn(tag, msg, e)
    }

    internal fun error(tag: String, msg: String? = null, e: Throwable? = null) {
        logDelegate.error(tag, msg, e)
    }
}
```

### 4. FakeLogDelegate 测试替身

提供 `FakeLogDelegate` 用于测试：

```kotlin
public class FakeLogDelegate : LogDelegate {
    public data class LogEntry(
        val level: Level,
        val tag: String,
        val msg: String?,
        val throwable: Throwable?
    )

    public enum class Level {
        DEBUG, INFO, WARN, ERROR
    }

    public val entries = mutableListOf<LogEntry>()

    override fun debug(tag: String, msg: String) {
        entries.add(LogEntry(Level.DEBUG, tag, msg, null))
    }

    override fun info(tag: String, msg: String) {
        entries.add(LogEntry(Level.INFO, tag, msg, null))
    }

    override fun warn(tag: String, msg: String?, e: Throwable?) {
        entries.add(LogEntry(Level.WARN, tag, msg, e))
    }

    override fun error(tag: String, msg: String?, e: Throwable?) {
        entries.add(LogEntry(Level.ERROR, tag, msg, e))
    }

    public fun clear() {
        entries.clear()
    }
}
```

### 5. 文件组织

所有代码都放在 `agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt` 文件中。

## 使用方式

### 注入外部实现

```kotlin
// 在应用启动时注入
Logging.setDelegate(MyCustomLogDelegate())
```

### 使用示例

```kotlin
val log = LoggingTagged("mytag")
log.info("这是一条信息日志")
log.warn("这是一条警告日志", exception)
```

### 测试示例

```kotlin
@Test
fun testLogging() {
    val fakeDelegate = FakeLogDelegate()
    Logging.setDelegate(fakeDelegate)
    
    val log = LoggingTagged("test")
    log.info("测试消息")
    
    assertEquals(1, fakeDelegate.entries.size)
    assertEquals("测试消息", fakeDelegate.entries[0].msg)
    
    Logging.setDelegate(DefaultLogDelegate()) // 恢复默认
}
```

## 影响范围

1. 修改 `agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt`
2. 更新相关测试文件
3. 所有使用 `LoggingTagged` 的模块无需修改，因为它们已经通过 `LoggingTagged` 间接使用 `Logging` 对象

## 向后兼容性

- `LoggingTagged` 类保持 public 不变
- `Logging` 对象的 `setDelegate` 方法是新增的 public 方法
- 现有代码无需修改，除非需要注入自定义日志实现

## 验证

1. 运行现有测试确保不破坏功能
2. 添加新测试验证委托注入功能
3. 验证默认实现的行为（debug/info 输出到 System.out，warn/error 输出到 System.err）