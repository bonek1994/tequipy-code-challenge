package com.tequipy.challenge.adapter.api.web

import com.tequipy.challenge.adapter.api.web.dto.EquipmentRequest
import com.tequipy.challenge.adapter.api.web.dto.EquipmentResponse
import com.tequipy.challenge.adapter.api.web.dto.RetireEquipmentRequest
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.client.TestRestTemplate
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpEntity
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.LocalDate
import java.util.UUID

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
@ActiveProfiles("test")
class EquipmentControllerIntegrationTest {

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

        @JvmStatic
        @DynamicPropertySource
        fun configureProperties(registry: DynamicPropertyRegistry) {
            registry.add("spring.datasource.url", postgres::getJdbcUrl)
            registry.add("spring.datasource.username", postgres::getUsername)
            registry.add("spring.datasource.password", postgres::getPassword)
        }
    }

    private fun equipmentUrl() = "http://localhost:$port/equipments"

    @Test
    fun `POST equipment should create and return 201`() {
        val request = EquipmentRequest(
            type = EquipmentType.MAIN_COMPUTER,
            brand = "Apple",
            model = "MacBook Pro",
            conditionScore = 0.95,
            purchaseDate = LocalDate.of(2025, 1, 10)
        )

        val response = restTemplate.postForEntity(equipmentUrl(), request, EquipmentResponse::class.java)

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertNotNull(response.body)
        assertEquals(EquipmentType.MAIN_COMPUTER, response.body!!.type)
        assertEquals("Apple", response.body!!.brand)
        assertEquals(EquipmentState.AVAILABLE, response.body!!.state)
    }


    @Test
    fun `GET all equipment should return 200`() {
        restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest(EquipmentType.KEYBOARD, "Logitech", "MX Keys", 0.93, LocalDate.of(2024, 5, 1)),
            EquipmentResponse::class.java
        )

        val response = restTemplate.getForEntity(equipmentUrl(), Array<EquipmentResponse>::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.isNotEmpty())
    }

    @Test
    fun `GET equipments filtered by state should return matching items`() {
        val created = restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest(EquipmentType.MOUSE, "Logitech", "MX Master 3S", 0.91, LocalDate.of(2023, 3, 1)),
            EquipmentResponse::class.java
        ).body!!

        restTemplate.postForEntity(
            "${equipmentUrl()}/${created.id}/retire",
            RetireEquipmentRequest("Damaged sensor"),
            EquipmentResponse::class.java
        )

        val response = restTemplate.getForEntity("${equipmentUrl()}?state=RETIRED", Array<EquipmentResponse>::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.any { it.id == created.id && it.state == EquipmentState.RETIRED })
    }

    @Test
    fun `POST retire equipment should return retired equipment`() {
        val created = restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest(EquipmentType.MONITOR, "LG", "UltraFine", 0.82, LocalDate.of(2022, 9, 1)),
            EquipmentResponse::class.java
        ).body!!

        val response = restTemplate.exchange(
            "${equipmentUrl()}/${created.id}/retire",
            HttpMethod.POST,
            HttpEntity(RetireEquipmentRequest("Panel damaged")),
            EquipmentResponse::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(EquipmentState.RETIRED, response.body!!.state)
        assertEquals("Panel damaged", response.body!!.retiredReason)
    }

    @Test
    fun `retire reserved equipment path should return 404 when missing`() {
        restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest(EquipmentType.MONITOR, "AOC", "24G2", 0.7, LocalDate.of(2021, 2, 1)),
            EquipmentResponse::class.java
        ).body!!

        val response = restTemplate.exchange(
            "${equipmentUrl()}/${UUID.randomUUID()}/retire",
            HttpMethod.POST,
            HttpEntity(RetireEquipmentRequest("Missing")),
            Map::class.java
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
