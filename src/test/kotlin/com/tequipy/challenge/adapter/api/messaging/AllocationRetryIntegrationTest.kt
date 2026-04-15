package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.adapter.api.messaging.events.AllocationCreated
import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.service.BatchAllocationService
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.mockito.kotlin.any
import org.springframework.amqp.core.AmqpTemplate
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.MockBean
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.testcontainers.containers.PostgreSQLContainer
import org.testcontainers.containers.RabbitMQContainer
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import java.time.Duration
import java.util.UUID

/**
 * Integration test for the batch allocation pipeline.
 *
 * With the batch design the [AllocationMessageListener] no longer calls
 * [com.tequipy.challenge.domain.port.api.ProcessAllocationUseCase] directly.
 * Instead it submits each incoming message to [com.tequipy.challenge.domain.service.BatchAllocationCollector],
 * which flushes to [BatchAllocationService] when the batch is full or the window expires.
 *
 * This test verifies that messages sent to `allocation.queue` are received, collected, and
 * forwarded to [BatchAllocationService.processBatch] within the configured window.
 * A short window (`tequipy.batch-allocation.window-ms=500`) keeps the test fast.
 */
@SpringBootTest
@Testcontainers
class AllocationRetryIntegrationTest {

    @MockBean
    private lateinit var batchAllocationService: BatchAllocationService

    @Autowired
    private lateinit var amqpTemplate: AmqpTemplate

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
            // Short batch window so the test doesn't wait 5 s
            registry.add("tequipy.batch-allocation.window-ms") { 500 }
        }
    }

    @Test
    fun `message sent to allocation queue should be forwarded to BatchAllocationService within the window`() {
        // given
        val allocationId = UUID.randomUUID()
        val message = AllocationCreated(id = allocationId, policy = emptyList())

        // when
        amqpTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_QUEUE, message)

        // then: processBatch must be called within 10 s (window is 500 ms)
        await()
            .atMost(Duration.ofSeconds(10))
            .pollInterval(Duration.ofMillis(200))
            .untilAsserted {
                Mockito.verify(batchAllocationService, Mockito.atLeastOnce()).processBatch(any())
            }
    }
}
