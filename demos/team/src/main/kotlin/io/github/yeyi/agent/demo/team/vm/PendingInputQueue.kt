package io.github.yeyi.agent.demo.team.vm

import io.github.yeyi.agent.demo.team.ui.PendingInput

/**
 * 发送后待确认指令队列的状态机。
 *
 * 背景：BossAgent 处于任务报告轮次时，新指令进入 scheduler 队列待报告完成才执行；
 * 若发送时立即渲染用户气泡，重启后记忆恢复顺序（报告→指令）与运行时显示顺序颠倒。
 * 因此指令发送后先入队等待对应 Initial 事件确认：200ms 内未收到 Initial 则转为
 * 排队态（UI 在输入框上方展示悬浮列表提示"正在队列中"），收到 Initial 后确认出队
 * 并渲染为用户气泡，保证显示顺序与记忆顺序一致。
 *
 * 三个状态转换均为幂等操作，供 [DemoViewModel] 在协程时序中安全调用：
 * [add] 入队 → 超时后 [markQueued] 转排队态 → [confirm] 出队。
 */
class PendingInputQueue {

    private val items = mutableListOf<PendingInput>()

    /** 入队待确认；返回条目下标，供 [markQueued] 精确定位。 */
    fun add(text: String): Int {
        val index = items.size
        items.add(PendingInput(text, queued = false))
        return index
    }

    /** 超时未收到 Initial：转为排队态（幂等，已出队/已排队均安全）。 */
    fun markQueued(index: Int) {
        val item = items.getOrNull(index) ?: return
        if (!item.queued) items[index] = item.copy(queued = true)
    }

    /**
     * Initial 到达：按文本匹配第一条待确认指令并出队，返回是否确认成功；
     * 未匹配（指令早已出队或来自报告轮次）返回 false，不产生副作用。
     */
    fun confirm(text: String): Boolean {
        val index = items.indexOfFirst { it.text == text }
        if (index < 0) return false
        items.removeAt(index)
        return true
    }

    /** 当前队列快照（供 UI 状态流消费）。 */
    fun snapshot(): List<PendingInput> = items.toList()
}
