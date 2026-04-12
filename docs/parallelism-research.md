# Parallelism & Contention Research — Allocation Algorithm

## Context

This document analyses why the allocation service suffers from high **lock contention** when
many concurrent requests compete for the same pool of equipment (e.g., thousands of
simultaneous `MAIN_COMPUTER` requests against 15 000 available laptops) and proposes concrete
improvements, ranked by impact and implementation difficulty.

---

## 1. How the Current Flow Works

```
HTTP POST /allocations
  → CreateAllocationService  – persists PENDING allocation
  → publishes AllocationRequestedMessage to allocation.queue

RabbitMQ consumer (AllocationMessageListener)
  → ProcessAllocationService.processAllocation()
      ├─ AllocationProcessingRepository.tryStart()        [idempotency guard]
      └─ InventoryAllocationService.reserveForAllocation()
            │
            ├─ 1. equipmentRepository.findAvailableWithMinConditionScore()
            │       SELECT * FROM equipments
            │       WHERE state = 'AVAILABLE' AND type IN (…) AND condition_score >= ?
            │       -- returns ALL matching rows (potentially thousands)
            │
            ├─ 2. findCandidateIds()
            │       Runs AllocationAlgorithm in-JVM on the full result set
            │       → deterministic top-N candidate IDs
            │
            ├─ 3. equipmentRepository.findByIdsForUpdate(candidateIds)
            │       SELECT * FROM equipments
            │       WHERE id IN (candidate_ids) AND state = 'AVAILABLE'
            │       FOR UPDATE SKIP LOCKED
            │
            ├─ 4. if lockedCandidates.size < candidateIds.size
            │         → throw AllocationLockContentionException
            │           (RabbitMQ retries up to 12 times)
            │
            └─ 5. allocationAlgorithm.allocate(lockedCandidates)
                  equipmentRepository.saveAll(selected as RESERVED)
```

---

## 2. Root Causes of High Contention

### 2.1 Deterministic Top-N Selection (All Transactions Fight for the Same Rows)

`AllocationAlgorithm.scoreCandidate()` is **fully deterministic**:

```kotlin
val brandScore   = if (preferredBrand matches) 10.0 else 0.0
val conditionScore = equipment.conditionScore
return brandScore + conditionScore
```

With 15 000 MAIN_COMPUTER laptops, every single concurrent transaction picks the same
top-scoring items (highest `conditionScore` + brand match).  All transactions then race to
lock that small elite subset; the rest of the pool is never touched.

### 2.2 Two-Phase Read-Then-Lock Pattern

The flow performs **two separate round-trips** to PostgreSQL per allocation:

1. `findAvailableWithMinConditionScore` — reads *all* eligible rows into JVM.
2. `findByIdsForUpdate` — tries to lock exactly the candidates chosen in step 1.

Because `FOR UPDATE SKIP LOCKED` skips rows held by other transactions, step 2 frequently
returns fewer rows than requested.  The strict guard

```kotlin
if (lockedCandidates.size < candidateIds.size) throw AllocationLockContentionException(…)
```

causes an immediate retry even when perfectly valid replacements exist in the pool, wasting
up to 12 retry round-trips per message before it lands in the DLQ.

### 2.3 Expensive Full-Table Scan Under Load

`findAvailableWithMinConditionScore` fetches **every** row matching the criteria.  Under 5 000
concurrent requests, each transaction materialises the same thousands of rows into JVM heap,
creating both DB I/O amplification and GC pressure.

### 2.4 Single RabbitMQ Consumer per Replica

`SimpleRabbitListenerContainerFactory` defaults to `concurrentConsumers = 1`.  With 3 Kubernetes
replicas, only **3 messages** are processed simultaneously across the whole cluster, regardless
of DB throughput capacity.

---

## 3. Proposed Solutions

### Solution A — Single-Phase `SKIP LOCKED` with `LIMIT` and Random Ordering ★★★★★

**Highest impact.  Eliminates the two-phase pattern entirely.**

Replace steps 1–4 in the current flow with a single locked query that:

- acquires only **unlocked** rows (`FOR UPDATE SKIP LOCKED`)
- limits the result to a small oversample (`LIMIT totalSlots × factor`)
- spreads selection across the pool (`ORDER BY random()`)

#### New SQL (add to `EquipmentJdbcRepository`)

```kotlin
fun findAvailableForUpdateSkipLocked(
    types: Set<EquipmentType>,
    minScore: Double,
    limit: Int
): List<EquipmentEntity> {
    if (types.isEmpty()) return emptyList()
    val placeholders = types.joinToString(",") { "?" }
    return jdbcTemplate.query(
        """
        SELECT * FROM equipments
        WHERE  state = ?
          AND  type IN ($placeholders)
          AND  condition_score >= ?
        ORDER BY random()
        LIMIT ?
        FOR UPDATE SKIP LOCKED
        """.trimIndent(),
        rowMapper,
        EquipmentState.AVAILABLE.name,
        *types.map { it.name }.toTypedArray(),
        minScore,
        limit
    )
}
```

#### Updated `InventoryAllocationService.reserveForAllocation`

```kotlin
@Transactional
override fun reserveForAllocation(allocationId: UUID, policy: List<EquipmentPolicyRequirement>): List<UUID>? {
    val globalMinScore  = policy.mapNotNull { it.minimumConditionScore }.minOrNull() ?: 0.0
    val requiredTypes   = policy.map { it.type }.toSet()
    val totalSlots      = policy.sumOf { it.quantity }
    val limit           = totalSlots * OVERSAMPLE_FACTOR          // e.g. 10

    // Single round-trip: lock a random, unlocked subset of eligible rows
    val lockedCandidates = equipmentRepository.findAvailableForUpdateSkipLocked(
        requiredTypes, globalMinScore, limit
    )

    // Algorithm works on whatever we locked — no contention check needed
    val selected = allocationAlgorithm.allocate(policy, lockedCandidates)
        ?: return null      // genuinely no feasible set → FAILED

    equipmentRepository.saveAll(selected.map { it.copy(state = EquipmentState.RESERVED) })
    return selected.map { it.id }
}

companion object { private const val OVERSAMPLE_FACTOR = 10 }
```

**Why this works under high contention:**

- `SKIP LOCKED` means different transactions automatically pick *different* rows.
- `ORDER BY random()` prevents the deterministic clustering of all transactions on the
  same top-N rows.  With 15 000 items and a limit of 20, the probability of two
  transactions colliding on any given row drops from ~100 % to < 0.1 %.
- `LIMIT` keeps each transaction's lock footprint small regardless of pool size.
- No `AllocationLockContentionException` is needed on the normal path; the exception and
  retry mechanism can be kept as a safety net for race conditions in the idempotency layer.

**Trade-off:** The algorithm no longer always picks the globally optimal set; it picks the
best set from the locked sample.  With an oversample factor of 10 the sample is large enough
that near-optimal choices are preserved in the vast majority of cases.

---

### Solution B — Partial Index for `AVAILABLE` Equipment ★★★☆☆

The existing index covers all equipment states:

```sql
CREATE INDEX idx_equipments_state_type_condition_score
    ON equipments(state, type, condition_score);
```

Under high allocation load, this index is constantly maintained for
`AVAILABLE → RESERVED → ASSIGNED` transitions.  A **partial index** on `AVAILABLE` rows only is:

- Smaller (only the subset that is actively queried for new allocations)
- Cache-friendly (fits entirely in `shared_buffers` under typical loads)
- Cheaper to maintain (updated only when equipment enters or leaves `AVAILABLE`, not on
  subsequent `RESERVED → ASSIGNED` transitions)

```sql
-- V6__add_partial_index_available_equipment.sql
CREATE INDEX IF NOT EXISTS idx_equipments_available_type_score
    ON equipments (type, condition_score)
    WHERE state = 'AVAILABLE';
```

The query planner will prefer this index for both the current `findAvailableWithMinConditionScore`
query and the new `findAvailableForUpdateSkipLocked` query.

---

### Solution C — Increase RabbitMQ Consumer Concurrency ★★★☆☆

Adding concurrent consumers per replica is a zero-risk, low-effort throughput multiplier:

```kotlin
// RabbitMQConfig.rabbitListenerContainerFactory
factory.setConcurrentConsumers(5)       // start 5 consumers per replica
factory.setMaxConcurrentConsumers(20)   // auto-scale up to 20 under queue pressure
```

With 3 Kubernetes replicas × 5–20 consumers, the cluster can process **15–60 messages**
simultaneously (up from 3) without any algorithmic changes.  Combine with Solution A to
ensure the increased concurrency does not amplify lock collisions.

---

### Solution D — Random Score Jitter in the Algorithm ★★☆☆☆

A small random offset on the scoring function breaks the deterministic clustering without
materially affecting allocation quality:

```kotlin
private fun scoreCandidate(equipment: Equipment, requirement: EquipmentPolicyRequirement): Double {
    val brandScore     = if (requirement.preferredBrand != null &&
                             equipment.brand.equals(requirement.preferredBrand, ignoreCase = true)) 10.0 else 0.0
    val conditionScore = equipment.conditionScore
    val jitter         = Random.nextDouble(0.0, 0.001)   // negligible quality impact
    return brandScore + conditionScore + jitter
}
```

The brand bonus (10.0) and condition-score spread (0.0–1.0) still dominate; the jitter only
breaks ties within the same score band.  This is a cheap complement to Solution A and
requires no DB or infrastructure changes.

---

### Solution E — CAS-style Atomic UPDATE (Alternative to `FOR UPDATE`) ★★★★☆

Instead of acquiring row-level locks with `SELECT … FOR UPDATE`, run the algorithm on an
**unlocked snapshot**, then commit the reservation atomically:

```sql
UPDATE equipments
SET    state = 'RESERVED'
WHERE  id IN (?)
  AND  state = 'AVAILABLE'
RETURNING id
```

If fewer IDs are returned than requested, some were taken concurrently.  Fetch replacement
candidates for the missing slots and retry **only those slots** (not the whole allocation).
This keeps the critical section to a single `UPDATE` statement (no long-held locks) and is
optimal when allocation computation is expensive relative to DB round-trips.

**Trade-off:** Slightly more complex retry logic per slot; requires the algorithm to operate
in a "fill remaining slots" mode.

---

## 4. Impact Summary

| Solution | Effort | Contention Reduction | Quality Impact | Risk |
|---|---|---|---|---|
| **A** Single-phase SKIP LOCKED + LIMIT + random ORDER | Medium | ★★★★★ | Negligible | Low |
| **B** Partial index for AVAILABLE rows | Low | ★★★☆☆ | None | None |
| **C** Increase RabbitMQ concurrentConsumers | Low | ★★★☆☆ | None | None |
| **D** Random score jitter | Low | ★★☆☆☆ | Negligible | Very low |
| **E** CAS-style atomic UPDATE | High | ★★★★☆ | None | Medium |

---

## 5. Recommended Implementation Order

1. **Solution B** — add the partial index migration.  Zero code change, immediate query plan
   improvement, completely safe.

2. **Solution C** — set `concurrentConsumers = 5`, `maxConcurrentConsumers = 20` in
   `RabbitMQConfig`.  Three-line change, immediate throughput gain.

3. **Solution A** — replace the two-phase read-then-lock with the single-phase
   `findAvailableForUpdateSkipLocked`.  This is the primary fix and eliminates almost all
   contention-driven retries.

4. **Solution D** — add random jitter to `AllocationAlgorithm.scoreCandidate` as a cheap
   additional safeguard.

Solutions A + B + C together should reduce retry rate from near-100 % under peak load to
effectively 0 % while increasing throughput by an order of magnitude.

---

## 6. Benchmark Reference

The `PerformanceTest` in `src/test/kotlin/com/tequipy/challenge/performance/PerformanceTest.kt`
seeds 15 000 `MAIN_COMPUTER` items and fires 5 000 concurrent allocation requests.  It is the
ideal harness for validating these changes.  Before/after comparison points:

- **Retry count** (observable via `AllocationLockContentionException` log lines)
- **DLQ depth** (allocations exhausting all 12 retries)
- **P50 / P99 response time** (reported in `build/performance-report.md`)
- **Wall-clock throughput** (req/s)
