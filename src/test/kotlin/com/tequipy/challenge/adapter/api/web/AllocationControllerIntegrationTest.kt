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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class AllocationControllerIntegrationTest {

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

    @Test
    fun `allocation should reserve then confirm equipment`() {
        restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest(EquipmentType.MAIN_COMPUTER, "Apple", "MacBook Pro", 0.95, LocalDate.of(2025, 1, 10)),
            EquipmentResponse::class.java
        )
        restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest(EquipmentType.MONITOR, "Dell", "U2723QE", 0.88, LocalDate.of(2024, 6, 1)),
            EquipmentResponse::class.java
        )

        val created = restTemplate.postForEntity(
            allocationUrl(),
            CreateAllocationRequest(
                employeeId = UUID.randomUUID(),
                policy = listOf(
                    EquipmentPolicyRequirementRequest(EquipmentType.MAIN_COMPUTER, quantity = 1, minimumConditionScore = 0.8, preferredBrand = "Apple"),
                    EquipmentPolicyRequirementRequest(EquipmentType.MONITOR, quantity = 1)
                )
            ),
            AllocationResponse::class.java
        )

        assertEquals(HttpStatus.CREATED, created.statusCode)
        assertNotNull(created.body)

        val allocated = waitForAllocation(created.body!!.id, AllocationState.ALLOCATED)
        assertEquals(2, allocated.allocatedEquipmentIds.size)
        assertTrue(allocated.allocatedEquipments.all { it.state == EquipmentState.RESERVED })

        val confirmed = restTemplate.postForEntity(
            "${allocationUrl()}/${allocated.id}/confirm",
            null,
            AllocationResponse::class.java
        )

        assertEquals(HttpStatus.OK, confirmed.statusCode)
        assertEquals(AllocationState.CONFIRMED, confirmed.body!!.state)
        assertTrue(confirmed.body!!.allocatedEquipments.all { it.state == EquipmentState.ASSIGNED })
    }

    @Test
    fun `allocation should fail when policy cannot be satisfied`() {
        val created = restTemplate.postForEntity(
            allocationUrl(),
            CreateAllocationRequest(
                employeeId = UUID.randomUUID(),
                policy = listOf(
                    EquipmentPolicyRequirementRequest(EquipmentType.KEYBOARD, quantity = 1, minimumConditionScore = 0.99)
                )
            ),
            AllocationResponse::class.java
        )

        assertEquals(HttpStatus.CREATED, created.statusCode)
        val failed = waitForAllocation(created.body!!.id, AllocationState.FAILED)
        assertEquals(0, failed.allocatedEquipmentIds.size)
    }

    private fun waitForAllocation(id: UUID, expectedState: AllocationState): AllocationResponse {
        lateinit var result: AllocationResponse
        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(500))
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
}

