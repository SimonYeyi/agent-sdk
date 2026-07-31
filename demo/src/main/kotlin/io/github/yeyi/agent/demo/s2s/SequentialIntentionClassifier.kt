package io.github.yeyi.agent.demo.s2s

import io.github.yeyi.agent.demo.log
import io.github.yeyi.agent.realtime.Intention
import io.github.yeyi.agent.realtime.IntentionClassifier
import kotlinx.coroutines.delay

class SequentialIntentionClassifier : IntentionClassifier {
    private var callIndex = 0

    private val acks = listOf("好的", "收到", "马上")

    override suspend fun classify(asr: String): Intention {
        val index = callIndex++
        val ack = acks[index % acks.size]
        return when (index % 4) {
            0 -> Intention.Delegated(ack, asr).also { delay(1000L) }
            1 -> Intention.Casual(ack).also { delay(500L) }
            2 -> Intention.Casual(null).also { delay(100L) }
            else -> throw RuntimeException("classify failed at index $index")
        }.apply { log.info("classify $this") }
    }
}