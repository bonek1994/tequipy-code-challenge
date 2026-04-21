package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import java.util.UUID

class AllocationAlgorithm {

    companion object {
        /**
         * Multiplier applied to the number of slots in a constraint group to
         * determine how many top-scoring candidates to consider per slot.
         *
         * e.g. request for 5 monitors → 5 × 3 = top-15 candidates per slot.
         */
        const val CANDIDATE_MULTIPLIER = 3

        /**
         * Score bonus applied to equipment whose brand matches the preferred brand
         * in a policy requirement. Used both by the algorithm and by the batch
         * service when pre-ranking candidates before locking.
         */
        const val BRAND_BONUS = 10.0
    }

    fun allocate(
        policy: List<EquipmentPolicyRequirement>,
        availableEquipment: List<Equipment>
    ): List<Equipment>? {
        // Filter to equipment that are physically available in inventory.
        // downstream steps assume only AVAILABLE items are considered.
        val eligible = availableEquipment.filter { it.state == EquipmentState.AVAILABLE }

        // Expand each requirement into individual "slots" (unit requests).
        // Example: requirement(quantity=3) -> three slots, each processed independently.
        // This simplifies allocation logic by treating each unit as a separate selection.
        val slots = policy.flatMap { req -> List(req.quantity) { req.copy(quantity = 1) } }
        if (slots.isEmpty()) return emptyList()

        // Precompute recency-normalized scores so newer equipment can be slightly preferred.
        val recencyScores = computeRecencyScores(eligible)

        // Group slots by their hard-constraint key (type + minScore) so that when
        // picking candidates we can consider more items for larger groups. This
        // enables fairer selection when multiple identical slots are requested.
        val slotsPerGroup = slots.groupingBy { it.constraintKey() }.eachCount()

        // For each slot produce a short list of top candidates. We don't keep all
        // eligible items; instead we take only `groupSize * CANDIDATE_MULTIPLIER` top
        // scorers per slot. This reduces combinatorial explosion while retaining
        // good-quality options. Note: the same equipment can appear in multiple
        // slot candidate lists — uniqueness is enforced later when picking.
        val candidatesPerSlot = slots.map { slot ->
            val groupSize = slotsPerGroup[slot.constraintKey()] ?: 1
            eligible
                .filter { it.matchesHardConstraints(slot) }
                .sortedByDescending { it.score(slot, recencyScores) }
                .take(groupSize * CANDIDATE_MULTIPLIER)
        }

        // If any slot has no candidates it means the policy cannot be satisfied.
        // We return `null` to indicate failure to allocate.
        if (candidatesPerSlot.any { it.isEmpty() }) return null

        // Process the slots in order of increasing candidate list size (most-constrained-first).
        // This heuristic reduces chance of dead-ends: slots with fewer options are fixed
        // earlier so they don't get robbed by slots with many choices.
        val processingOrder = slots.indices.sortedBy { candidatesPerSlot[it].size }

        // Pick actual items ensuring each equipment id is used at most once.
        // We iterate according to `processingOrder` and choose the highest-scored
        // candidate that hasn't been used yet. If we cannot find a free candidate
        // for a slot we must fail the entire allocation (returns null) — the
        // algorithm is atomic from the caller's perspective.
        val usedIds = mutableSetOf<UUID>()
        val chosen = mutableListOf<Equipment>()

        for (pos in processingOrder.indices) {
            val slotIdx = processingOrder[pos]
            val candidate = candidatesPerSlot[slotIdx].firstOrNull { it.id !in usedIds }
                ?: return null
            usedIds += candidate.id
            chosen += candidate
        }

        return chosen
    }

    private fun computeRecencyScores(eligible: List<Equipment>): Map<UUID, Double> {
        if (eligible.isEmpty()) return emptyMap()
        val minDate = eligible.minOf { it.purchaseDate }
        val maxDate = eligible.maxOf { it.purchaseDate }
        val rangeDays = minDate.until(maxDate, java.time.temporal.ChronoUnit.DAYS).toDouble()
        return eligible.associate { equipment ->
            val ageDays = minDate.until(equipment.purchaseDate, java.time.temporal.ChronoUnit.DAYS).toDouble()
            equipment.id to if (rangeDays == 0.0) 0.0 else ageDays / rangeDays
        }
    }

    private fun Equipment.matchesHardConstraints(req: EquipmentPolicyRequirement): Boolean =
        type == req.type && (req.minimumConditionScore == null || conditionScore >= req.minimumConditionScore)

    private fun Equipment.score(req: EquipmentPolicyRequirement, recencyScores: Map<UUID, Double>): Double {
        val brandBonus = if (req.preferredBrand?.equals(brand, ignoreCase = true) == true) BRAND_BONUS else 0.0
        val recency = recencyScores[id] ?: 0.0
        return brandBonus + conditionScore + recency
    }

    private fun EquipmentPolicyRequirement.constraintKey() = ConstraintKey(type, minimumConditionScore)

    private data class ConstraintKey(val type: EquipmentType, val minScore: Double?)
}
