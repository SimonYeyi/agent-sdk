package io.gateway.engine

import io.gateway.api.ConcurrencyController
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.atomic.AtomicInteger

class DefaultConcurrencyController(
    override val maxConcurrency: Int = 10
) : ConcurrencyController {

    private val semaphore = Semaphore(maxConcurrency)
    private val _processingCount = MutableStateFlow(0)

    override val processingCount: Int
        get() = _processingCount.value

    override suspend fun acquire(): Boolean {
        val acquired = semaphore.tryAcquire()
        if (acquired) {
            _processingCount.value = _processingCount.value + 1
        }
        return acquired
    }

    override fun release() {
        semaphore.release()
        _processingCount.value = (_processingCount.value - 1).coerceAtLeast(0)
    }

    override fun observeProcessing(): Flow<Int> = _processingCount.asStateFlow()
}
