package io.github.yeyi.agent.demo.s2s

import io.github.yeyi.agent.realtime.Intention
import io.github.yeyi.agent.realtime.IntentionClassifier
import kotlinx.coroutines.delay

class SequentialIntentionClassifier : IntentionClassifier {
    private var callIndex = 0

    private val acks = listOf("好的", "收到", "马上")
    private val tasks = listOf("打开空调", "调暗灯光", "打开车窗")

    override suspend fun classify(asr: String): Intention {
        val index = callIndex++
        delay(50)
        val ack = acks[index % acks.size]
        val task = tasks[index % tasks.size]
        return when (index % 4) {
            0 -> Intention.Delegated(ack, task)
            1 -> Intention.Casual(ack)
            2 -> Intention.Casual(null)
            else -> throw RuntimeException("classify failed at index $index")
        }
    }
}