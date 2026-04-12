package com.tequipy.challenge.adapter.api.messaging

import com.tequipy.challenge.config.RabbitMQConfig
import com.tequipy.challenge.domain.AllocationLockContentionException
import com.tequipy.challenge.domain.port.api.ProcessAllocationUseCase
import org.awaitility.Awaitility.await
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test
import org.mockito.kotlin.any
import org.mockito.Mockito
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

@SpringBootTest
@Testcontainers
class AllocationRetryIntegrationTest {

    @MockBean
    private lateinit var processAllocationUseCase: ProcessAllocationUseCase

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
            registry.add(RabbitMQConfig.ALLOCATION_RETRY_ATTEMPTS_PROPERTY) { 1 }
        }
    }

    @Test
    fun `message should be routed to DLQ after 1 consecutive AllocationLockContentionException`() {
        // given: processor always throws lock contention
        val allocationId = UUID.randomUUID()
        Mockito.doThrow(AllocationLockContentionException(allocationId))
            .`when`(processAllocationUseCase).processAllocation(any())

        // when: send a typed AllocationRequestedMessage object directly to the allocation queue
        val message = AllocationRequestedMessage(id = allocationId, policy = emptyList())
        amqpTemplate.convertAndSend(RabbitMQConfig.ALLOCATION_QUEUE, message)

        // then: message should appear in DLQ after retry exhaustion
        val dlqMessage = await()
            .atMost(Duration.ofSeconds(60))
            .pollInterval(Duration.ofMillis(500))
            .until({ amqpTemplate.receiveAndConvert(RabbitMQConfig.ALLOCATION_DLQ) }, { it != null })
        assertNotNull(dlqMessage, "Message should be routed to DLQ after 1 retry attempt")

        // verify that the processor was invoked exactly once
        Mockito.verify(processAllocationUseCase, Mockito.timeout(30_000).times(1))
            .processAllocation(any())
    }
}
