package com.tequipy.challenge.performance

import com.tequipy.challenge.adapter.api.web.dto.AllocationResponse
import com.tequipy.challenge.adapter.api.web.dto.CreateAllocationRequest
import com.tequipy.challenge.adapter.api.web.dto.EquipmentPolicyRequirementRequest
import com.tequipy.challenge.domain.model.EquipmentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
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
import org.slf4j.LoggerFactory
import java.io.File
import java.sql.Date
import java.sql.PreparedStatement
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.random.Random
import kotlin.system.measureTimeMillis

/**
 * Performance test suite for the Tequipy allocation service.
 *
 * Measures the **full business journey**:
 *  1. **HTTP Phase** – submits concurrent allocation requests and measures HTTP response times.
 *  2. **Processing Phase** – waits for all allocations to leave PENDING state (async via RabbitMQ)
 *     and measures per-allocation processing duration using database timestamps.
 *
 * Data setup:
 *  - Seeds 300 MAIN_COMPUTER laptops (150 Apple + 150 Dell)
 *  - Each with random condition score [0.60, 1.0] and purchase date 2022–2025
 *  - Submits 1000 concurrent allocations, each requesting 1 Apple + 1 Dell laptop
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@Tag("performance")
class PerformanceTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    companion object {
        private val log = LoggerFactory.getLogger(PerformanceTest::class.java)
        private const val APPLE_COUNT = 5000
        private const val DELL_COUNT = 5000
        private const val ALLOCATION_REQUESTS = 5000
        private const val THREAD_POOL_SIZE = 10
        private const val MIN_CONDITION = 0.7
        private const val PROCESSING_TIMEOUT_MS = 240_000L
        private const val POLL_INTERVAL_MS = 500L
        private val RANDOM_SEED = Random(42)

        private val APPLE_MODELS = listOf(
            "MacBook Pro 14",
            "MacBook Pro 16",
            "MacBook Air M2",
            "MacBook Air M3"
        )
        private val DELL_MODELS = listOf(
            "XPS 15",
            "XPS 13",
            "Latitude 5540",
            "Latitude 7440",
            "Precision 5570"
        )
        private val PURCHASE_DATES: List<LocalDate> = (2022..2025).flatMap { year ->
            (1..12).map { month -> LocalDate.of(year, month, 1) }
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

    private fun allocationUrl() = "http://localhost:$port/allocations"

    // -------------------------------------------------------------------------
    // Data seeding
    // -------------------------------------------------------------------------

    private data class EquipmentRow(
        val id: UUID,
        val brand: String,
        val model: String,
        val conditionScore: Double,
        val purchaseDate: LocalDate
    )

    private fun buildEquipmentRows(): List<EquipmentRow> = buildList {
        repeat(APPLE_COUNT) {
            add(
                EquipmentRow(
                    id = UUID.randomUUID(),
                    brand = "Apple",
                    model = APPLE_MODELS[RANDOM_SEED.nextInt(APPLE_MODELS.size)],
                    conditionScore = 0.7 + RANDOM_SEED.nextDouble() * 0.4,
                    purchaseDate = PURCHASE_DATES[RANDOM_SEED.nextInt(PURCHASE_DATES.size)]
                )
            )
        }
        repeat(DELL_COUNT) {
            add(
                EquipmentRow(
                    id = UUID.randomUUID(),
                    brand = "Dell",
                    model = DELL_MODELS[RANDOM_SEED.nextInt(DELL_MODELS.size)],
                    conditionScore = 0.7 + RANDOM_SEED.nextDouble() * 0.4,
                    purchaseDate = PURCHASE_DATES[RANDOM_SEED.nextInt(PURCHASE_DATES.size)]
                )
            )
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
                    ps.setString(2, "MAIN_COMPUTER")
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

    // -------------------------------------------------------------------------
    // Polling helpers
    // -------------------------------------------------------------------------

    private data class AllocationStateCounts(
        val pending: Int = 0,
        val allocated: Int = 0,
        val failed: Int = 0,
        val other: Int = 0
    ) {
        val settled: Int get() = allocated + failed + other
        val total: Int get() = pending + settled
    }

    private fun queryStateCounts(): AllocationStateCounts {
        val rows = jdbcTemplate.query(
            "SELECT state, COUNT(*)::int AS cnt FROM allocations GROUP BY state"
        ) { rs, _ -> rs.getString("state") to rs.getInt("cnt") }

        var pending = 0; var allocated = 0; var failed = 0; var other = 0
        for ((state, cnt) in rows) {
            when (state) {
                "PENDING" -> pending = cnt
                "ALLOCATED" -> allocated = cnt
                "FAILED" -> failed = cnt
                else -> other += cnt
            }
        }
        return AllocationStateCounts(pending, allocated, failed, other)
    }

    private fun queryProcessingTimesMs(): List<Long> {
        return jdbcTemplate.query(
            """
            SELECT EXTRACT(EPOCH FROM (apr.updated_at - apr.created_at)) * 1000 AS processing_ms
            FROM allocation_processing_results apr
            WHERE apr.state IN ('ALLOCATED', 'FAILED')
            ORDER BY processing_ms
            """.trimIndent()
        ) { rs, _ -> rs.getLong("processing_ms") }
    }

    private fun queryEndToEndTimesMs(): List<Long> {
        return jdbcTemplate.query(
            """
            SELECT EXTRACT(EPOCH FROM (a.updated_at - a.created_at)) * 1000 AS e2e_ms
            FROM allocations a
            WHERE a.state IN ('ALLOCATED', 'FAILED')
            ORDER BY e2e_ms
            """.trimIndent()
        ) { rs, _ -> rs.getLong("e2e_ms") }
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

    private fun dumpDatabaseTables(): String {
        val tables = jdbcTemplate.query(
            """
            SELECT table_name
            FROM information_schema.tables
            WHERE table_schema = 'public' AND table_type = 'BASE TABLE'
            ORDER BY table_name
            """.trimIndent()
        ) { rs, _ -> rs.getString("table_name") }

        return buildString {
            appendLine("# Database dump")
            appendLine()

            tables.forEach { tableName ->
                val columns = jdbcTemplate.query(
                    """
                    SELECT column_name
                    FROM information_schema.columns
                    WHERE table_schema = 'public' AND table_name = ?
                    ORDER BY ordinal_position
                    """.trimIndent(),
                    { rs, _ -> rs.getString("column_name") },
                    tableName
                )

                val quotedTableName = quoteIdentifier(tableName)
                val rows = jdbcTemplate.queryForList(
                    if (columns.contains("id")) {
                        "SELECT * FROM $quotedTableName ORDER BY ${quoteIdentifier("id")}"
                    } else {
                        "SELECT * FROM $quotedTableName"
                    }
                )

                appendLine("## $tableName")
                appendLine()
                appendLine("Rows: ${rows.size}")

                if (columns.isEmpty()) {
                    appendLine("(no columns)")
                    appendLine()
                    return@forEach
                }

                appendLine()
                appendLine("| ${columns.joinToString(" | ")} |")
                appendLine("|${columns.joinToString("|") { "---" }}|")

                rows.forEach { row ->
                    appendLine(
                        "| ${columns.joinToString(" | ") { column -> formatDumpValue(row[column]) }} |"
                    )
                }
                appendLine()
            }
        }
    }

    private fun quoteIdentifier(identifier: String): String {
        return "\"${identifier.replace("\"", "\"\"")}\""
    }

    private fun formatDumpValue(value: Any?): String {
        return when (value) {
            null -> ""
            is ByteArray -> value.joinToString(prefix = "[", postfix = "]") { byte -> byte.toString() }
            is Collection<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> formatDumpValue(item) }
            is Array<*> -> value.joinToString(prefix = "[", postfix = "]") { item -> formatDumpValue(item) }
            else -> value.toString()
                .replace("\r\n", "\\n")
                .replace("\n", "\\n")
                .replace("|", "\\|")
        }
    }

    // -------------------------------------------------------------------------
    // Performance test
    // -------------------------------------------------------------------------

    @Test
    fun `1000 concurrent allocation requests for Apple and Dell laptops with minimum condition 0_7`() {
        val rows = buildEquipmentRows()
        val appleEligible = rows.count { it.brand == "Apple" && it.conditionScore >= MIN_CONDITION }
        val dellEligible = rows.count { it.brand == "Dell" && it.conditionScore >= MIN_CONDITION }

        log.info("Seeding {} equipment items", rows.size)
        val seedMs = measureTimeMillis { seedEquipment(rows) }
        log.info("Seeded {} items in {} ms", rows.size, seedMs)
        log.info("Apple laptops eligible (condition >= {}): {} / {}", MIN_CONDITION, appleEligible, APPLE_COUNT)
        log.info("Dell laptops eligible (condition >= {}): {} / {}", MIN_CONDITION, dellEligible, DELL_COUNT)

        // ---- Phase 1: HTTP submission ----
        val executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
        val httpSuccessCount = AtomicInteger(0)
        val httpFailureCount = AtomicInteger(0)
        val responseTimes = CopyOnWriteArrayList<Long>()
        val allocationIds = CopyOnWriteArrayList<UUID>()

        log.info("Phase 1: submitting {} concurrent allocation requests", ALLOCATION_REQUESTS)
        val httpDurationMs = measureTimeMillis {
            val futures = (1..ALLOCATION_REQUESTS).map {
                CompletableFuture.runAsync({
                    val start = System.currentTimeMillis()
                    try {
                        val response = restTemplate.postForEntity(
                            allocationUrl(),
                            CreateAllocationRequest(
                                policy = listOf(
                                    EquipmentPolicyRequirementRequest(
                                        type = EquipmentType.MAIN_COMPUTER,
                                        quantity = 1,
                                        minimumConditionScore = MIN_CONDITION,
                                        preferredBrand = "Apple"
                                    ),
                                    EquipmentPolicyRequirementRequest(
                                        type = EquipmentType.MAIN_COMPUTER,
                                        quantity = 1,
                                        minimumConditionScore = MIN_CONDITION,
                                        preferredBrand = "Dell"
                                    )
                                )
                            ),
                            AllocationResponse::class.java
                        )
                        responseTimes.add(System.currentTimeMillis() - start)
                        if (response.statusCode == HttpStatus.ACCEPTED) {
                            httpSuccessCount.incrementAndGet()
                            response.body?.id?.let { allocationIds.add(it) }
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
        val httpAvg = if (httpSorted.isNotEmpty()) httpSorted.average() else 0.0
        val httpP50 = if (httpSorted.isNotEmpty()) httpSorted[(httpSorted.size * 0.50).toInt().coerceAtMost(httpSorted.size - 1)] else 0L
        val httpP99 = if (httpSorted.isNotEmpty()) httpSorted[(httpSorted.size * 0.99).toInt().coerceAtMost(httpSorted.size - 1)] else 0L
        val httpThroughput = responseTimes.size * 1_000.0 / httpDurationMs

        log.info(
            "Phase 1 complete: {} accepted, {} failed in {} ms",
            httpSuccessCount.get(),
            httpFailureCount.get(),
            httpDurationMs
        )

        // ---- Phase 2: Wait for async processing to complete ----
        log.info("Phase 2: waiting for allocation processing (timeout {}s)", PROCESSING_TIMEOUT_MS / 1000)
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
                    log.info(
                        "Processing progress: pending={}, allocated={}, failed={}, settled={}",
                        finalCounts.pending,
                        finalCounts.allocated,
                        finalCounts.failed,
                        finalCounts.settled
                    )
                    lastLoggedPending = finalCounts.pending
                }
            }
        }

        log.info("Phase 2 complete in {} ms", processingDurationMs)
        log.info(
            "Final allocation states: allocated={}, failed={}, pending={}",
            finalCounts.allocated,
            finalCounts.failed,
            finalCounts.pending
        )

        // ---- Phase 3: Per-allocation timing metrics ----
        val procTimes = queryProcessingTimesMs()
        val procAvg = if (procTimes.isNotEmpty()) procTimes.average() else 0.0
        val procP50 = if (procTimes.isNotEmpty()) procTimes[(procTimes.size * 0.50).toInt().coerceAtMost(procTimes.size - 1)] else 0L
        val procP99 = if (procTimes.isNotEmpty()) procTimes[(procTimes.size * 0.99).toInt().coerceAtMost(procTimes.size - 1)] else 0L
        val procMax = procTimes.maxOrNull() ?: 0L

        val queueTimes = queryQueueWaitTimesMs()
        val queueAvg = if (queueTimes.isNotEmpty()) queueTimes.average() else 0.0
        val queueP50 = if (queueTimes.isNotEmpty()) queueTimes[(queueTimes.size * 0.50).toInt().coerceAtMost(queueTimes.size - 1)] else 0L
        val queueP99 = if (queueTimes.isNotEmpty()) queueTimes[(queueTimes.size * 0.99).toInt().coerceAtMost(queueTimes.size - 1)] else 0L
        val queueMax = queueTimes.maxOrNull() ?: 0L

        val e2eTimes = queryEndToEndTimesMs()
        val e2eAvg = if (e2eTimes.isNotEmpty()) e2eTimes.average() else 0.0
        val e2eP50 = if (e2eTimes.isNotEmpty()) e2eTimes[(e2eTimes.size * 0.50).toInt().coerceAtMost(e2eTimes.size - 1)] else 0L
        val e2eP99 = if (e2eTimes.isNotEmpty()) e2eTimes[(e2eTimes.size * 0.99).toInt().coerceAtMost(e2eTimes.size - 1)] else 0L
        val e2eMax = e2eTimes.maxOrNull() ?: 0L

        val processingThroughput = if (processingDurationMs > 0) finalCounts.settled * 1_000.0 / processingDurationMs else 0.0

        val endToEndMs = httpDurationMs + processingDurationMs

        // ---- Report ----
        val report = buildString {
            appendLine("## 🚀 Performance Test Report")
            appendLine()
            appendLine("### Setup")
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Equipment seeded | ${rows.size} (${APPLE_COUNT} Apple + ${DELL_COUNT} Dell) |")
            appendLine("| Apple eligible (score ≥ $MIN_CONDITION) | $appleEligible |")
            appendLine("| Dell eligible (score ≥ $MIN_CONDITION) | $dellEligible |")
            appendLine("| Total allocation requests | $ALLOCATION_REQUESTS |")
            appendLine("| Thread pool size | $THREAD_POOL_SIZE |")
            appendLine()
            appendLine("### Phase 1 — HTTP Submission")
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| HTTP 202 (accepted) | ${httpSuccessCount.get()} |")
            appendLine("| HTTP errors | ${httpFailureCount.get()} |")
            appendLine("| Wall-clock time | ${httpDurationMs} ms |")
            appendLine("| Throughput | ${"%.1f".format(httpThroughput)} req/s |")
            appendLine("| Avg response time | ${"%.1f".format(httpAvg)} ms |")
            appendLine("| P50 response time | $httpP50 ms |")
            appendLine("| P99 response time | $httpP99 ms |")
            appendLine()
            appendLine("### Phase 2 — Async Processing (RabbitMQ → Allocation)")
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| ALLOCATED | ${finalCounts.allocated} |")
            appendLine("| FAILED | ${finalCounts.failed} |")
            appendLine("| Still PENDING | ${finalCounts.pending} |")
            appendLine("| Processing wall-clock | ${processingDurationMs} ms |")
            appendLine("| Processing throughput | ${"%.1f".format(processingThroughput)} alloc/s |")
            appendLine()
            appendLine("### Per-Allocation Processing Time (actual work: tryStart → complete)")
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Samples | ${procTimes.size} |")
            appendLine("| Avg | ${"%.1f".format(procAvg)} ms |")
            appendLine("| P50 | $procP50 ms |")
            appendLine("| P99 | $procP99 ms |")
            appendLine("| Max | $procMax ms |")
            appendLine()
            appendLine("### Queue Wait Time (HTTP POST → processing start)")
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Samples | ${queueTimes.size} |")
            appendLine("| Avg | ${"%.1f".format(queueAvg)} ms |")
            appendLine("| P50 | $queueP50 ms |")
            appendLine("| P99 | $queueP99 ms |")
            appendLine("| Max | $queueMax ms |")
            appendLine()
            appendLine("### End-to-End Latency (HTTP POST → final state)")
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Samples | ${e2eTimes.size} |")
            appendLine("| Avg | ${"%.1f".format(e2eAvg)} ms |")
            appendLine("| P50 | $e2eP50 ms |")
            appendLine("| P99 | $e2eP99 ms |")
            appendLine("| Max | $e2eMax ms |")
            appendLine()
            appendLine("### Wall-Clock Totals")
            appendLine("| Metric | Value |")
            appendLine("|--------|-------|")
            appendLine("| Total time (HTTP + processing) | ${endToEndMs} ms |")
        }

        log.info("Performance test completed. Writing report to build/performance-report.md")
        log.info("\n{}", report)

        File("build").mkdirs()
        File("build/performance-report.md").writeText(report)
        log.info("Report written to {}", File("build/performance-report.md").absolutePath)

        val dumpFile = File("build/performance-db-dump.md")
        dumpFile.writeText(dumpDatabaseTables())
        log.info("Database dump written to {}", dumpFile.absolutePath)

        // ---- Assertions ----
        assertEquals(
            ALLOCATION_REQUESTS,
            httpSuccessCount.get(),
            "All $ALLOCATION_REQUESTS allocation submissions must return HTTP 202. " +
                "Got ${httpSuccessCount.get()} successes and ${httpFailureCount.get()} HTTP errors."
        )

        assertTrue(
            finalCounts.pending == 0,
            "All allocations should be processed within ${PROCESSING_TIMEOUT_MS / 1000}s. " +
                "${finalCounts.pending} still PENDING out of ${finalCounts.total}."
        )
    }
}
