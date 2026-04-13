package com.tequipy.challenge.config

import com.tequipy.challenge.domain.AllocationLockContentionException
import org.springframework.amqp.core.Binding
import org.springframework.amqp.core.BindingBuilder
import org.springframework.amqp.core.DirectExchange
import org.springframework.amqp.core.Queue
import org.springframework.amqp.core.QueueBuilder
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory
import org.springframework.amqp.rabbit.connection.ConnectionFactory
import org.springframework.amqp.rabbit.core.RabbitTemplate
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.retry.support.RetryTemplate
import org.springframework.retry.interceptor.RetryOperationsInterceptor
import org.springframework.retry.policy.SimpleRetryPolicy

@Configuration
class RabbitMQConfig {

    companion object {
        const val ALLOCATION_QUEUE = "allocation.queue"
        const val ALLOCATION_RESULT_QUEUE = "allocation.result.queue"
        const val ALLOCATION_DLQ = "allocation.dlq"
        const val ALLOCATION_DLX = "allocation.dlx"
        const val ALLOCATION_RETRY_ATTEMPTS_PROPERTY = "tequipy.rabbitmq.allocation-retry-attempts"
        const val ALLOCATION_PREFETCH_COUNT_PROPERTY = "tequipy.rabbitmq.allocation-prefetch-count"
        const val RESULT_CONSUMER_CONCURRENCY_PROPERTY = "tequipy.rabbitmq.result-consumer-concurrency"
        const val RESULT_PREFETCH_COUNT_PROPERTY = "tequipy.rabbitmq.result-prefetch-count"
    }

    @Bean
    fun allocationQueue(): Queue = QueueBuilder.durable(ALLOCATION_QUEUE)
        .withArgument("x-dead-letter-exchange", ALLOCATION_DLX)
        .withArgument("x-dead-letter-routing-key", ALLOCATION_DLQ)
        .build()

    @Bean
    fun allocationResultQueue(): Queue = QueueBuilder.durable(ALLOCATION_RESULT_QUEUE).build()

    @Bean
    fun allocationDlq(): Queue = QueueBuilder.durable(ALLOCATION_DLQ).build()

    @Bean
    fun allocationDlx(): DirectExchange = DirectExchange(ALLOCATION_DLX)

    @Bean
    fun dlqBinding(allocationDlq: Queue, allocationDlx: DirectExchange): Binding =
        BindingBuilder.bind(allocationDlq).to(allocationDlx).with(ALLOCATION_DLQ)

    @Bean
    fun jsonMessageConverter(): Jackson2JsonMessageConverter = Jackson2JsonMessageConverter()

    @Bean
    fun republishMessageRecoverer(rabbitTemplate: RabbitTemplate): RepublishMessageRecoverer =
        RepublishMessageRecoverer(rabbitTemplate, ALLOCATION_DLX, ALLOCATION_DLQ)

    @Bean
    fun allocationRetryInterceptor(
        republishMessageRecoverer: RepublishMessageRecoverer,
        @Value("\${$ALLOCATION_RETRY_ATTEMPTS_PROPERTY:3}") maxRetryAttempts: Int
    ): RetryOperationsInterceptor {
        val retryTemplate = RetryTemplate()
        retryTemplate.setRetryPolicy(
            SimpleRetryPolicy(
                maxRetryAttempts,
                mapOf(AllocationLockContentionException::class.java to true),
                true,   // traverseCauses
                false   // defaultValue: don't retry exceptions not in the map
            )
        )
        return RetryInterceptorBuilder.stateless()
            .retryOperations(retryTemplate)
            .recoverer(republishMessageRecoverer)
            .build()
    }

    @Bean
    fun rabbitListenerContainerFactory(
        connectionFactory: ConnectionFactory,
        allocationRetryInterceptor: RetryOperationsInterceptor,
        jsonMessageConverter: Jackson2JsonMessageConverter,
        @Value("\${$ALLOCATION_PREFETCH_COUNT_PROPERTY:10}") prefetchCount: Int
    ): SimpleRabbitListenerContainerFactory {
        val factory = SimpleRabbitListenerContainerFactory()
        factory.setConnectionFactory(connectionFactory)
        factory.setAdviceChain(allocationRetryInterceptor)
        factory.setMessageConverter(jsonMessageConverter)
        factory.setPrefetchCount(prefetchCount)
        return factory
    }

    /**
     * Dedicated container factory for allocation.result.queue consumers.
     * No retry interceptor needed — result processing is a simple DB update.
     * Runs with its own concurrency pool so it never blocks on the main
     * allocation queue processing.
     */
    @Bean
    fun allocationResultListenerContainerFactory(
        connectionFactory: ConnectionFactory,
        jsonMessageConverter: Jackson2JsonMessageConverter,
        @Value("\${$RESULT_CONSUMER_CONCURRENCY_PROPERTY:4}") concurrentConsumers: Int,
        @Value("\${$RESULT_PREFETCH_COUNT_PROPERTY:20}") prefetchCount: Int
    ): SimpleRabbitListenerContainerFactory {
        val factory = SimpleRabbitListenerContainerFactory()
        factory.setConnectionFactory(connectionFactory)
        factory.setMessageConverter(jsonMessageConverter)
        factory.setConcurrentConsumers(concurrentConsumers)
        factory.setMaxConcurrentConsumers(concurrentConsumers)
        factory.setPrefetchCount(prefetchCount)
        return factory
    }
}
