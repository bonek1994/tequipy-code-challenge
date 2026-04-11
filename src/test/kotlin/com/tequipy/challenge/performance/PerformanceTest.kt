package com.tequipy.challenge.performance

import com.tequipy.challenge.adapter.api.web.dto.AllocationResponse
import com.tequipy.challenge.adapter.api.web.dto.CreateAllocationRequest
import com.tequipy.challenge.adapter.api.web.dto.EquipmentPolicyRequirementRequest
import com.tequipy.challenge.domain.model.EquipmentType
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
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
 * Performance test suite for the Tequipy allocation service running with 3 replicas in Kubernetes.
 *
 * This test:
 *  1. Seeds 15,000 MAIN_COMPUTER laptops into the database:
 *       - 7,500 Apple laptops  (MacBook Pro / MacBook Air variants)
 *       - 7,500 Dell  laptops  (XPS / Latitude / Precision variants – representative "Windows" machines)
 *       Each item has a random condition score in [0.60, 1.0] and a random purchase date in 2022–2025.
 *  2. Submits 5,000 concurrent allocation requests, each requesting:
 *       - 1 × MAIN_COMPUTER (preferredBrand = "Apple",  minimumConditionScore = 0.7)
 *       - 1 × MAIN_COMPUTER (preferredBrand = "Dell",   minimumConditionScore = 0.7)
 *  3. Asserts that every HTTP submission returns HTTP 201 (CREATED).
 *  4. Prints a performance summary: total duration, throughput, and P95 / P99 response times.
 *
 * Kubernetes note:
 *   The k8s/deployment.yaml is configured with replicas: 3 so that three application instances
 *   share the same PostgreSQL and RabbitMQ instances. To run this test against an actual cluster:
 *     1. Build: ./gradlew bootJar && docker build -t tequipy-code-challenge:local .
 *     2. Load into kind:  kind load docker-image tequipy-code-challenge:local
 *     3. Apply manifests: kubectl apply -f k8s/
 *     4. Forward port:    kubectl port-forward svc/tequipy-app 8080:8080
 *     5. Point this test at the forwarded address instead of the embedded server.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Tag("performance")
class PerformanceTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    companion object {
        private const val APPLE_COUNT = 7_500
        private const val DELL_COUNT = 7_500
        private const val ALLOCATION_REQUESTS = 5_000
        private const val THREAD_POOL_SIZE = 50
        private const val MIN_CONDITION = 0.7
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
                    conditionScore = 0.6 + RANDOM_SEED.nextDouble() * 0.4,
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
                    conditionScore = 0.6 + RANDOM_SEED.nextDouble() * 0.4,
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
    // Performance test
    // -------------------------------------------------------------------------

    @Test
    fun `5000 concurrent allocation requests for Apple and Windows laptops with minimum condition 0_7`() {
        val rows = buildEquipmentRows()
        val appleEligible = rows.count { it.brand == "Apple" && it.conditionScore >= MIN_CONDITION }
        val dellEligible = rows.count { it.brand == "Dell" && it.conditionScore >= MIN_CONDITION }

        println("\n=== Seeding ${rows.size} equipment items ===")
        val seedMs = measureTimeMillis { seedEquipment(rows) }
        println("Seeded ${rows.size} items in ${seedMs} ms")
        println("  Apple laptops eligible (condition >= $MIN_CONDITION): $appleEligible / $APPLE_COUNT")
        println("  Dell  laptops eligible (condition >= $MIN_CONDITION): $dellEligible / $DELL_COUNT")

        val executor = Executors.newFixedThreadPool(THREAD_POOL_SIZE)
        val httpSuccessCount = AtomicInteger(0)
        val httpFailureCount = AtomicInteger(0)
        val responseTimes = CopyOnWriteArrayList<Long>()

        println("\n=== Starting $ALLOCATION_REQUESTS concurrent allocation requests ===")
        val testDurationMs = measureTimeMillis {
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
                        if (response.statusCode == HttpStatus.CREATED) {
                            httpSuccessCount.incrementAndGet()
                        } else {
                            httpFailureCount.incrementAndGet()
                        }
                    } catch (e: Exception) {
                        responseTimes.add(System.currentTimeMillis() - start)
                        httpFailureCount.incrementAndGet()
                    }
                }, executor)
            }
            CompletableFuture.allOf(*futures.toTypedArray()).join()
        }

        executor.shutdown()

        val sorted = responseTimes.sorted()
        val avg = if (sorted.isNotEmpty()) sorted.average() else 0.0
        val p95 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.95).toInt().coerceAtMost(sorted.size - 1)] else 0L
        val p99 = if (sorted.isNotEmpty()) sorted[(sorted.size * 0.99).toInt().coerceAtMost(sorted.size - 1)] else 0L
        val throughput = responseTimes.size * 1_000.0 / testDurationMs

        println(
            """
            
=== Performance Test Results ===
Total requests submitted : $ALLOCATION_REQUESTS
HTTP 201 (success)       : ${httpSuccessCount.get()}
HTTP errors              : ${httpFailureCount.get()}
Total wall-clock time    : ${testDurationMs} ms
Throughput               : ${"%.1f".format(throughput)} req/s
Avg response time        : ${"%.1f".format(avg)} ms
P95 response time        : $p95 ms
P99 response time        : $p99 ms
=================================
            """.trimIndent()
        )

        assertEquals(
            ALLOCATION_REQUESTS,
            httpSuccessCount.get(),
            "All $ALLOCATION_REQUESTS allocation submissions must return HTTP 201. " +
                "Got ${httpSuccessCount.get()} successes and ${httpFailureCount.get()} HTTP errors."
        )
    }
}
