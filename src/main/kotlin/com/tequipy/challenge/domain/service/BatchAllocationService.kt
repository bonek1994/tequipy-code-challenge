package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.command.ProcessAllocationCommand
import com.tequipy.challenge.domain.model.AllocationProcessingState
import com.tequipy.challenge.domain.model.AllocationProcessedResult
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.spi.AllocationEventPublisher
import com.tequipy.challenge.domain.port.spi.AllocationProcessingRepository
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID
import kotlin.system.measureNanoTime

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
        const val MAX_BATCH_SIZE = 100
        private const val LOCK_OVERSAMPLE_FACTOR = 2
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
        val outcomes = mutableListOf<AllocationProcessedResult>()
        for (cmd in commands) {
            if (allocationProcessingRepository.tryStart(cmd.allocationId)) {
                newCommands += cmd
            } else {
                val existing = allocationProcessingRepository.findById(cmd.allocationId) ?: continue
                when (existing.state) {
                    AllocationProcessingState.ALLOCATED -> {
                        logger.info { "Allocation ${cmd.allocationId} already processed, republishing cached result" }
                        outcomes += AllocationProcessedResult(
                            allocationId = cmd.allocationId,
                            success = true,
                            allocatedEquipmentIds = existing.allocatedEquipmentIds
                        )
                    }
                    AllocationProcessingState.FAILED -> {
                        logger.info { "Allocation ${cmd.allocationId} already failed, republishing cached result" }
                        outcomes += AllocationProcessedResult(
                            allocationId = cmd.allocationId,
                            success = false,
                            allocatedEquipmentIds = emptyList()
                        )
                    }
                    AllocationProcessingState.PROCESSING -> {
                        logger.warn { "Allocation ${cmd.allocationId} is already in PROCESSING state, skipping" }
                    }
                }
            }
        }

        if (newCommands.isEmpty()) {
            allocationEventPublisher.publishAllocationProcessedBatch(outcomes)
            return
        }

        // ── Step 2: Compute batch-wide equipment constraints ──────────────────
        val allTypes  = newCommands.flatMap { it.policy.map { req -> req.type } }.toSet()
        val globalMin = newCommands.flatMap { it.policy.mapNotNull { req -> req.minimumConditionScore } }
                                   .minOrNull() ?: 0.0

        // ── Step 3: Single read for all candidate equipment ───────────────────
        val available = equipmentRepository.findAvailableWithMinConditionScore(allTypes, globalMin)

        // ── Step 4: Lock a bounded oversample (not the entire eligible pool) ──
        // IMPORTANT: take per-type, not globally.
        // Without ORDER BY the DB returns rows in index order (state, type, conditionScore),
        // so a global `take(totalSlots × factor)` would grab items from only the first
        // type alphabetically, starving every other type and causing mass FAILED allocations.
        val slotsPerType: Map<EquipmentType, Int> = newCommands
            .flatMap { it.policy }
            .groupBy { it.type }
            .mapValues { (_, reqs) -> reqs.sumOf { it.quantity } }

        val candidateIds: List<UUID> = available
            .groupBy { it.type }
            .flatMap { (type, items) ->
                val slotsForType = slotsPerType[type] ?: 0
                items.map { it.id }.take(slotsForType * LOCK_OVERSAMPLE_FACTOR)
            }

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
            var selected: List<Equipment>?
            val algorithmDurationNanos = measureNanoTime {
                selected = allocationAlgorithm.allocate(cmd.policy, poolForCmd)
            }
            AllocationAlgorithmMetrics.record(algorithmDurationNanos)
            val chosen = selected
            if (chosen != null) {
                usedIds   += chosen.map { it.id }
                toReserve += chosen
                results[cmd.allocationId] = chosen.map { it.id }
            } else {
                results[cmd.allocationId] = null
            }
        }

        // ── Step 7: Persist all reservations in one round-trip ────────────────
        if (toReserve.isNotEmpty()) {
            equipmentRepository.updateAll(toReserve.map { it.copy(state = EquipmentState.RESERVED) })
        }

        // ── Step 8: Record outcomes and publish events ────────────────────────
        for (cmd in newCommands) {
            val reserved = results[cmd.allocationId]
            val state    = if (reserved != null) AllocationProcessingState.ALLOCATED
                           else AllocationProcessingState.FAILED
            allocationProcessingRepository.complete(cmd.allocationId, state, reserved ?: emptyList())
            outcomes += AllocationProcessedResult(
                allocationId = cmd.allocationId,
                success = reserved != null,
                allocatedEquipmentIds = reserved ?: emptyList()
            )
        }

        allocationEventPublisher.publishAllocationProcessedBatch(outcomes)

        val successCount = results.values.count { it != null }
        val failedCount  = results.values.count { it == null }
        logger.info { "Batch complete: $successCount allocated, $failedCount failed" }
        metrics.recordBatch(newCommands.size, successCount, failedCount)
    }
}
