package io.gateway.api

import kotlinx.coroutines.flow.Flow

public interface ConcurrencyController {

    public suspend fun acquire(): Boolean

    public fun release()

    public val processingCount: Int

    public val maxConcurrency: Int

    public fun observeProcessing(): Flow<Int>
}
