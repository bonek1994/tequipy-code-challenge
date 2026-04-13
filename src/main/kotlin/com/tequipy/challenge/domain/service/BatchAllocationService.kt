package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.command.ProcessAllocationCommand
import com.tequipy.challenge.domain.model.AllocationProcessingState
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.spi.AllocationEventPublisher
import com.tequipy.challenge.domain.port.spi.AllocationProcessingRepository
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Processes a batch of [ProcessAllocationCommand]s in a single database transaction,
 * eliminating the per-request lock contention that causes retry storms under high load.
 *
 * Key design choices:
 * - One `SELECT … FOR UPDATE SKIP LOCKED` covers the entire batch (not one per request).
 * - A generous oversample (`totalSlots × LOCK_OVERSAMPLE_FACTOR`) is locked, not the full
 *   eligible pool, so lock scope stays bounded regardless of inventory size.
 * - Requests with the strictest `minimumConditionScore` are processed first within the batch
 *   so they get first pick of the locked pool (most-constrained-first ordering).
 * - [AllocationAlgorithm] runs unchanged per request; quality is fully preserved.
 * - Idempotency is enforced via [AllocationProcessingRepository.tryStart]; duplicate
 *   messages receive cached results without re-running the algorithm.
 */
@Service
class BatchAllocationService(
    private val equipmentRepository: EquipmentRepository,
    private val allocationProcessingRepository: AllocationProcessingRepository,
    private val allocationEventPublisher: AllocationEventPublisher,
    private val metrics: BatchAllocationMetrics
) {
    companion object {
        const val MAX_BATCH_SIZE = 20
        private const val LOCK_OVERSAMPLE_FACTOR = 3
        private val logger = KotlinLogging.logger {}
    }

    private val allocationAlgorithm = AllocationAlgorithm()

    @Transactional
    fun processBatch(commands: List<ProcessAllocationCommand>) {
        if (commands.isEmpty()) return
        logger.info { "Processing batch of ${commands.size} allocation(s)" }

        // ── Step 1: Idempotency ───────────────────────────────────────────────
        // tryStart inserts a PROCESSING row (ON CONFLICT DO NOTHING).
        // Returns true for brand-new allocations; false if a row already exists.
        val newCommands = mutableListOf<ProcessAllocationCommand>()
        for (cmd in commands) {
            if (allocationProcessingRepository.tryStart(cmd.allocationId)) {
                newCommands += cmd
            } else {
                val existing = allocationProcessingRepository.findById(cmd.allocationId) ?: continue
                when (existing.state) {
                    AllocationProcessingState.ALLOCATED -> {
                        logger.info { "Allocation ${cmd.allocationId} already processed, republishing cached result" }
                        allocationEventPublisher.publishAllocationProcessed(
                            cmd.allocationId, true, existing.allocatedEquipmentIds
                        )
                    }
                    AllocationProcessingState.FAILED -> {
                        logger.info { "Allocation ${cmd.allocationId} already failed, republishing cached result" }
                        allocationEventPublisher.publishAllocationProcessed(cmd.allocationId, false)
                    }
                    AllocationProcessingState.PROCESSING -> {
                        logger.warn { "Allocation ${cmd.allocationId} is already in PROCESSING state, skipping" }
                    }
                }
            }
        }

        if (newCommands.isEmpty()) return

        // ── Step 2: Compute batch-wide equipment constraints ──────────────────
        val allTypes  = newCommands.flatMap { it.policy.map { req -> req.type } }.toSet()
        val globalMin = newCommands.flatMap { it.policy.mapNotNull { req -> req.minimumConditionScore } }
                                   .minOrNull() ?: 0.0
        val totalSlots = newCommands.sumOf { cmd -> cmd.policy.sumOf { it.quantity } }

        // ── Step 3: Single read for all candidate equipment ───────────────────
        val available = equipmentRepository.findAvailableWithMinConditionScore(allTypes, globalMin)

        // ── Step 4: Lock a bounded oversample (not the entire eligible pool) ──
        // Locking only `totalSlots × factor` rows prevents the "lock everything,
        // fail on one skip" anti-pattern in InventoryAllocationService.
        val candidateIds = available.map { it.id }.take(totalSlots * LOCK_OVERSAMPLE_FACTOR)
        val lockedPool: List<Equipment> = if (candidateIds.isEmpty()) {
            emptyList()
        } else {
            equipmentRepository.findByIdsForUpdate(candidateIds, globalMin)
        }

        // ── Step 5: Most-constrained-first ordering within the batch ──────────
        val sortedCommands = newCommands.sortedByDescending { cmd ->
            cmd.policy.mapNotNull { it.minimumConditionScore }.maxOrNull() ?: 0.0
        }

        // ── Step 6: Run AllocationAlgorithm per request, no double-assignment ─
        val usedIds   = mutableSetOf<UUID>()
        val toReserve = mutableListOf<Equipment>()
        val results   = mutableMapOf<UUID, List<UUID>?>()

        for (cmd in sortedCommands) {
            val poolForCmd = lockedPool.filter { it.id !in usedIds }
            val selected   = allocationAlgorithm.allocate(cmd.policy, poolForCmd)
            if (selected != null) {
                usedIds   += selected.map { it.id }
                toReserve += selected
                results[cmd.allocationId] = selected.map { it.id }
            } else {
                results[cmd.allocationId] = null
            }
        }

        // ── Step 7: Persist all reservations in one round-trip ────────────────
        if (toReserve.isNotEmpty()) {
            equipmentRepository.saveAll(toReserve.map { it.copy(state = EquipmentState.RESERVED) })
        }

        // ── Step 8: Record outcomes and publish events ────────────────────────
        for (cmd in newCommands) {
            val reserved = results[cmd.allocationId]
            val state    = if (reserved != null) AllocationProcessingState.ALLOCATED
                           else AllocationProcessingState.FAILED
            allocationProcessingRepository.complete(cmd.allocationId, state, reserved ?: emptyList())
            allocationEventPublisher.publishAllocationProcessed(
                cmd.allocationId,
                success = reserved != null,
                allocatedEquipmentIds = reserved ?: emptyList()
            )
        }

        val successCount = results.values.count { it != null }
        val failedCount  = results.values.count { it == null }
        logger.info { "Batch complete: $successCount allocated, $failedCount failed" }
        metrics.recordBatch(newCommands.size, successCount, failedCount)
    }
}
