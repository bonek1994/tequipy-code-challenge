package com.tequipy.challenge.adapter.api.web

import com.tequipy.challenge.adapter.api.web.dto.AllocationResponse
import com.tequipy.challenge.adapter.api.web.dto.CreateAllocationRequest
import com.tequipy.challenge.adapter.api.web.dto.EquipmentPolicyRequirementRequest
import com.tequipy.challenge.adapter.api.web.dto.EquipmentRequest
import com.tequipy.challenge.adapter.api.web.dto.EquipmentResponse
import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.time.LocalDate
import java.util.UUID

/**
 * End-to-end integration tests for the allocation flow:
 * HTTP request → CreateAllocationService → RabbitMQ → ProcessAllocationService
 * (with AllocationAlgorithm) → CompleteAllocationService → final state.
 *
 * Each test seeds equipment via HTTP, creates an allocation, waits for async
 * processing, and verifies that the algorithm selected the best-matching equipment.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AllocationE2EIntegrationTest {

    @LocalServerPort
    private var port: Int = 0

    @Autowired
    private lateinit var restTemplate: TestRestTemplate

    companion object {
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

    private fun equipmentUrl() = "http://localhost:$port/equipments"
    private fun allocationUrl() = "http://localhost:$port/allocations"

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun registerEquipment(
        type: EquipmentType,
        brand: String,
        model: String,
        conditionScore: Double,
        purchaseDate: LocalDate = LocalDate.of(2024, 1, 1)
    ): EquipmentResponse {
        val response = restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest(type, brand, model, conditionScore, purchaseDate),
            EquipmentResponse::class.java
        )
        assertEquals(HttpStatus.CREATED, response.statusCode)
        return response.body!!
    }

    private fun createAllocation(policy: List<EquipmentPolicyRequirementRequest>): AllocationResponse {
        val response = restTemplate.postForEntity(
            allocationUrl(),
            CreateAllocationRequest(policy = policy),
            AllocationResponse::class.java
        )
        assertEquals(HttpStatus.ACCEPTED, response.statusCode)
        assertNotNull(response.body)
        return response.body!!
    }

    private fun waitForState(id: UUID, expectedState: AllocationState, timeoutSeconds: Long = 15): AllocationResponse {
        lateinit var result: AllocationResponse
        await()
            .atMost(Duration.ofSeconds(timeoutSeconds))
            .pollInterval(Duration.ofMillis(300))
            .until {
                val body = restTemplate.getForEntity("${allocationUrl()}/$id", AllocationResponse::class.java).body
                if (body?.state == expectedState) {
                    result = body
                    true
                } else {
                    false
                }
            }
        return result
    }

    // -------------------------------------------------------------------------
    // Scenario 1: Preferred brand is selected over higher condition non-preferred
    // -------------------------------------------------------------------------

    @Test
    fun `algorithm selects preferred brand Apple over higher-condition Dell`() {
        // Seed: Apple laptop (0.85) and Dell laptop (0.98)
        // Policy: prefer Apple, min condition 0.8
        // Expected: Apple selected (brand bonus 10.0 + 0.85 = 10.85 > 0.98)
        val apple = registerEquipment(EquipmentType.MAIN_COMPUTER, "Apple", "MacBook Pro 14", 0.85)
        val dell = registerEquipment(EquipmentType.MAIN_COMPUTER, "Dell", "XPS 15", 0.98)

        val created = createAllocation(
            listOf(
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.MAIN_COMPUTER,
                    quantity = 1,
                    minimumConditionScore = 0.8,
                    preferredBrand = "Apple"
                )
            )
        )

        val allocated = waitForState(created.id, AllocationState.ALLOCATED)

        assertEquals(AllocationState.ALLOCATED, allocated.state)
        assertEquals(1, allocated.allocatedEquipmentIds.size)
        assertEquals(apple.id, allocated.allocatedEquipmentIds[0])
        assertTrue(allocated.allocatedEquipments.all { it.state == EquipmentState.RESERVED })
    }

    // -------------------------------------------------------------------------
    // Scenario 2: Multi-slot allocation picks best combination across types
    // -------------------------------------------------------------------------

    @Test
    fun `algorithm allocates best computer and best monitor for multi-type policy`() {
        // Seed: 2 computers (Apple 0.95, Dell 0.80) + 2 monitors (LG 0.90, Samsung 0.70)
        // Policy: 1 computer (prefer Apple, min 0.7) + 1 monitor (min 0.7)
        // Expected: Apple computer + LG monitor
        val appleLaptop = registerEquipment(EquipmentType.MAIN_COMPUTER, "Apple", "MacBook Air M3", 0.95, LocalDate.of(2025, 3, 1))
        val dellLaptop = registerEquipment(EquipmentType.MAIN_COMPUTER, "Dell", "Latitude 5540", 0.80, LocalDate.of(2024, 6, 1))
        val lgMonitor = registerEquipment(EquipmentType.MONITOR, "LG", "27UK850", 0.90, LocalDate.of(2025, 1, 1))
        val samsungMonitor = registerEquipment(EquipmentType.MONITOR, "Samsung", "S27R750", 0.70, LocalDate.of(2023, 9, 1))

        val created = createAllocation(
            listOf(
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.MAIN_COMPUTER,
                    quantity = 1,
                    minimumConditionScore = 0.7,
                    preferredBrand = "Apple"
                ),
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.MONITOR,
                    quantity = 1,
                    minimumConditionScore = 0.7
                )
            )
        )

        val allocated = waitForState(created.id, AllocationState.ALLOCATED)

        assertEquals(AllocationState.ALLOCATED, allocated.state)
        assertEquals(2, allocated.allocatedEquipmentIds.size)
        assertTrue(allocated.allocatedEquipmentIds.contains(appleLaptop.id))
        assertTrue(allocated.allocatedEquipmentIds.contains(lgMonitor.id))
        assertTrue(allocated.allocatedEquipments.all { it.state == EquipmentState.RESERVED })
    }

    // -------------------------------------------------------------------------
    // Scenario 3: Minimum condition score filters out low-quality equipment
    // -------------------------------------------------------------------------

    @Test
    fun `algorithm respects minimum condition score hard constraint`() {
        // Seed: keyboard 0.60 (below threshold) and keyboard 0.85 (above threshold)
        // Policy: 1 keyboard, min condition 0.75
        // Expected: only 0.85 keyboard selected
        val lowCondition = registerEquipment(EquipmentType.KEYBOARD, "Logitech", "K380", 0.60)
        val goodCondition = registerEquipment(EquipmentType.KEYBOARD, "Logitech", "MX Keys", 0.85)

        val created = createAllocation(
            listOf(
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.KEYBOARD,
                    quantity = 1,
                    minimumConditionScore = 0.75
                )
            )
        )

        val allocated = waitForState(created.id, AllocationState.ALLOCATED)

        assertEquals(1, allocated.allocatedEquipmentIds.size)
        assertEquals(goodCondition.id, allocated.allocatedEquipmentIds[0])
    }

    // -------------------------------------------------------------------------
    // Scenario 4: Multi-quantity selects best distinct items
    // -------------------------------------------------------------------------

    @Test
    fun `algorithm selects top N distinct items for quantity greater than 1`() {
        // Seed: 3 mice with different conditions (0.95, 0.85, 0.70)
        // Policy: 2 mice, min condition 0.65
        // Expected: top 2 by condition (0.95 and 0.85)
        val best = registerEquipment(EquipmentType.MOUSE, "Logitech", "MX Master 3", 0.95)
        val mid = registerEquipment(EquipmentType.MOUSE, "Razer", "DeathAdder V3", 0.85)
        val low = registerEquipment(EquipmentType.MOUSE, "Generic", "Basic Mouse", 0.70)

        val created = createAllocation(
            listOf(
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.MOUSE,
                    quantity = 2,
                    minimumConditionScore = 0.65
                )
            )
        )

        val allocated = waitForState(created.id, AllocationState.ALLOCATED)

        assertEquals(2, allocated.allocatedEquipmentIds.size)
        assertTrue(allocated.allocatedEquipmentIds.contains(best.id))
        assertTrue(allocated.allocatedEquipmentIds.contains(mid.id))
        assertTrue(!allocated.allocatedEquipmentIds.contains(low.id))
    }

    // -------------------------------------------------------------------------
    // Scenario 5: Allocation FAILS when not enough equipment matches
    // -------------------------------------------------------------------------

    @Test
    fun `allocation fails when quantity exceeds available matching equipment`() {
        // Seed: only 1 monitor with condition >= 0.9
        registerEquipment(EquipmentType.MONITOR, "Dell", "U2723QE", 0.92)
        registerEquipment(EquipmentType.MONITOR, "LG", "Budget", 0.50) // below threshold

        val created = createAllocation(
            listOf(
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.MONITOR,
                    quantity = 2,
                    minimumConditionScore = 0.9
                )
            )
        )

        val failed = waitForState(created.id, AllocationState.FAILED)

        assertEquals(AllocationState.FAILED, failed.state)
        assertTrue(failed.allocatedEquipmentIds.isEmpty())
    }

    // -------------------------------------------------------------------------
    // Scenario 6: Brand preference is soft — non-preferred still selected if only option
    // -------------------------------------------------------------------------

    @Test
    fun `algorithm selects non-preferred brand when preferred brand is unavailable`() {
        // Seed: only Dell keyboard available, policy prefers Apple
        // Expected: Dell selected (soft preference doesn't exclude it)
        val dell = registerEquipment(EquipmentType.KEYBOARD, "Dell", "KB216", 0.90)

        val created = createAllocation(
            listOf(
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.KEYBOARD,
                    quantity = 1,
                    preferredBrand = "Apple"
                )
            )
        )

        val allocated = waitForState(created.id, AllocationState.ALLOCATED)

        assertEquals(1, allocated.allocatedEquipmentIds.size)
        assertEquals(dell.id, allocated.allocatedEquipmentIds[0])
    }

    // -------------------------------------------------------------------------
    // Scenario 7: Globally feasible combination — constrained + unconstrained slots
    // -------------------------------------------------------------------------

    @Test
    fun `algorithm finds globally feasible combination when slots compete for equipment`() {
        // Seed: 2 monitors — A (0.95, Dell) and B (0.80, LG)
        // Policy: slot 1: monitor, min 0.9 (only A qualifies) + slot 2: monitor, min 0.7 (both qualify)
        // Greedy would pick A for slot 2 (higher score), leaving slot 1 unsatisfied.
        // Global search must assign A to slot 1 and B to slot 2.
        val monitorA = registerEquipment(EquipmentType.MONITOR, "Dell", "Premium", 0.95)
        val monitorB = registerEquipment(EquipmentType.MONITOR, "LG", "Standard", 0.80)

        val created = createAllocation(
            listOf(
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.MONITOR,
                    quantity = 1,
                    minimumConditionScore = 0.9
                ),
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.MONITOR,
                    quantity = 1,
                    minimumConditionScore = 0.7
                )
            )
        )

        val allocated = waitForState(created.id, AllocationState.ALLOCATED)

        assertEquals(2, allocated.allocatedEquipmentIds.size)
        val allocatedIds = allocated.allocatedEquipmentIds.toSet()
        assertTrue(allocatedIds.contains(monitorA.id))
        assertTrue(allocatedIds.contains(monitorB.id))
    }

    // -------------------------------------------------------------------------
    // Scenario 8: Full lifecycle — create → allocate → verify equipment state
    // -------------------------------------------------------------------------

    @Test
    fun `full lifecycle creates allocation and reserves equipment correctly`() {
        val computer = registerEquipment(EquipmentType.MAIN_COMPUTER, "Apple", "MacBook Pro 16", 0.98, LocalDate.of(2025, 2, 1))
        val monitor = registerEquipment(EquipmentType.MONITOR, "Dell", "U3223QE", 0.92, LocalDate.of(2025, 1, 15))
        val keyboard = registerEquipment(EquipmentType.KEYBOARD, "Apple", "Magic Keyboard", 0.90, LocalDate.of(2024, 11, 1))
        val mouse = registerEquipment(EquipmentType.MOUSE, "Apple", "Magic Mouse", 0.88, LocalDate.of(2024, 10, 1))

        val created = createAllocation(
            listOf(
                EquipmentPolicyRequirementRequest(type = EquipmentType.MAIN_COMPUTER, quantity = 1, preferredBrand = "Apple"),
                EquipmentPolicyRequirementRequest(type = EquipmentType.MONITOR, quantity = 1),
                EquipmentPolicyRequirementRequest(type = EquipmentType.KEYBOARD, quantity = 1),
                EquipmentPolicyRequirementRequest(type = EquipmentType.MOUSE, quantity = 1)
            )
        )

        assertEquals(AllocationState.PENDING, created.state)

        val allocated = waitForState(created.id, AllocationState.ALLOCATED)

        // Verify allocation state
        assertEquals(AllocationState.ALLOCATED, allocated.state)
        assertEquals(4, allocated.allocatedEquipmentIds.size)

        // Verify all expected equipment items are allocated
        val allocatedIds = allocated.allocatedEquipmentIds.toSet()
        assertTrue(allocatedIds.contains(computer.id))
        assertTrue(allocatedIds.contains(monitor.id))
        assertTrue(allocatedIds.contains(keyboard.id))
        assertTrue(allocatedIds.contains(mouse.id))

        // Verify all equipment is in RESERVED state
        assertTrue(allocated.allocatedEquipments.all { it.state == EquipmentState.RESERVED })

        // Verify equipment details match what was registered
        val allocatedComputer = allocated.allocatedEquipments.find { it.id == computer.id }!!
        assertEquals("Apple", allocatedComputer.brand)
        assertEquals("MacBook Pro 16", allocatedComputer.model)
        assertEquals(0.98, allocatedComputer.conditionScore)
    }

    // -------------------------------------------------------------------------
    // Scenario 9: Case-insensitive brand preference
    // -------------------------------------------------------------------------

    @Test
    fun `algorithm matches preferred brand case-insensitively`() {
        val apple = registerEquipment(EquipmentType.MAIN_COMPUTER, "Apple", "MacBook Air", 0.82)
        val dell = registerEquipment(EquipmentType.MAIN_COMPUTER, "Dell", "XPS 13", 0.90)

        val created = createAllocation(
            listOf(
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.MAIN_COMPUTER,
                    quantity = 1,
                    preferredBrand = "apple" // lowercase
                )
            )
        )

        val allocated = waitForState(created.id, AllocationState.ALLOCATED)

        assertEquals(1, allocated.allocatedEquipmentIds.size)
        // Apple selected despite lower condition score — brand bonus (10.0) dominates
        assertEquals(apple.id, allocated.allocatedEquipmentIds[0])
    }

    // -------------------------------------------------------------------------
    // Scenario 10: No condition constraint — all equipment eligible
    // -------------------------------------------------------------------------

    @Test
    fun `allocation without condition score constraint selects best scoring equipment`() {
        val great = registerEquipment(EquipmentType.MOUSE, "Logitech", "G Pro X", 0.99)
        val poor = registerEquipment(EquipmentType.MOUSE, "NoName", "Cheap Mouse", 0.10)

        val created = createAllocation(
            listOf(
                EquipmentPolicyRequirementRequest(
                    type = EquipmentType.MOUSE,
                    quantity = 1
                    // no minimumConditionScore — both qualify
                )
            )
        )

        val allocated = waitForState(created.id, AllocationState.ALLOCATED)

        assertEquals(1, allocated.allocatedEquipmentIds.size)
        // Best condition score wins when no brand preference
        assertEquals(great.id, allocated.allocatedEquipmentIds[0])
    }
}

