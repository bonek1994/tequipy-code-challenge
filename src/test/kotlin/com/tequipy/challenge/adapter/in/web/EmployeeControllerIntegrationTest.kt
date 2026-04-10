package com.tequipy.challenge.adapter.`in`.web

import com.tequipy.challenge.adapter.`in`.web.dto.EmployeeRequest
import com.tequipy.challenge.adapter.`in`.web.dto.EmployeeResponse
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
class EmployeeControllerIntegrationTest {

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

    private fun baseUrl() = "http://localhost:$port/api/employees"

    @Test
    fun `POST employee should create and return 201`() {
        val request = EmployeeRequest("Alice Smith", "alice.smith@tequipy.com", "Engineering")

        val response = restTemplate.postForEntity(baseUrl(), request, EmployeeResponse::class.java)

        assertEquals(HttpStatus.CREATED, response.statusCode)
        assertNotNull(response.body)
        assertEquals("Alice Smith", response.body!!.name)
        assertEquals("alice.smith@tequipy.com", response.body!!.email)
        assertEquals("Engineering", response.body!!.department)
        assertNotNull(response.body!!.id)
    }

    @Test
    fun `GET employee by id should return 200 when found`() {
        val created = restTemplate.postForEntity(
            baseUrl(),
            EmployeeRequest("Bob Jones", "bob.jones@tequipy.com", "HR"),
            EmployeeResponse::class.java
        ).body!!

        val response = restTemplate.getForEntity("${baseUrl()}/${created.id}", EmployeeResponse::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals(created.id, response.body!!.id)
        assertEquals("Bob Jones", response.body!!.name)
    }

    @Test
    fun `GET employee by id should return 404 when not found`() {
        val response = restTemplate.getForEntity("${baseUrl()}/${UUID.randomUUID()}", Map::class.java)

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }

    @Test
    fun `GET all employees should return 200`() {
        restTemplate.postForEntity(baseUrl(), EmployeeRequest("Carol", "carol@tequipy.com", "Finance"), EmployeeResponse::class.java)

        val response = restTemplate.getForEntity(baseUrl(), Array<EmployeeResponse>::class.java)

        assertEquals(HttpStatus.OK, response.statusCode)
        assertTrue(response.body!!.isNotEmpty())
    }

    @Test
    fun `PUT employee should update and return 200`() {
        val created = restTemplate.postForEntity(
            baseUrl(),
            EmployeeRequest("Dave Original", "dave@tequipy.com", "IT"),
            EmployeeResponse::class.java
        ).body!!

        val updateRequest = EmployeeRequest("Dave Updated", "dave.updated@tequipy.com", "IT")
        val response = restTemplate.exchange(
            "${baseUrl()}/${created.id}",
            HttpMethod.PUT,
            HttpEntity(updateRequest),
            EmployeeResponse::class.java
        )

        assertEquals(HttpStatus.OK, response.statusCode)
        assertEquals("Dave Updated", response.body!!.name)
        assertEquals("dave.updated@tequipy.com", response.body!!.email)
    }

    @Test
    fun `DELETE employee should return 204`() {
        val created = restTemplate.postForEntity(
            baseUrl(),
            EmployeeRequest("Eve Delete", "eve@tequipy.com", "Ops"),
            EmployeeResponse::class.java
        ).body!!

        val response = restTemplate.exchange(
            "${baseUrl()}/${created.id}",
            HttpMethod.DELETE,
            null,
            Void::class.java
        )

        assertEquals(HttpStatus.NO_CONTENT, response.statusCode)
    }

    @Test
    fun `DELETE employee should return 404 when not found`() {
        val response = restTemplate.exchange(
            "${baseUrl()}/${UUID.randomUUID()}",
            HttpMethod.DELETE,
            null,
            Map::class.java
        )

        assertEquals(HttpStatus.NOT_FOUND, response.statusCode)
    }
}
