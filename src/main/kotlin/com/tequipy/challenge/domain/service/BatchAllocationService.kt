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
 * - Requests are ordered by **most-constrained-total first**: each policy requirement
 *   contributes `quantity × (CONSTRAINT_BASE_WEIGHT + preferredBrandBonus + minimumConditionScore)`
 *   to the total weight. `CONSTRAINT_BASE_WEIGHT = 10.0` makes quantity the primary scale;
 *   `PREFERRED_BRAND_WEIGHT = 2.0` is added when a preferred brand is specified; and
 *   `minimumConditionScore` (in [0, 1]) adds the strictness of the quality threshold. Ties
 *   are broken by total slot count descending.
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

        /**
         * Base constraint weight applied to every slot in the ordering heuristic.
         * Ensures that requests needing more items always rank above requests needing
         * fewer items when all other constraint components are equal. Set to 10.0 so
         * that it sits on the same scale as [PREFERRED_BRAND_WEIGHT] and
         * [AllocationAlgorithm.BRAND_BONUS].
         */
        private const val CONSTRAINT_BASE_WEIGHT = 10.0

        /**
         * Additional weight added per slot when a policy requirement specifies a
         * preferred brand. Reflects that brand-preferring requests compete more
         * intensely for a narrower subset of the locked pool.
         */
        private const val PREFERRED_BRAND_WEIGHT = 2.0

        private val logger = KotlinLogging.logger {}
    }

    private val algorithm = AllocationAlgorithm()

    @Transactional
    fun processBatch(commands: List<ProcessAllocationCommand>) {
        if (commands.isEmpty()) return
        logger.info { "Processing batch of ${commands.size} allocation(s)" }

        val (newCommands, cachedOutcomes) = partitionByIdempotency(commands)

        if (newCommands.isEmpty()) {
            allocationEventPublisher.publishAllocationProcessedBatch(cachedOutcomes)
            return
        }

        val lockedPool = lockCandidatePool(newCommands)
        val (resultsByAllocation, toReserve) = allocatePerCommand(newCommands, lockedPool)

        if (toReserve.isNotEmpty()) {
            equipmentRepository.updateAll(toReserve.map { it.copy(state = EquipmentState.RESERVED) })
        }

        val outcomes = persistOutcomes(newCommands, resultsByAllocation) + cachedOutcomes
        allocationEventPublisher.publishAllocationProcessedBatch(outcomes)

        val successCount = resultsByAllocation.values.count { it != null }
        val failedCount = resultsByAllocation.values.count { it == null }
        logger.info { "Batch complete: $successCount allocated, $failedCount failed" }
        metrics.recordBatch(newCommands.size, successCount, failedCount)
    }

    // ── Idempotency ────────────────────────────────────────────────────────

    private fun partitionByIdempotency(
        commands: List<ProcessAllocationCommand>
    ): Pair<List<ProcessAllocationCommand>, List<AllocationProcessedResult>> {
        val newCommands = mutableListOf<ProcessAllocationCommand>()
        val cached = mutableListOf<AllocationProcessedResult>()

        for (cmd in commands) {
            if (allocationProcessingRepository.tryStart(cmd.allocationId)) {
                newCommands += cmd
            } else {
                cachedResultFor(cmd.allocationId)?.let { cached += it }
            }
        }
        return newCommands to cached
    }

    private fun cachedResultFor(allocationId: UUID): AllocationProcessedResult? {
        val existing = allocationProcessingRepository.findById(allocationId) ?: return null
        return when (existing.state) {
            AllocationProcessingState.ALLOCATED -> {
                logger.info { "Allocation $allocationId already processed, republishing cached result" }
                AllocationProcessedResult(allocationId, success = true, existing.allocatedEquipmentIds)
            }
            AllocationProcessingState.FAILED -> {
                logger.info { "Allocation $allocationId already failed, republishing cached result" }
                AllocationProcessedResult(allocationId, success = false)
            }
            AllocationProcessingState.PROCESSING -> {
                logger.warn { "Allocation $allocationId is already in PROCESSING state, skipping" }
                null
            }
        }
    }

    // ── Pool locking ───────────────────────────────────────────────────────

    private fun lockCandidatePool(commands: List<ProcessAllocationCommand>): List<Equipment> {
        val allTypes = commands.flatMap { it.policy.map { req -> req.type } }.toSet()
        val globalMin = commands.flatMap { it.policy.mapNotNull { req -> req.minimumConditionScore } }
            .minOrNull() ?: 0.0

        val available = equipmentRepository.findAvailableWithMinConditionScore(allTypes, globalMin)

        val slotsPerType: Map<EquipmentType, Int> = commands
            .flatMap { it.policy }
            .groupBy { it.type }
            .mapValues { (_, reqs) -> reqs.sumOf { it.quantity } }

        // Collect all preferred brands (lower-cased) so candidates are pre-ranked the
        // same way AllocationAlgorithm scores them: brand bonus + conditionScore.
        // Without this, preferred-brand items with lower condition scores would be
        // excluded from the locked pool before the algorithm ever runs.
        val preferredBrands: Set<String> = commands
            .flatMap { it.policy.mapNotNull { req -> req.preferredBrand?.lowercase() } }
            .toSet()

        val candidateIds = available
            .groupBy { it.type }
            .flatMap { (type, items) ->
                val limit = (slotsPerType[type] ?: 0) * LOCK_OVERSAMPLE_FACTOR
                items
                    .sortedByDescending { item ->
                        val bonus = if (preferredBrands.isNotEmpty() &&
                            item.brand.lowercase() in preferredBrands
                        ) AllocationAlgorithm.BRAND_BONUS else 0.0
                        bonus + item.conditionScore
                    }
                    .take(limit)
                    .map { it.id }
            }

        if (candidateIds.isEmpty()) return emptyList()
        return equipmentRepository.findByIdsForUpdate(candidateIds, globalMin)
    }

    // ── Per-command algorithm ──────────────────────────────────────────────

    private data class AllocateResult(
        val resultsByAllocation: Map<UUID, List<UUID>?>,
        val toReserve: List<Equipment>
    )

    private fun allocatePerCommand(
        commands: List<ProcessAllocationCommand>,
        pool: List<Equipment>
    ): AllocateResult {
        val sorted = commands.sortedWith(
            compareByDescending<ProcessAllocationCommand> { cmd ->
                // Weight per requirement = quantity × (base + brandBonus + minimumConditionScore).
                // CONSTRAINT_BASE_WEIGHT (10.0) per slot ensures quantity contributes at the same
                // scale as the constraint bonuses. PREFERRED_BRAND_WEIGHT (2.0) reflects that
                // brand-preferring requirements compete for a narrower subset of the pool.
                cmd.policy.sumOf { req ->
                    val brandBonus = if (req.preferredBrand != null) PREFERRED_BRAND_WEIGHT else 0.0
                    req.quantity * (CONSTRAINT_BASE_WEIGHT + brandBonus + (req.minimumConditionScore ?: 0.0))
                }
            }.thenByDescending { cmd ->
                cmd.policy.sumOf { it.quantity }
            }
        )

        val usedIds = mutableSetOf<UUID>()
        val toReserve = mutableListOf<Equipment>()
        val results = mutableMapOf<UUID, List<UUID>?>()

        for (cmd in sorted) {
            val available = pool.filter { it.id !in usedIds }
            var selected: List<Equipment>?
            val nanos = measureNanoTime { selected = algorithm.allocate(cmd.policy, available) }
            AllocationAlgorithmMetrics.record(nanos)

            val chosen = selected
            if (chosen != null) {
                usedIds += chosen.map { it.id }
                toReserve += chosen
                results[cmd.allocationId] = chosen.map { it.id }
            } else {
                results[cmd.allocationId] = null
            }
        }
        return AllocateResult(results, toReserve)
    }

    // ── Persistence ────────────────────────────────────────────────────────

    private fun persistOutcomes(
        commands: List<ProcessAllocationCommand>,
        results: Map<UUID, List<UUID>?>
    ): List<AllocationProcessedResult> = commands.map { cmd ->
        val reserved = results[cmd.allocationId]
        val state = if (reserved != null) AllocationProcessingState.ALLOCATED else AllocationProcessingState.FAILED
        allocationProcessingRepository.complete(cmd.allocationId, state, reserved ?: emptyList())
        AllocationProcessedResult(cmd.allocationId, success = reserved != null, reserved ?: emptyList())
    }
}
