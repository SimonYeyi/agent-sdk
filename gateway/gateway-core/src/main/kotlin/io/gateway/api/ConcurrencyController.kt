package io.gateway.api

import kotlinx.coroutines.flow.Flow

interface ConcurrencyController {

    suspend fun acquire(): Boolean

    fun release()

    val processingCount: Int

    val maxConcurrency: Int

    fun observeProcessing(): Flow<Int>
}
