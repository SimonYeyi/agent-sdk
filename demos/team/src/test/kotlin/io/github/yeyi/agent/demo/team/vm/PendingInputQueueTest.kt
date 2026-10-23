package io.github.yeyi.agent.demo.team.vm

import io.github.yeyi.agent.demo.team.ui.PendingInput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [PendingInputQueue] 结构化回归测试。
 *
 * 覆盖 UI 层"指令排队"修复的状态机核心：指令发送后先入待确认队列，
 * 200ms 未收到 Initial 转排队态（悬浮列表提示），收到 Initial 后确认出队
 * 落为用户气泡。断言队列状态转换的正确性与幂等性，防止回归导致
 * 运行时显示顺序（报告→指令）与记忆恢复顺序颠倒。
 */
class PendingInputQueueTest {

    @Test
    fun `add appends unqueued item and returns its index`() {
        val queue = PendingInputQueue()

        val index = queue.add("打开空调")

        assertEquals(0, index)
        assertEquals(listOf(PendingInput("打开空调", queued = false)), queue.snapshot())
    }

    @Test
    fun `markQueued flips item to queued state for floating list`() {
        val queue = PendingInputQueue()
        val index = queue.add("打开空调")

        queue.markQueued(index)

        assertEquals(listOf(PendingInput("打开空调", queued = true)), queue.snapshot())
    }

    @Test
    fun `markQueued is idempotent and safe for stale or invalid indices`() {
        val queue = PendingInputQueue()
        val index = queue.add("打开空调")
        queue.markQueued(index)
        queue.markQueued(index)
        assertEquals(listOf(PendingInput("打开空调", queued = true)), queue.snapshot())

        // 已出队后，旧下标失效也不抛异常
        queue.confirm("打开空调")
        queue.markQueued(index)
        assertTrue(queue.snapshot().isEmpty())

        // 越界下标安全
        queue.markQueued(99)
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test
    fun `confirm removes matched item and returns true`() {
        val queue = PendingInputQueue()
        queue.add("打开空调")
        queue.markQueued(0)

        assertTrue(queue.confirm("打开空调"))
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test
    fun `confirm with unmatched text returns false without side effect`() {
        val queue = PendingInputQueue()
        queue.add("打开空调")

        assertFalse(queue.confirm("播放音乐"))
        assertEquals(listOf(PendingInput("打开空调", queued = false)), queue.snapshot())
    }

    @Test
    fun `confirm after timeout keeps order aligned with memory`() {
        // 真实场景：指令 B 在报告轮次 R 之后才被 Initial 确认。
        // 队列先入队（未排队）→ 超时转排队 → Initial 确认出队。
        val queue = PendingInputQueue()
        val index = queue.add("第二个指令")

        // 超时前 Initial 未到 → 转排队态（悬浮列表可见）
        queue.markQueued(index)
        assertEquals(listOf(PendingInput("第二个指令", queued = true)), queue.snapshot())

        // Initial 到达 → 确认出队，队列清空（悬浮框自动关闭）
        assertTrue(queue.confirm("第二个指令"))
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test
    fun `multiple queued instructions are confirmed independently in FIFO order`() {
        val queue = PendingInputQueue()
        val first = queue.add("指令一")
        queue.markQueued(first)
        val second = queue.add("指令二")
        queue.markQueued(second)

        // 确认第一条，仅该条出队
        assertTrue(queue.confirm("指令一"))
        assertEquals(listOf(PendingInput("指令二", queued = true)), queue.snapshot())

        // 确认第二条，队列清空
        assertTrue(queue.confirm("指令二"))
        assertTrue(queue.snapshot().isEmpty())
    }

    @Test
    fun `confirm removes first match when same text queued twice`() {
        val queue = PendingInputQueue()
        val first = queue.add("重复指令")
        queue.markQueued(first)
        queue.add("重复指令")

        assertTrue(queue.confirm("重复指令"))
        assertEquals(listOf(PendingInput("重复指令", queued = false)), queue.snapshot())
    }
}
