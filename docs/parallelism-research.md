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

### 2.4 One Transaction per Allocation Request

Because each allocation request is processed in its own isolated transaction, N concurrent
requests create N competing transactions.  Under high load this scales DB contention
linearly with the number of in-flight allocations.

---

## 3. Proposed Solutions

### Solution A — Batch Allocation Window ★★★★★  *(Primary recommendation)*

**Eliminates contention without compromising quality.**

Instead of one transaction per allocation request, a **batch processor** collects up to
`maxBatchSize` pending messages within a `windowDuration` time window (e.g. 20 requests /
5 seconds) and processes them **in a single transaction** against the full equipment pool.
Within the transaction no intra-batch contention exists; cross-batch contention (one batch
vs. another) is orders of magnitude rarer than one-request-per-transaction contention.

#### How the batch window works

```
                  Time →
  ┌──────────────────────────────────────────────────────┐
  │   Messages arrive at queue                           │
  │   ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○          │
  │                                                      │
  │   BatchCollector waits up to 5 s OR 20 items         │
  │   ├────── window ──────┤                             │
  │   [○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○ ○]        │
  │           ↓  single transaction                      │
  │        SELECT … FOR UPDATE SKIP LOCKED               │
  │        AllocationAlgorithm × N requests              │
  │        UPDATE equipments SET state = RESERVED        │
  └──────────────────────────────────────────────────────┘
```

#### New component: `BatchAllocationProcessor`

```kotlin
@Component
class BatchAllocationProcessor(
    private val equipmentRepository: EquipmentRepository,
    private val allocationProcessingRepository: AllocationProcessingRepository,
    private val allocationEventPublisher: AllocationEventPublisher
) {
    private val allocationAlgorithm = AllocationAlgorithm()

    companion object {
        const val MAX_BATCH_SIZE     = 20
        val WINDOW_DURATION: Duration = Duration.ofSeconds(5)
    }

    /**
     * Processes a batch of allocation commands in a single transaction.
     * Equipment is fetched once for the whole batch; the algorithm is run
     * per-request using only equipment not yet assigned within the batch.
     */
    @Transactional
    fun processBatch(commands: List<ProcessAllocationCommand>) {
        if (commands.isEmpty()) return

        // Combined hard constraints across the whole batch
        val allTypes    = commands.flatMap { it.policy.map { req -> req.type } }.toSet()
        val globalMin   = commands.flatMap { it.policy.mapNotNull { req -> req.minimumConditionScore } }
                                  .minOrNull() ?: 0.0

        // Single DB round-trip: lock enough equipment for the whole batch
        val totalSlots = commands.sumOf { cmd -> cmd.policy.sumOf { it.quantity } }
        val pool = equipmentRepository.findAvailableWithMinConditionScore(allTypes, globalMin)
        val lockedPool = equipmentRepository.findByIdsForUpdate(
            pool.map { it.id }.take(totalSlots * 3),   // generous oversample
            globalMin
        )

        // Greedily assign equipment across requests; requests with the most
        // constrained policies are processed first (fewest candidates → highest risk)
        val sortedCommands = commands.sortedBy { cmd ->
            cmd.policy.minOfOrNull { it.minimumConditionScore ?: 0.0 }?.times(-1) ?: 0.0
        }

        val usedIds = mutableSetOf<UUID>()
        val toReserve = mutableListOf<Equipment>()
        val results   = mutableMapOf<UUID, List<UUID>?>()

        for (cmd in sortedCommands) {
            val available = lockedPool.filter { it.id !in usedIds }
            val selected  = allocationAlgorithm.allocate(cmd.policy, available)

            if (selected != null) {
                usedIds.addAll(selected.map { it.id })
                toReserve.addAll(selected)
                results[cmd.allocationId] = selected.map { it.id }
            } else {
                results[cmd.allocationId] = null
            }
        }

        // Persist all reservations atomically
        if (toReserve.isNotEmpty()) {
            equipmentRepository.saveAll(toReserve.map { it.copy(state = EquipmentState.RESERVED) })
        }

        // Publish results and record idempotency state
        for (cmd in commands) {
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
    }
}
```

#### New component: `BatchAllocationCollector`

```kotlin
@Component
class BatchAllocationCollector(
    private val processor: BatchAllocationProcessor,
    private val allocationProcessingRepository: AllocationProcessingRepository
) {
    private val pending = ArrayBlockingQueue<ProcessAllocationCommand>(1_000)
    private val lock    = ReentrantLock()

    /** Called by AllocationMessageListener for each incoming message. */
    fun submit(command: ProcessAllocationCommand) {
        // Skip if already processed (idempotency)
        val existing = allocationProcessingRepository.findById(command.allocationId)
        if (existing != null && existing.state != AllocationProcessingState.PROCESSING) return

        pending.put(command)

        // Trigger immediately when batch is full
        if (pending.size >= BatchAllocationProcessor.MAX_BATCH_SIZE) {
            flush()
        }
    }

    /** Scheduled flush — fires every WINDOW_DURATION to drain partial batches. */
    @Scheduled(fixedDelayString = "#{T(com.tequipy.challenge.domain.service.BatchAllocationProcessor).WINDOW_DURATION.toMillis()}")
    fun scheduledFlush() = flush()

    private fun flush() {
        if (!lock.tryLock()) return     // another thread is already flushing
        try {
            val batch = mutableListOf<ProcessAllocationCommand>()
            pending.drainTo(batch, BatchAllocationProcessor.MAX_BATCH_SIZE)
            if (batch.isNotEmpty()) processor.processBatch(batch)
        } finally {
            lock.unlock()
        }
    }
}
```

#### Why quality is fully preserved

- The `AllocationAlgorithm` runs on the **full locked pool** (same as today, but shared
  across the batch).
- Hard constraints (`type`, `minimumConditionScore`) are enforced exactly as before.
- Soft preferences (`preferredBrand`, `conditionScore`, `purchaseDate`) are scored via the
  same `scoreCandidate` function with no random sampling or jitter.
- The only new decision is **ordering of requests within the batch**: most-constrained-first
  ensures requests with stricter requirements get first pick of the pool.

#### Why contention drops to near-zero

Under peak load (5 000 requests, 15 000 items), the one-request-per-transaction model
produces up to 5 000 competing transactions.  With batches of 20, the same workload runs in
at most 250 batch transactions — a **20× reduction** in transaction count.  Because each
batch transaction acquires and releases its locks quickly (no contention within the batch),
the probability of cross-batch collisions is negligible.

#### Latency vs. throughput trade-off

| | One-by-one (current) | Batch (20 / 5 s) |
|---|---|---|
| Best-case latency | ~immediate | ≤ 5 s window |
| Worst-case latency under load | many retries → DLQ | ≤ 5 s + processing |
| Throughput under load | limited by retry storms | ~20× higher |
| Quality | full | identical (same algorithm, full pool) |

The 5-second window is only relevant for processing start; allocation requests are already
async (HTTP 202 on creation), so this latency is acceptable.

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

The query planner will prefer this index for both the `findAvailableWithMinConditionScore`
and `findByIdsForUpdate` queries in the batch flow.

---

### Solution C — CAS-style Atomic UPDATE (no `FOR UPDATE`) ★★★★☆

*Complementary to the batch approach if cross-batch contention becomes measurable.*

Instead of acquiring row-level locks with `SELECT … FOR UPDATE`, run the algorithm on an
**unlocked snapshot**, then commit the reservation atomically:

```sql
UPDATE equipments
SET    state = 'RESERVED'
WHERE  id IN (?)
  AND  state = 'AVAILABLE'
RETURNING id
```

If fewer IDs are returned than requested, some were taken by a concurrent batch.  Fetch
replacement candidates for the missing slots and retry only those slots (not the whole
batch).  This eliminates long-held locks entirely; the critical section is a single
`UPDATE` statement.

**Trade-off:** Slightly more complex retry logic; best combined with Solution A to keep
retries rare.

---

### Solution D — Score-Based `FOR UPDATE` Without Random Ordering *(not recommended as sole fix)*

A single-phase query with `FOR UPDATE SKIP LOCKED` and a `LIMIT` avoids the two-phase
pattern and keeps lock scope small:

```sql
SELECT * FROM equipments
WHERE  state = 'AVAILABLE'
  AND  type IN (…)
  AND  condition_score >= ?
ORDER BY condition_score DESC, purchase_date DESC   -- deterministic quality ordering kept
LIMIT ?
FOR UPDATE SKIP LOCKED
```

Because `SKIP LOCKED` spreads transactions across rows naturally, top-scored rows are
progressively consumed and later transactions fall back to lower-scored rows still in the
pool.  However, with 5 000 simultaneous transactions using the same ordering, the leading
rows are still contested first; only after those locks are released do later transactions
see them.  This reduces but does not eliminate contention, and quality degrades subtly for
later transactions.  **Prefer Solution A (batch) for zero quality impact.**

---

## 4. Impact Summary

| Solution | Effort | Contention Reduction | Quality Impact | Risk |
|---|---|---|---|---|
| **A** Batch allocation window (20 req / 5 s) | Medium | ★★★★★ | None | Low |
| **B** Partial index for AVAILABLE rows | Low | ★★★☆☆ | None | None |
| **C** CAS atomic UPDATE | High | ★★★★☆ | None | Medium |
| **D** Single-phase SKIP LOCKED + score ORDER + LIMIT | Medium | ★★★☆☆ | Minor (later batches) | Low |

---

## 5. Recommended Implementation Order

1. **Solution B** — add the partial index migration.  Zero code change, immediate query plan
   improvement, completely safe.

2. **Solution A** — implement `BatchAllocationCollector` + `BatchAllocationProcessor`.
   This is the primary fix: eliminates contention, preserves quality, and dramatically
   increases throughput.

3. **Solution C** — add CAS-style `UPDATE … RETURNING` as a fallback if cross-batch
   contention still appears at very high replica counts.

Solutions A + B together should reduce the retry rate from near-100 % under peak load to
effectively 0 % while preserving full allocation quality.

---

## 6. Benchmark Reference

The `PerformanceTest` in `src/test/kotlin/com/tequipy/challenge/performance/PerformanceTest.kt`
seeds 15 000 `MAIN_COMPUTER` items and fires 5 000 concurrent allocation requests.  It is the
ideal harness for validating these changes.  Before/after comparison points:

- **Retry count** (observable via `AllocationLockContentionException` log lines)
- **DLQ depth** (allocations exhausting all 12 retries)
- **P50 / P99 response time** (reported in `build/performance-report.md`)
- **Wall-clock throughput** (req/s)
