package io.gateway.util

public class MessageDeduplicator(
    private val maxSize: Int = 1000
) {
    private val seenIds = LinkedHashSet<String>()
    private val lock = Any()

    public fun isDuplicate(messageId: String): Boolean = synchronized(lock) {
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

    public fun clear(): Unit = synchronized(lock) {
        seenIds.clear()
    }

    public fun size(): Int = synchronized(lock) {
        seenIds.size
    }
}
