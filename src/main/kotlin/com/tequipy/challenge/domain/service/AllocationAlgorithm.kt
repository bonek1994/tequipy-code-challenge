package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import java.util.UUID

class AllocationAlgorithm {

    companion object {
        /**
         * Score bonus applied to equipment whose brand matches the preferred brand
         * in a policy requirement. Used both by the algorithm and by the batch
         * service when pre-ranking candidates before locking.
         */
        const val BRAND_BONUS = 10.0
    }

    /**
     * Lookup key combining hard constraints ([type], [minimumConditionScore]) with the
     * optional soft constraint ([preferredBrand]). A null [preferredBrand] represents the
     * no-brand fallback key used when no brand-matching candidate is available.
     */
    private data class EquipmentKey(
        val type: EquipmentType,
        val minimumConditionScore: Double?,
        val preferredBrand: String?
    )

    /**
     * Allocates equipment from [availableEquipment] according to [policy].
     *
     * The algorithm builds a hashmap keyed by tuples of equipment parameters derived from
     * the policy. Each key maps to a pre-sorted list of all matching candidates (best score
     * first). Slots are processed in most-constrained-first order to maximise the chance of
     * satisfying hard constraints. For each slot:
     * 1. Look up the full key (type + minimumConditionScore + preferredBrand).
     * 2. If no available candidate is found and [EquipmentPolicyRequirement.preferredBrand]
     *    was set, fall back to the no-brand key (type + minimumConditionScore only).
     *
     * Returns null if any slot cannot be satisfied, or the chosen list otherwise.
     */
    fun allocate(
        policy: List<EquipmentPolicyRequirement>,
        availableEquipment: List<Equipment>
    ): List<Equipment>? {
        val eligible = availableEquipment.filter { it.state == EquipmentState.AVAILABLE }
        val slots = policy.flatMap { req -> List(req.quantity) { req.copy(quantity = 1) } }
        if (slots.isEmpty()) return emptyList()

        val recencyScores = computeRecencyScores(eligible)
        val index = buildIndex(eligible, slots, recencyScores)

        // Process most-constrained slots first (fewest hard-constraint candidates).
        val sortedSlots = slots.sortedBy { slot ->
            val hardKey = EquipmentKey(slot.type, slot.minimumConditionScore, null)
            index[hardKey]?.size ?: 0
        }

        val usedIds = mutableSetOf<UUID>()
        val selected = mutableListOf<Equipment>()

        for (slot in sortedSlots) {
            val candidate = findBestCandidate(slot, index, usedIds) ?: return null
            usedIds += candidate.id
            selected += candidate
        }

        return selected
    }

    /**
     * Builds the candidate hashmap from [eligible] equipment.
     *
     * Two keys are generated for every slot that carries a brand preference:
     * - a full key `(type, minimumConditionScore, preferredBrand)` whose list contains only
     *   brand-matching equipment, and
     * - a fallback key `(type, minimumConditionScore, null)` whose list contains all
     *   equipment that satisfies the hard constraints regardless of brand.
     *
     * Slots without a brand preference produce only the no-brand key. Within each bucket
     * candidates are sorted best-score-first so [findBestCandidate] can simply take the
     * first available entry.
     */
    private fun buildIndex(
        eligible: List<Equipment>,
        slots: List<EquipmentPolicyRequirement>,
        recencyScores: Map<UUID, Double>
    ): Map<EquipmentKey, List<Equipment>> {
        val keys = mutableSetOf<EquipmentKey>()
        for (slot in slots) {
            val brand = slot.preferredBrand?.lowercase()
            keys += EquipmentKey(slot.type, slot.minimumConditionScore, brand)
            if (brand != null) {
                keys += EquipmentKey(slot.type, slot.minimumConditionScore, null)
            }
        }
        return keys.associateWith { key ->
            eligible
                .filter { equipment ->
                    equipment.type == key.type &&
                        (key.minimumConditionScore == null || equipment.conditionScore >= key.minimumConditionScore) &&
                        (key.preferredBrand == null || equipment.brand.lowercase() == key.preferredBrand)
                }
                .sortedByDescending { it.score(key.preferredBrand, recencyScores) }
        }
    }

    /**
     * Returns the best available (not yet in [usedIds]) candidate for [slot].
     *
     * Tries the full key (including optional brand) first. If the slot carries a brand
     * preference but no matching candidate is free, retries with the no-brand fallback key.
     */
    private fun findBestCandidate(
        slot: EquipmentPolicyRequirement,
        index: Map<EquipmentKey, List<Equipment>>,
        usedIds: Set<UUID>
    ): Equipment? {
        val brand = slot.preferredBrand?.lowercase()
        val fullKey = EquipmentKey(slot.type, slot.minimumConditionScore, brand)
        val fromFull = index[fullKey]?.firstOrNull { it.id !in usedIds }
        if (fromFull != null) return fromFull

        if (brand != null) {
            val fallbackKey = EquipmentKey(slot.type, slot.minimumConditionScore, null)
            return index[fallbackKey]?.firstOrNull { it.id !in usedIds }
        }
        return null
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

    private fun Equipment.score(preferredBrand: String?, recencyScores: Map<UUID, Double>): Double {
        val brandBonus = if (preferredBrand != null && preferredBrand.equals(brand, ignoreCase = true)) BRAND_BONUS else 0.0
        val recency = recencyScores[id] ?: 0.0
        return brandBonus + conditionScore + recency
    }
}
