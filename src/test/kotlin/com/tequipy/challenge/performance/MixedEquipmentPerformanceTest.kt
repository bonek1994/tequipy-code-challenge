package com.tequipy.challenge.performance

import com.tequipy.challenge.adapter.api.web.dto.AllocationResponse
import com.tequipy.challenge.adapter.api.web.dto.CreateAllocationRequest
import com.tequipy.challenge.adapter.api.web.dto.EquipmentPolicyRequirementRequest
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.service.AllocationAlgorithmMetrics
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.BatchPreparedStatementSetter
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.io.File
import java.sql.Date
import java.sql.PreparedStatement
import java.time.LocalDate
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Mixed-equipment performance test.
 *
 * Simulates 5 000 concurrent "onboard a new employee" allocation requests where:
 *  - Each request covers a random subset of the four equipment types
 *    (MAIN_COMPUTER, MONITOR, KEYBOARD, MOUSE).
 *  - The number of requirements per request is uniformly random in [1, 4].
 *  - Each requirement optionally carries a `minimumConditionScore` and a `preferredBrand`.
 *
 * Dataset sizing strategy:
 *  - Request policies are pre-generated with a fixed seed (deterministic).
 *  - The actual demand per type is computed from the generated policies.
 *  - Each type is stocked at [OVERSUPPLY_FACTOR]× its actual demand, ensuring
 *    the vast majority of allocations can be satisfied.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("performance")
class MixedEquipmentPerformanceTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @BeforeEach
    fun resetMetrics() {
        AllocationAlgorithmMetrics.reset()
    }

    companion object {
        private val log = LoggerFactory.getLogger(MixedEquipmentPerformanceTest::class.java)

        private const val ALLOCATION_REQUESTS = 5_000
        private const val THREAD_POOL_SIZE = 10
        private const val PROCESSING_TIMEOUT_MS = 300_000L
        private const val POLL_INTERVAL_MS = 500L

        /** Each equipment type is seeded at demand × this factor to absorb condition-score filtering. */
        private const val OVERSUPPLY_FACTOR = 2

        private val ALL_TYPES: List<EquipmentType> = EquipmentType.entries

        private val BRANDS_BY_TYPE: Map<EquipmentType, List<String>> = mapOf(
            EquipmentType.MAIN_COMPUTER to listOf("Apple", "Dell"),
            EquipmentType.MONITOR       to listOf("Dell", "LG", "Samsung"),
            EquipmentType.KEYBOARD      to listOf("Apple", "Logitech"),
            EquipmentType.MOUSE         to listOf("Apple", "Logitech")
        )

        private val MODELS_BY_TYPE: Map<EquipmentType, List<String>> = mapOf(
            EquipmentType.MAIN_COMPUTER to listOf(
                "MacBook Pro 14", "MacBook Pro 16", "MacBook Air M2", "MacBook Air M3",
                "XPS 15", "XPS 13", "Latitude 5540", "Latitude 7440"
            ),
            EquipmentType.MONITOR to listOf(
                "U2722D", "U3223QE", "P2422H", "S2721QS",
                "27UK850-W", "32UN880-B", "27GP950-B",
                "C27F390", "C32G55T"
            ),
            EquipmentType.KEYBOARD to listOf(
                "Magic Keyboard", "MX Keys", "K350", "K375s", "KB216"
            ),
            EquipmentType.MOUSE to listOf(
                "Magic Mouse", "MX Master 3", "M705", "M331", "MS3320W"
            )
        )

        /** Probability distribution for minimumConditionScore (null = no constraint). */
        private val MIN_SCORE_OPTIONS: List<Double?> = listOf(null, 0.6, 0.7, 0.8)

        private val PURCHASE_DATES: List<LocalDate> = (2022..2025).flatMap { y ->
            (1..12).map { m -> LocalDate.of(y, m, 1) }
        }

        @Container
        val postgres: PostgreSQLContainer<*> = PostgreSQLContainer("postgres:15-alpine")
            .withDatabaseName("tequipy_test")
            .withUsername("tequipy")
            .withPassword("tequipy")

        @Container
        val rabbitmq: RabbitMQContainer = RabbitMQContainer("rabbitmq:3.12-management-alpine")

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
            registry.add("spring.rabbitmq.host", rabbitmq::getHost)
            registry.add("spring.rabbitmq.port", rabbitmq::getAmqpPort)
            registry.add("spring.rabbitmq.username", rabbitmq::getAdminUsername)
            registry.add("spring.rabbitmq.password", rabbitmq::getAdminPassword)
        }
    }

    @Test
    fun `5000 burst mixed-equipment allocation requests with random policy sizes`() {

        // ---- Step 1: Pre-generate all request policies (deterministic seed = reproducible run) ----
        val requestRandom = Random(123)
        val policies: List<List<EquipmentPolicyRequirementRequest>> =
            (1..ALLOCATION_REQUESTS).map { buildRandomPolicy(requestRandom) }

        // ---- Step 2: Compute actual demand per type, seed inventory at 2× that amount ----
        val demandPerType: Map<EquipmentType, Int> = ALL_TYPES.associateWith { type ->
            policies.sumOf { policy -> policy.count { req -> req.type == type } }
        }

        val equipmentRows = buildEquipmentRows(random = Random(42), demandPerType = demandPerType)

        log.info("Dataset per type (demand → seeded):")
        ALL_TYPES.forEach { type ->
            val demand = demandPerType[type] ?: 0
            log.info("  {}: {} → {} items", type, demand, demand * OVERSUPPLY_FACTOR)
        }
        log.info("Total equipment to seed: {}", equipmentRows.size)

        val seedMs = measureTimeMillis { seedEquipment(equipmentRows) }
        log.info("Seeded {} items in {} ms", equipmentRows.size, seedMs)

        // ---- Phase 1: HTTP submission ----
        val executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
        val httpSuccessCount = AtomicInteger(0)
        val httpFailureCount = AtomicInteger(0)
        val responseTimes = CopyOnWriteArrayList<Long>()

        log.info("Phase 1: submitting {} concurrent allocation requests", ALLOCATION_REQUESTS)
        val httpDurationMs = measureTimeMillis {
            val futures = policies.map { policy ->
                CompletableFuture.runAsync({
                    val start = System.currentTimeMillis()
                    try {
                        val response = restTemplate.postForEntity(
                            allocationUrl(),
                            CreateAllocationRequest(policy = policy),
                            AllocationResponse::class.java
                        )
                        responseTimes.add(System.currentTimeMillis() - start)
                        if (response.statusCode == HttpStatus.ACCEPTED) {
                            httpSuccessCount.incrementAndGet()
                        } else {
                            httpFailureCount.incrementAndGet()
                        }
                    } catch (_: Exception) {
                        responseTimes.add(System.currentTimeMillis() - start)
                        httpFailureCount.incrementAndGet()
                    }
                }, executor)
            }
            CompletableFuture.allOf(*futures.toTypedArray()).join()
        }
        executor.shutdown()

        val httpSorted = responseTimes.sorted()
        val httpAvg        = if (httpSorted.isNotEmpty()) httpSorted.average() else 0.0
        val httpP50        = if (httpSorted.isNotEmpty()) httpSorted[(httpSorted.size * 0.50).toInt().coerceAtMost(httpSorted.size - 1)] else 0L
        val httpP99        = if (httpSorted.isNotEmpty()) httpSorted[(httpSorted.size * 0.99).toInt().coerceAtMost(httpSorted.size - 1)] else 0L
        val httpThroughput = responseTimes.size * 1_000.0 / httpDurationMs

        log.info("Phase 1 complete: {} accepted, {} failed in {} ms",
            httpSuccessCount.get(), httpFailureCount.get(), httpDurationMs)

        // ---- Phase 2: Wait for async processing ----
        log.info("Phase 2: waiting for processing (timeout {}s)", PROCESSING_TIMEOUT_MS / 1000)
        var finalCounts = queryStateCounts()
        val processingDurationMs = measureTimeMillis {
            val deadline = System.currentTimeMillis() + PROCESSING_TIMEOUT_MS
            var pollCount = 0
            var lastLoggedPending = finalCounts.pending
            while (finalCounts.pending > 0 && System.currentTimeMillis() < deadline) {
                Thread.sleep(POLL_INTERVAL_MS)
                finalCounts = queryStateCounts()
                pollCount++
                if (pollCount == 1 || pollCount % 5 == 0 || finalCounts.pending != lastLoggedPending) {
                    log.info("Processing progress: pending={}, allocated={}, failed={}, settled={}",
                        finalCounts.pending, finalCounts.allocated, finalCounts.failed, finalCounts.settled)
                    lastLoggedPending = finalCounts.pending
                }
            }
        }
        log.info("Phase 2 complete in {} ms", processingDurationMs)
        log.info("Final states: allocated={}, failed={}, pending={}",
            finalCounts.allocated, finalCounts.failed, finalCounts.pending)

        // ---- Phase 3: Queue latency metrics ----
        val queueTimes           = queryQueueWaitTimesMs()
        val queueAvg             = if (queueTimes.isNotEmpty()) queueTimes.average() else 0.0
        val queueP50             = if (queueTimes.isNotEmpty()) queueTimes[(queueTimes.size * 0.50).toInt().coerceAtMost(queueTimes.size - 1)] else 0L
        val queueP99             = if (queueTimes.isNotEmpty()) queueTimes[(queueTimes.size * 0.99).toInt().coerceAtMost(queueTimes.size - 1)] else 0L
        val queueMax             = queueTimes.maxOrNull() ?: 0L
        val processingThroughput = if (processingDurationMs > 0) finalCounts.settled * 1_000.0 / processingDurationMs else 0.0
        val endToEndMs           = httpDurationMs + processingDurationMs

        // ---- Report ----
        val algorithmChronological = AllocationAlgorithmMetrics.snapshotNanos()
        val algorithmDurations = algorithmChronological.sorted()
        val algorithmStats = buildAlgorithmSpeedStats(algorithmDurations)

        val report = buildString {
            appendLine("## 🚀 Mixed Equipment Performance Test Report")
            appendLine()
            appendLine("### Dataset (demand computed from deterministic seed, stocked at ${OVERSUPPLY_FACTOR}×)")
            appendLine("| Type | Demand | Seeded |")
            appendLine("|------|-------:|------:|")
            ALL_TYPES.forEach { type ->
                val d = demandPerType[type] ?: 0
                appendLine("| $type | $d | ${d * OVERSUPPLY_FACTOR} |")
            }
            appendLine("| **Total** | | **${equipmentRows.size}** |")
            appendLine()
            appendLine("### Request Policy Distribution")
            appendLine("| Requirements per request | Requests |")
            appendLine("|-------------------------:|---------:|")
            policies.groupBy { it.size }.mapValues { it.value.size }.toSortedMap()
                .forEach { (size, count) -> appendLine("| $size | $count |") }
            appendLine()
            appendLine("### Equipment Type Demand Across All Requests")
            appendLine("| Type | Requirements |")
            appendLine("|------|-------------:|")
            ALL_TYPES.associateWith { type -> policies.sumOf { p -> p.count { it.type == type } } }
                .forEach { (type, count) -> appendLine("| $type | $count |") }
            appendLine()
            appendLine("### Phase 1 — HTTP Submission")
            appendLine("| Metric | Value |")
            appendLine("|--------|------:|")
            appendLine("| HTTP 202 (accepted) | ${httpSuccessCount.get()} |")
            appendLine("| HTTP errors | ${httpFailureCount.get()} |")
            appendLine("| Wall-clock time | ${httpDurationMs} ms |")
            appendLine("| Throughput | ${String.format(Locale.ROOT, "%.1f", httpThroughput)} req/s |")
            appendLine("| Avg response time | ${String.format(Locale.ROOT, "%.1f", httpAvg)} ms |")
            appendLine("| P50 response time | $httpP50 ms |")
            appendLine("| P99 response time | $httpP99 ms |")
            appendLine()
            appendLine("### Phase 2 — Async Processing (RabbitMQ → Allocation)")
            appendLine("| Metric | Value |")
            appendLine("|--------|------:|")
            appendLine("| ALLOCATED | ${finalCounts.allocated} |")
            appendLine("| FAILED | ${finalCounts.failed} |")
            appendLine("| Still PENDING | ${finalCounts.pending} |")
            appendLine("| Processing wall-clock | ${processingDurationMs} ms |")
            appendLine("| Processing throughput | ${String.format(Locale.ROOT, "%.1f", processingThroughput)} alloc/s |")
            appendLine()
            appendLine("### Queue Wait Time (HTTP POST → processing start)")
            appendLine("| Metric | Value |")
            appendLine("|--------|------:|")
            appendLine("| Samples | ${queueTimes.size} |")
            appendLine("| Avg | ${String.format(Locale.ROOT, "%.1f", queueAvg)} ms |")
            appendLine("| P50 | $queueP50 ms |")
            appendLine("| P99 | $queueP99 ms |")
            appendLine("| Max | $queueMax ms |")
            appendLine()
            appendLine("### Wall-Clock Totals")
            appendLine("| Metric | Value |")
            appendLine("|--------|------:|")
            appendLine("| Total time (HTTP + processing) | ${endToEndMs} ms |")
            appendLine()
            append(renderAllocationAlgorithmSpeedSection(
                algorithmStats, "|--------|------:|",
                chronologicalNanos = algorithmChronological
            ))
        }

        log.info("Performance test complete.\n{}", report)
        File("build").mkdirs()
        File("build/mixed-equipment-performance-report.md").writeText(report)
        log.info("Report written to build/mixed-equipment-performance-report.md")

        // ---- Assertions ----
        assertEquals(
            ALLOCATION_REQUESTS,
            httpSuccessCount.get(),
            "All $ALLOCATION_REQUESTS submissions must return HTTP 202. " +
                "Got ${httpSuccessCount.get()} successes and ${httpFailureCount.get()} failures."
        )
        assertTrue(
            finalCounts.pending == 0,
            "All allocations should be processed within ${PROCESSING_TIMEOUT_MS / 1000}s. " +
                "${finalCounts.pending} still PENDING out of ${finalCounts.total}."
        )
        assertEquals(
            finalCounts.settled,
            algorithmStats.samples,
            "Algorithm metrics should contain one sample per processed allocation."
        )
    }

    // ---- Private helpers ----

    private fun allocationUrl() = "http://localhost:$port/allocations"


    /**
     * Generates a single random allocation policy with 1–4 requirements.
     * Each requirement draws a uniformly random `EquipmentType`, optional
     * `minimumConditionScore`, and optional `preferredBrand`.
     */
    private fun buildRandomPolicy(random: Random): List<EquipmentPolicyRequirementRequest> {
        val requirementCount = random.nextInt(1, 5) // 1, 2, 3, or 4
        return (1..requirementCount).map {
            val type   = ALL_TYPES[random.nextInt(ALL_TYPES.size)]
            val brands = BRANDS_BY_TYPE.getValue(type)
            EquipmentPolicyRequirementRequest(
                type                  = type,
                quantity              = 1,
                minimumConditionScore = MIN_SCORE_OPTIONS[random.nextInt(MIN_SCORE_OPTIONS.size)],
                preferredBrand        = if (random.nextBoolean()) brands[random.nextInt(brands.size)] else null
            )
        }
    }

    private data class EquipmentRow(
        val id: UUID,
        val type: EquipmentType,
        val brand: String,
        val model: String,
        val conditionScore: Double,
        val purchaseDate: LocalDate
    )

    /**
     * Builds equipment rows: [OVERSUPPLY_FACTOR]× the actual demand per type.
     * Condition scores are drawn from [0.6, 1.0) so that requests with any
     * [MIN_SCORE_OPTIONS] value have eligible candidates in the inventory.
     */
    private fun buildEquipmentRows(
        random: Random,
        demandPerType: Map<EquipmentType, Int>
    ): List<EquipmentRow> = buildList {
        for (type in ALL_TYPES) {
            val count  = (demandPerType[type] ?: 0) * OVERSUPPLY_FACTOR
            val brands = BRANDS_BY_TYPE.getValue(type)
            val models = MODELS_BY_TYPE.getValue(type)
            repeat(count) {
                add(
                    EquipmentRow(
                        id             = UUID.randomUUID(),
                        type           = type,
                        brand          = brands[random.nextInt(brands.size)],
                        model          = models[random.nextInt(models.size)],
                        conditionScore = 0.6 + random.nextDouble() * 0.4, // [0.6, 1.0)
                        purchaseDate   = PURCHASE_DATES[random.nextInt(PURCHASE_DATES.size)]
                    )
                )
            }
        }
    }

    private fun seedEquipment(rows: List<EquipmentRow>) {
        val sql = """
            INSERT INTO equipments (id, type, brand, model, state, condition_score, purchase_date)
            VALUES (?, ?, ?, ?, ?, ?, ?)
        """.trimIndent()
        rows.chunked(500).forEach { batch ->
            jdbcTemplate.batchUpdate(sql, object : BatchPreparedStatementSetter {
                override fun setValues(ps: PreparedStatement, i: Int) {
                    val row = batch[i]
                    ps.setObject(1, row.id)
                    ps.setString(2, row.type.name)
                    ps.setString(3, row.brand)
                    ps.setString(4, row.model)
                    ps.setString(5, "AVAILABLE")
                    ps.setDouble(6, row.conditionScore)
                    ps.setDate(7, Date.valueOf(row.purchaseDate))
                }
                override fun getBatchSize() = batch.size
            })
        }
    }

    private data class AllocationStateCounts(
        val pending: Int = 0,
        val allocated: Int = 0,
        val failed: Int = 0,
        val other: Int = 0
    ) {
        val settled: Int get() = allocated + failed + other
        val total:   Int get() = pending + settled
    }

    private fun queryStateCounts(): AllocationStateCounts {
        val rows = jdbcTemplate.query(
            "SELECT state, COUNT(*)::int AS cnt FROM allocations GROUP BY state"
        ) { rs, _ -> rs.getString("state") to rs.getInt("cnt") }

        var pending = 0; var allocated = 0; var failed = 0; var other = 0
        for ((state, cnt) in rows) {
            when (state) {
                "PENDING"   -> pending   = cnt
                "ALLOCATED" -> allocated = cnt
                "FAILED"    -> failed    = cnt
                else        -> other    += cnt
            }
        }
        return AllocationStateCounts(pending, allocated, failed, other)
    }

    private fun queryQueueWaitTimesMs(): List<Long> {
        return jdbcTemplate.query(
            """
            SELECT EXTRACT(EPOCH FROM (apr.created_at - a.created_at)) * 1000 AS queue_ms
            FROM allocations a
            JOIN allocation_processing_results apr ON apr.allocation_id = a.id
            ORDER BY queue_ms
            """.trimIndent()
        ) { rs, _ -> rs.getLong("queue_ms") }
    }
}



