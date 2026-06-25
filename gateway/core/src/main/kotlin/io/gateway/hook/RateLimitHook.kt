package io.gateway.hook

import io.gateway.api.HookPipeline
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Clock

public class RateLimitHook(
    private val maxMessagesPerMinute: Int = 20,
    private val maxMessagesPerHour: Int = 200,
    private val cleanupIntervalMs: Long = 3_600_000  // 1 hour
) : HookPipeline.Hook {

    override val name: String = "rate-limit"

    override val events: Set<HookPipeline.Event> = setOf(HookPipeline.Event.BEFORE_VALIDATE)

    override val priority: Int = 20

    private val minuteTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val hourTimestamps = mutableMapOf<String, MutableList<Long>>()
    private val mutex = Mutex()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    init {
        scope.launch {
            while (true) {
                kotlinx.coroutines.delay(cleanupIntervalMs)
                cleanupStaleEntries()
            }
        }
    }

    private suspend fun cleanupStaleEntries() {
        mutex.withLock {
            val now = Clock.System.now().toEpochMilliseconds()
            val oneHourAgo = now - 3_600_000

            val minuteKeysToRemove = mutableListOf<String>()
            for ((key, list) in minuteTimestamps) {
                list.removeAll { it < oneHourAgo }
                if (list.isEmpty()) {
                    minuteKeysToRemove.add(key)
                }
            }
            minuteKeysToRemove.forEach { minuteTimestamps.remove(it) }

            val hourKeysToRemove = mutableListOf<String>()
            for ((key, list) in hourTimestamps) {
                list.removeAll { it < oneHourAgo }
                if (list.isEmpty()) {
                    hourKeysToRemove.add(key)
                }
            }
            hourKeysToRemove.forEach { hourTimestamps.remove(it) }
        }
    }

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
