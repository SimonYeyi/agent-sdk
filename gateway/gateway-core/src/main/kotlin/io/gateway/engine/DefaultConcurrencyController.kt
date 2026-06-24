package io.gateway.engine

import io.gateway.api.ConcurrencyController
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.locks.ReentrantLock

internal class DefaultConcurrencyController(
    override val maxConcurrency: Int = 10
) : ConcurrencyController {

    private val semaphore = Semaphore(maxConcurrency)
    private val _processingCount = MutableStateFlow(0)
    private val lock = ReentrantLock()

    override val processingCount: Int
        get() = _processingCount.value

    override suspend fun acquire(): Boolean {
        lock.lock()
        return try {
            val acquired = semaphore.tryAcquire()
            if (acquired) {
                _processingCount.value += 1
            }
            acquired
        } finally {
            lock.unlock()
        }
    }

    override fun release() {
        lock.lock()
        try {
            semaphore.release()
            _processingCount.value = (_processingCount.value - 1).coerceAtLeast(0)
        } finally {
            lock.unlock()
        }
    }

    override fun observeProcessing(): Flow<Int> = _processingCount.asStateFlow()
}
