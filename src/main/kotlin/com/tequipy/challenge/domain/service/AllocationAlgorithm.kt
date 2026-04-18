package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import java.util.Locale
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
     * [preferredBrand] is always stored as lowercase([Locale.ROOT]) or null.
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
     * Per-key cursors advance past consumed entries so that each bucket entry is visited at
     * most once per key across all slots, giving amortized O(1) lookup per slot.
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

        // Pre-normalize equipment brands once with a stable locale to avoid repeated
        // allocations and locale-sensitive casing issues during index build and scoring.
        val normalizedBrand: Map<UUID, String> = eligible.associate { it.id to it.brand.lowercase(Locale.ROOT) }

        val index = buildIndex(eligible, normalizedBrand, slots, recencyScores)

        // Per-key cursors track the scan position; used entries at the head of a bucket are
        // skipped permanently so each entry is visited at most once per key.
        val cursors: MutableMap<EquipmentKey, Int> = index.keys.associateWithTo(mutableMapOf()) { 0 }

        // Process most-constrained slots first (fewest hard-constraint candidates).
        // Slots whose hard-constraint bucket has no candidates (size = 0) have no possible
        // match and are also placed first so the algorithm fails fast without wasting work
        // on other slots.
        val sortedSlots = slots.sortedBy { slot ->
            val hardKey = EquipmentKey(slot.type, slot.minimumConditionScore, null)
            index[hardKey]?.size ?: 0
        }

        val usedIds = mutableSetOf<UUID>()
        val selected = mutableListOf<Equipment>()

        for (slot in sortedSlots) {
            val candidate = findBestCandidate(slot, index, cursors, usedIds) ?: return null
            usedIds += candidate.id
            selected += candidate
        }

        return selected
    }

    /**
     * Builds the candidate hashmap from [eligible] equipment.
     *
     * Equipment brands are compared using the pre-normalized [normalizedBrand] map
     * (lowercased with [Locale.ROOT]) to avoid locale-sensitive casing issues and
     * repeated string allocations.
     *
     * Eligible items are first grouped by type (O(|eligible|)). Each key then filters
     * only its type group (O(|type-group|)), so the total build cost is
     * O(|eligible| + |keys| × avg_type_group_size) rather than O(|keys| × |eligible|).
     *
     * Two keys are generated for every slot that carries a brand preference:
     * - a full key `(type, minimumConditionScore, preferredBrand)` whose list contains only
     *   brand-matching equipment, sorted by `score(preferredBrand, ...)` which applies
     *   [BRAND_BONUS] plus conditionScore and recency, and
     * - a fallback key `(type, minimumConditionScore, null)` whose list contains all
     *   equipment that satisfies the hard constraints regardless of brand, sorted by
     *   conditionScore and recency only (no brand bonus).
     *
     * Slots without a brand preference produce only the no-brand key.
     */
    private fun buildIndex(
        eligible: List<Equipment>,
        normalizedBrand: Map<UUID, String>,
        slots: List<EquipmentPolicyRequirement>,
        recencyScores: Map<UUID, Double>
    ): Map<EquipmentKey, List<Equipment>> {
        val keys = mutableSetOf<EquipmentKey>()
        for (slot in slots) {
            val brand = slot.preferredBrand?.lowercase(Locale.ROOT)
            keys += EquipmentKey(slot.type, slot.minimumConditionScore, brand)
            if (brand != null) {
                keys += EquipmentKey(slot.type, slot.minimumConditionScore, null)
            }
        }

        // Group once by type to avoid scanning the full eligible list per key.
        val byType: Map<EquipmentType, List<Equipment>> = eligible.groupBy { it.type }

        return keys.associateWith { key ->
            (byType[key.type] ?: emptyList())
                .filter { equipment ->
                    (key.minimumConditionScore == null || equipment.conditionScore >= key.minimumConditionScore) &&
                        (key.preferredBrand == null || normalizedBrand[equipment.id] == key.preferredBrand)
                }
                .sortedByDescending { it.score(key.preferredBrand, normalizedBrand, recencyScores) }
        }
    }

    /**
     * Returns the best available (not yet in [usedIds]) candidate for [slot].
     *
     * Tries the full key (including optional brand) first. If the slot carries a brand
     * preference but no matching candidate is free, retries with the no-brand fallback key.
     *
     * [cursors] tracks the current scan position per key. When leading entries of a bucket
     * are already in [usedIds], the cursor is advanced permanently past them so that
     * subsequent calls do not re-scan the same used entries.
     */
    private fun findBestCandidate(
        slot: EquipmentPolicyRequirement,
        index: Map<EquipmentKey, List<Equipment>>,
        cursors: MutableMap<EquipmentKey, Int>,
        usedIds: Set<UUID>
    ): Equipment? {
        val brand = slot.preferredBrand?.lowercase(Locale.ROOT)
        val fullKey = EquipmentKey(slot.type, slot.minimumConditionScore, brand)
        val fromFull = advanceCursor(fullKey, index, cursors, usedIds)
        if (fromFull != null) return fromFull

        if (brand != null) {
            val fallbackKey = EquipmentKey(slot.type, slot.minimumConditionScore, null)
            return advanceCursor(fallbackKey, index, cursors, usedIds)
        }
        return null
    }

    /**
     * Advances the cursor for [key] past any leading used entries and returns the first
     * available candidate, or null if the bucket is exhausted.
     */
    private fun advanceCursor(
        key: EquipmentKey,
        index: Map<EquipmentKey, List<Equipment>>,
        cursors: MutableMap<EquipmentKey, Int>,
        usedIds: Set<UUID>
    ): Equipment? {
        val bucket = index[key] ?: return null
        var cursor = cursors.getOrDefault(key, 0)
        while (cursor < bucket.size && bucket[cursor].id in usedIds) {
            cursor++
        }
        cursors[key] = cursor
        return if (cursor < bucket.size) bucket[cursor] else null
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

    private fun Equipment.score(
        preferredBrand: String?,
        normalizedBrand: Map<UUID, String>,
        recencyScores: Map<UUID, Double>
    ): Double {
        val brandBonus = if (preferredBrand != null && normalizedBrand[id] == preferredBrand) BRAND_BONUS else 0.0
        val recency = recencyScores[id] ?: 0.0
        return brandBonus + conditionScore + recency
    }
}
