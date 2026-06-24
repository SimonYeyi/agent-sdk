package io.gateway.hook

import io.gateway.api.HookPipeline
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

class RateLimitHook(
    private val maxMessagesPerMinute: Int = 20,
    private val maxMessagesPerHour: Int = 200
) : HookPipeline.Hook {

    override val name: String = "rate-limit"

    override val events: Set<HookPipeline.Event> = setOf(HookPipeline.Event.BEFORE_VALIDATE)

    override val priority: Int = 20

    private val minuteTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val hourTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val mutex = Mutex()

    override suspend fun execute(context: HookPipeline.Context): HookPipeline.Result {
        val message = context.message ?: return HookPipeline.Result.Continue
        val sessionKey = message.source.sessionKey()
        val now = Clock.System.now().toEpochMilliseconds()

        mutex.withLock {
            val minuteList = minuteTimestamps.getOrPut(sessionKey) { mutableListOf() }
            val hourList = hourTimestamps.getOrPut(sessionKey) { mutableListOf() }

            val oneMinuteAgo = now - 60_000
            val oneHourAgo = now - 3_600_000

            minuteList.removeAll { it < oneMinuteAgo }
            hourList.removeAll { it < oneHourAgo }

            if (minuteList.size >= maxMessagesPerMinute) {
                return HookPipeline.Result.Halt("Rate limit exceeded: $maxMessagesPerMinute/minute")
            }

            if (hourList.size >= maxMessagesPerHour) {
                return HookPipeline.Result.Halt("Rate limit exceeded: $maxMessagesPerHour/hour")
            }

            minuteList.add(now)
            hourList.add(now)
        }

        return HookPipeline.Result.Continue
    }
}
