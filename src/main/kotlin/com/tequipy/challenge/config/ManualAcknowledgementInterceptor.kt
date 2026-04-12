package com.tequipy.challenge.config

import com.rabbitmq.client.Channel
import io.github.oshai.kotlinlogging.KotlinLogging
import org.aopalliance.intercept.MethodInterceptor
import org.aopalliance.intercept.MethodInvocation
import org.springframework.amqp.core.Message

/**
 * Outermost advice in the listener container's advice chain.
 *
 * When [org.springframework.amqp.core.AcknowledgeMode.MANUAL] is active the broker holds the
 * message until the consumer sends an explicit acknowledgement.  Inner advice (e.g. the retry
 * interceptor + dead-letter recoverer) operates *inside* this interceptor, so by the time
 * control returns here:
 *
 * - **Success**: processing completed normally → `basicAck` removes the message from the queue.
 * - **Retry exhausted / DLQ recovery**: the recoverer published to the DLQ and returned without
 *   throwing, so `basicAck` is still called and the original message is removed.
 * - **Unrecoverable exception**: propagated out of the inner chain → `basicNack` with
 *   `requeue = true` so that the message is re-delivered for another attempt.
 *
 * The raw [Channel] and delivery tag are extracted from the method-invocation arguments that
 * Spring AMQP passes to `MessagingMessageListenerAdapter.onMessage(Message, Channel)`.
 */
class ManualAcknowledgementInterceptor : MethodInterceptor {

    private val logger = KotlinLogging.logger {}

    override fun invoke(invocation: MethodInvocation): Any? {
        val args = invocation.arguments
        val message = args.firstOrNull { it is Message } as? Message
        val channel = args.firstOrNull { it is Channel } as? Channel
        val deliveryTag = message?.messageProperties?.deliveryTag

        return try {
            val result = invocation.proceed()
            if (channel != null && deliveryTag != null && channel.isOpen) {
                logger.debug { "Acknowledging message: deliveryTag=$deliveryTag" }
                channel.basicAck(deliveryTag, false)
            }
            result
        } catch (ex: Exception) {
            if (channel != null && deliveryTag != null && channel.isOpen) {
                logger.warn { "Negative-acknowledging message (requeue=true): deliveryTag=$deliveryTag, reason=${ex.message}" }
                channel.basicNack(deliveryTag, false, true)
            }
            throw ex
        }
    }
}
