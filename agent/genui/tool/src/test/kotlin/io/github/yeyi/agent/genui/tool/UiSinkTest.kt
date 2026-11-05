package io.github.yeyi.agent.genui.tool

import io.github.yeyi.agent.genui.ir.CardIr
import io.github.yeyi.agent.genui.ir.TreeNode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class UiSinkTest {

    @Test
    fun `订阅后 emit 立即收到卡片`() = runTest {
        val sink = UiSink()
        val card = CardIr(instanceId = "c1", root = TreeNode(component = "Text"))
        val received = mutableListOf<CardIr>()
        val job = backgroundScope.launch { sink.cards.take(1).collect { received += it } }
        runCurrent() // 排空调度队列，确保订阅注册后再发射

        sink.emit(card)
        runCurrent() // 处理订阅者恢复任务，确保卡片已投递

        job.join()
        assertEquals(listOf(card), received)
    }

    @Test
    fun `无订阅者时发射不阻塞也不缓存`() = runTest {
        val sink = UiSink()
        val card = CardIr(instanceId = "c1", root = TreeNode(component = "Text"))

        sink.emit(card)
    }

    @Test
    fun `订阅者消费滞后时溢出丢弃且发射不挂起`() = runTest {
        val sink = UiSink()
        // 消费第一张后长时间挂起，模拟 UI 渲染卡顿导致消费远慢于产出
        val job = backgroundScope.launch { sink.cards.collect { delay(60_000) } }
        runCurrent()

        withTimeout(1_000) {
            (1..100).forEach {
                sink.emit(CardIr(instanceId = "c$it", root = TreeNode(component = "Text")))
            }
        }
        job.cancel()
    }
}
