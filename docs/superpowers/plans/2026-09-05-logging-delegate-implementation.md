# Logging 委托模式实现计划

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 将 `agent/core` 中的封闭 logging 系统改为可注入的委托模式，允许外部日志框架注入自定义实现。

**Architecture:** 定义 `LogDelegate` 接口，提供默认实现和测试替身，修改 `Logging` 对象支持委托注入。

**Tech Stack:** Kotlin · JUnit

**Spec 参考:** `docs/superpowers/specs/2026-09-05-logging-delegate-design.md`

---

## 文件结构

```
agent/core/src/main/kotlin/io/github/yeyi/agent/log/
├── Logging.kt                              ← 修改：添加接口和实现

agent/core/src/test/kotlin/io/github/yeyi/agent/log/
├── LoggingTest.kt                          ← 修改：添加委托注入测试
```

---

### Task 1: 定义 LogDelegate 接口

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt`

- [ ] **Step 1: 在文件顶部添加 LogDelegate 接口**

在 `package` 声明后添加：

```kotlin
public interface LogDelegate {
    public fun debug(tag: String, msg: String)
    public fun info(tag: String, msg: String)
    public fun warn(tag: String, msg: String? = null, e: Throwable? = null)
    public fun error(tag: String, msg: String? = null, e: Throwable? = null)
}
```

- [ ] **Step 2: 验证编译**

Run: `./gradlew :agent:core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt
git commit -m "feat: add LogDelegate interface"
```

---

### Task 2: 实现 DefaultLogDelegate

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt`

- [ ] **Step 1: 在 LogDelegate 接口后添加 DefaultLogDelegate**

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

- [ ] **Step 2: 验证编译**

Run: `./gradlew :agent:core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt
git commit -m "feat: add DefaultLogDelegate implementation"
```

---

### Task 3: 修改 Logging 对象支持委托注入

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt`

- [ ] **Step 1: 修改 Logging 对象**

将现有的 `Logging` 对象替换为：

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

- [ ] **Step 2: 验证编译**

Run: `./gradlew :agent:core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 运行现有测试确保不破坏功能**

Run: `./gradlew :agent:core:test`
Expected: 所有测试通过

- [ ] **Step 4: 提交**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt
git commit -m "feat: modify Logging object to support delegate injection"
```

---

### Task 4: 实现 FakeLogDelegate 测试替身

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt`

- [ ] **Step 1: 在文件末尾添加 FakeLogDelegate**

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

- [ ] **Step 2: 验证编译**

Run: `./gradlew :agent:core:compileKotlin`
Expected: BUILD SUCCESSFUL

- [ ] **Step 3: 提交**

```bash
git add agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt
git commit -m "feat: add FakeLogDelegate test double"
```

---

### Task 5: 添加委托注入测试

**Files:**
- Modify: `agent/core/src/test/kotlin/io/github/yeyi/agent/log/LoggingTest.kt`

- [ ] **Step 1: 在测试文件中添加委托注入测试**

在 `LoggingTest` 类中添加以下测试方法：

```kotlin
@Test
fun testDelegateInjection() {
    val fakeDelegate = FakeLogDelegate()
    Logging.setDelegate(fakeDelegate)
    
    val log = LoggingTagged("test")
    log.info("test message")
    
    assertEquals(1, fakeDelegate.entries.size)
    assertEquals("test message", fakeDelegate.entries[0].msg)
    assertEquals(FakeLogDelegate.Level.INFO, fakeDelegate.entries[0].level)
    assertEquals("test", fakeDelegate.entries[0].tag)
    
    Logging.setDelegate(DefaultLogDelegate()) // 恢复默认
}

@Test
fun testDelegateWithThrowable() {
    val fakeDelegate = FakeLogDelegate()
    Logging.setDelegate(fakeDelegate)
    
    val log = LoggingTagged("test")
    val exception = RuntimeException("test exception")
    log.warn("warning message", exception)
    
    assertEquals(1, fakeDelegate.entries.size)
    assertEquals("warning message", fakeDelegate.entries[0].msg)
    assertEquals(exception, fakeDelegate.entries[0].throwable)
    assertEquals(FakeLogDelegate.Level.WARN, fakeDelegate.entries[0].level)
    
    Logging.setDelegate(DefaultLogDelegate()) // 恢复默认
}

@Test
fun testMultipleDelegates() {
    val fakeDelegate1 = FakeLogDelegate()
    val fakeDelegate2 = FakeLogDelegate()
    
    Logging.setDelegate(fakeDelegate1)
    val log1 = LoggingTagged("test1")
    log1.info("message1")
    
    Logging.setDelegate(fakeDelegate2)
    val log2 = LoggingTagged("test2")
    log2.info("message2")
    
    assertEquals(1, fakeDelegate1.entries.size)
    assertEquals(1, fakeDelegate2.entries.size)
    assertEquals("message1", fakeDelegate1.entries[0].msg)
    assertEquals("message2", fakeDelegate2.entries[0].msg)
    
    Logging.setDelegate(DefaultLogDelegate()) // 恢复默认
}
```

- [ ] **Step 2: 需要导入 Logging 和 DefaultLogDelegate**

在测试文件顶部添加导入：

```kotlin
import io.github.yeyi.agent.log.Logging
import io.github.yeyi.agent.log.DefaultLogDelegate
```

注意：`DefaultLogDelegate` 是 private 类，需要改为 internal 以便测试访问。或者，可以在测试中直接使用 `Logging.setDelegate(FakeLogDelegate())` 然后恢复时使用 `Logging.setDelegate(FakeLogDelegate())` 作为默认的替代。

实际上，我们可以在测试中保存原始委托，然后恢复。修改测试方法：

```kotlin
@Test
fun testDelegateInjection() {
    val fakeDelegate = FakeLogDelegate()
    Logging.setDelegate(fakeDelegate)
    
    val log = LoggingTagged("test")
    log.info("test message")
    
    assertEquals(1, fakeDelegate.entries.size)
    assertEquals("test message", fakeDelegate.entries[0].msg)
    assertEquals(FakeLogDelegate.Level.INFO, fakeDelegate.entries[0].level)
    assertEquals("test", fakeDelegate.entries[0].tag)
    
    // 恢复默认委托（通过创建一个新的默认委托）
    Logging.setDelegate(object : LogDelegate {
        override fun debug(tag: String, msg: String) {
            System.out.println("[DEBUG] $tag: $msg")
        }
        
        override fun info(tag: String, msg: String) {
            System.out.println("[INFO] $tag: $msg")
        }
        
        override fun warn(tag: String, msg: String?, e: Throwable?) {
            System.err.println("[WARN] $tag: ${msg ?: ""}${if (e != null) "\n${e.stackTraceToString()}" else ""}")
        }
        
        override fun error(tag: String, msg: String?, e: Throwable?) {
            System.err.println("[ERROR] $tag: ${msg ?: ""}${if (e != null) "\n${e.stackTraceToString()}" else ""}")
        }
    })
}
```

这样就不需要访问 `DefaultLogDelegate` 了。

- [ ] **Step 3: 运行测试验证通过**

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.log.LoggingTest.testDelegateInjection"`
Expected: PASS

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.log.LoggingTest.testDelegateWithThrowable"`
Expected: PASS

Run: `./gradlew :agent:core:test --tests "io.github.yeyi.agent.log.LoggingTest.testMultipleDelegates"`
Expected: PASS

- [ ] **Step 4: 运行所有测试确保不破坏现有功能**

Run: `./gradlew :agent:core:test`
Expected: 所有测试通过

- [ ] **Step 5: 提交**

```bash
git add agent/core/src/test/kotlin/io/github/yeyi/agent/log/LoggingTest.kt
git commit -m "test: add delegate injection tests"
```

---

### Task 6: 最终验证和清理

**Files:**
- Modify: `agent/core/src/main/kotlin/io/github/yeyi/agent/log/Logging.kt`

- [ ] **Step 1: 确保所有代码符合项目风格**

检查代码格式，确保一致的缩进和命名。

- [ ] **Step 2: 运行完整测试套件**

Run: `./gradlew :agent:core:test`
Expected: 所有测试通过

- [ ] **Step 3: 验证 API 可用性**

检查 `LogDelegate` 接口和 `FakeLogDelegate` 类是否为 public，`DefaultLogDelegate` 是否为 private。

- [ ] **Step 4: 最终提交**

```bash
git add .
git commit -m "feat: complete logging delegate implementation"
```