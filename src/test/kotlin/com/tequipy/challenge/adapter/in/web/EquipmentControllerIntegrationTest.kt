package com.tequipy.challenge.adapter.`in`.web

import com.tequipy.challenge.adapter.`in`.web.dto.EmployeeRequest
import com.tequipy.challenge.adapter.`in`.web.dto.EmployeeResponse
import com.tequipy.challenge.adapter.`in`.web.dto.EquipmentRequest
import com.tequipy.challenge.adapter.`in`.web.dto.EquipmentResponse
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

    private fun equipmentUrl() = "http://localhost:$port/api/equipment"
    private fun employeeUrl() = "http://localhost:$port/api/employees"

    @Test
    fun `POST equipment should create and return 201`() {
        val request = EquipmentRequest("Laptop Pro", "SN-LAPTOP-001")

        val response = restTemplate.postForEntity(equipmentUrl(), request, EquipmentResponse::class.java)

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertNotNull(response.body)
        assertEquals("Laptop Pro", response.body!!.name)
        assertEquals("SN-LAPTOP-001", response.body!!.serialNumber)
        assertNull(response.body!!.employeeId)
    }

    @Test
    fun `GET equipment by id should return 200 when found`() {
        val created = restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest("Monitor", "SN-MON-001"),
            EquipmentResponse::class.java
        ).body!!

        val response = restTemplate.getForEntity("${equipmentUrl()}/${created.id}", EquipmentResponse::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(created.id, response.body!!.id)
        assertEquals("Monitor", response.body!!.name)
    }

    @Test
    fun `GET equipment by id should return 404 when not found`() {
        val response = restTemplate.getForEntity("${equipmentUrl()}/${UUID.randomUUID()}", Map::class.java)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `GET all equipment should return 200`() {
        restTemplate.postForEntity(equipmentUrl(), EquipmentRequest("Keyboard", "SN-KB-001"), EquipmentResponse::class.java)

        val response = restTemplate.getForEntity(equipmentUrl(), Array<EquipmentResponse>::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.isNotEmpty())
    }

    @Test
    fun `PUT equipment should update and return 200`() {
        val created = restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest("Old Mouse", "SN-MS-001"),
            EquipmentResponse::class.java
        ).body!!

        val updateRequest = EquipmentRequest("New Mouse", "SN-MS-002")
        val response = restTemplate.exchange(
            "${equipmentUrl()}/${created.id}",
            HttpMethod.PUT,
            HttpEntity(updateRequest),
            EquipmentResponse::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("New Mouse", response.body!!.name)
        assertEquals("SN-MS-002", response.body!!.serialNumber)
    }

    @Test
    fun `DELETE equipment should return 204`() {
        val created = restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest("Headset", "SN-HS-001"),
            EquipmentResponse::class.java
        ).body!!

        val response = restTemplate.exchange(
            "${equipmentUrl()}/${created.id}",
            HttpMethod.DELETE,
            null,
            Void::class.java
        )

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
    }

    @Test
    fun `assign equipment to employee should return 200`() {
        val employee = restTemplate.postForEntity(
            employeeUrl(),
            EmployeeRequest("Frank", "frank@tequipy.com", "IT"),
            EmployeeResponse::class.java
        ).body!!

        val equipment = restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest("Tablet", "SN-TAB-001"),
            EquipmentResponse::class.java
        ).body!!

        val response = restTemplate.exchange(
            "${equipmentUrl()}/${equipment.id}/assign/${employee.id}",
            HttpMethod.PUT,
            null,
            EquipmentResponse::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(employee.id, response.body!!.employeeId)
    }

    @Test
    fun `unassign equipment should return 200 with null employeeId`() {
        val employee = restTemplate.postForEntity(
            employeeUrl(),
            EmployeeRequest("Grace", "grace@tequipy.com", "Ops"),
            EmployeeResponse::class.java
        ).body!!

        val equipment = restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest("Docking Station", "SN-DOCK-001"),
            EquipmentResponse::class.java
        ).body!!

        restTemplate.exchange(
            "${equipmentUrl()}/${equipment.id}/assign/${employee.id}",
            HttpMethod.PUT,
            null,
            EquipmentResponse::class.java
        )

        val response = restTemplate.exchange(
            "${equipmentUrl()}/${equipment.id}/unassign",
            HttpMethod.PUT,
            null,
            EquipmentResponse::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertNull(response.body!!.employeeId)
    }

    @Test
    fun `assign equipment should return 404 when employee not found`() {
        val equipment = restTemplate.postForEntity(
            equipmentUrl(),
            EquipmentRequest("Webcam", "SN-CAM-001"),
            EquipmentResponse::class.java
        ).body!!

        val response = restTemplate.exchange(
            "${equipmentUrl()}/${equipment.id}/assign/${UUID.randomUUID()}",
            HttpMethod.PUT,
            null,
            Map::class.java
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
