package com.tequipy.challenge.domain.service

import org.springframework.stereotype.Component
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe counters for batch allocation pipeline observability.
 *
 * Updated by [BatchAllocationService] on every successful [BatchAllocationService.processBatch] call.
 * Exposed as a Spring bean so that integration and performance tests can read the values after the
 * processing window has closed.
 */
@Component
class BatchAllocationMetrics {

    val batchesProcessed = AtomicInteger(0)
    val allocatedCount   = AtomicInteger(0)
    val failedCount      = AtomicInteger(0)

    private val batchSizes = CopyOnWriteArrayList<Int>()

    fun recordBatch(batchSize: Int, allocated: Int, failed: Int) {
        batchesProcessed.incrementAndGet()
        allocatedCount.addAndGet(allocated)
        failedCount.addAndGet(failed)
        batchSizes.add(batchSize)
    }

    fun avgBatchSize(): Double = if (batchSizes.isEmpty()) 0.0 else batchSizes.average()
    fun minBatchSize(): Int   = batchSizes.minOrNull() ?: 0
    fun maxBatchSize(): Int   = batchSizes.maxOrNull() ?: 0
}
