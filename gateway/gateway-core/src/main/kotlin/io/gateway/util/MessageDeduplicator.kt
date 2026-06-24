package io.gateway.util

import java.util.LinkedList
import kotlin.math.max

class MessageDeduplicator(
    private val maxSize: Int = 1000
) {
    private val seenIds = LinkedHashSet<String>()
    private val lock = Any()

    fun isDuplicate(messageId: String): Boolean = synchronized(lock) {
        if (seenIds.contains(messageId)) {
            true
        } else {
            if (seenIds.size >= maxSize) {
                val iterator = seenIds.iterator()
                if (iterator.hasNext()) {
                    iterator.next()
                    iterator.remove()
                }
            }
            seenIds.add(messageId)
            false
        }
    }

    fun clear() = synchronized(lock) {
        seenIds.clear()
    }

    fun size(): Int = synchronized(lock) {
        seenIds.size
    }
}
