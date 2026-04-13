package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.command.ProcessAllocationCommand
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.locks.ReentrantLock

/**
 * Accumulates incoming [ProcessAllocationCommand]s and flushes them to
 * [BatchAllocationService] either when the batch reaches [BatchAllocationService.MAX_BATCH_SIZE]
 * or when the configured time window (`tequipy.batch-allocation.window-ms`, default 5 000 ms)
 * expires — whichever comes first.
 *
 * This removes per-request DB-transaction contention: N individual transactions become at
 * most ⌈N / [BatchAllocationService.MAX_BATCH_SIZE]⌉ batch transactions.
 *
 * Thread-safety: a [ReentrantLock] (non-blocking `tryLock`) ensures that at most one flush
 * runs at a time; concurrent callers that lose the race simply return without blocking.
 */
@Component
class BatchAllocationCollector(
    private val batchAllocationService: BatchAllocationService,
    @Value("\${tequipy.batch-allocation.queue-capacity:10000}")
    private val queueCapacity: Int
) {
    private val logger = KotlinLogging.logger {}
    private val lock   = ReentrantLock()

    // Lazily sized so the capacity property is resolved before construction.
    private val pending by lazy { ArrayBlockingQueue<ProcessAllocationCommand>(queueCapacity) }

    /**
     * Enqueues [command] for batch processing.
     *
     * If the queue is full a flush is triggered immediately before re-attempting the enqueue,
     * guaranteeing forward progress under sustained load.
     *
     * If the queue reaches [BatchAllocationService.MAX_BATCH_SIZE] after enqueue the batch
     * is flushed eagerly (no need to wait for the scheduled window).
     */
    fun submit(command: ProcessAllocationCommand) {
        if (!pending.offer(command)) {
            logger.warn { "Batch queue full; forcing flush before enqueue for allocation ${command.allocationId}" }
            flush()
            pending.put(command)
        }
        if (pending.size >= BatchAllocationService.MAX_BATCH_SIZE) {
            flush()
        }
    }

    /**
     * Scheduled drain: fires every `tequipy.batch-allocation.window-ms` milliseconds
     * (default 5 000 ms) to flush any partial batches that haven't yet reached
     * [BatchAllocationService.MAX_BATCH_SIZE].
     */
    @Scheduled(fixedDelayString = "\${tequipy.batch-allocation.window-ms:5000}")
    fun scheduledFlush() = flush()

    /**
     * Drains up to [BatchAllocationService.MAX_BATCH_SIZE] commands and delegates to
     * [BatchAllocationService.processBatch].  Non-blocking: returns immediately if another
     * thread is already flushing.
     */
    fun flush() {
        if (!lock.tryLock()) return
        try {
            val batch = mutableListOf<ProcessAllocationCommand>()
            pending.drainTo(batch, BatchAllocationService.MAX_BATCH_SIZE)
            if (batch.isNotEmpty()) {
                logger.debug { "Flushing batch of ${batch.size} allocation command(s)" }
                batchAllocationService.processBatch(batch)
            }
        } catch (e: Exception) {
            logger.error(e) { "Error while processing allocation batch" }
        } finally {
            lock.unlock()
        }
    }
}
