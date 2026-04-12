package com.tequipy.challenge.config

import com.rabbitmq.client.Channel
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.aopalliance.intercept.MethodInvocation
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.springframework.amqp.core.Message
import org.springframework.amqp.core.MessageProperties

class ManualAcknowledgementInterceptorTest {

    private val interceptor = ManualAcknowledgementInterceptor()

    private fun buildInvocation(
        channel: Channel,
        deliveryTag: Long,
        action: () -> Any?
    ): MethodInvocation {
        val props = MessageProperties()
        props.deliveryTag = deliveryTag
        val message = Message(ByteArray(0), props)
        val invocation = mockk<MethodInvocation>()
        every { invocation.arguments } returns arrayOf(message, channel)
        every { invocation.proceed() } answers { action() }
        return invocation
    }

    @Test
    fun `should basicAck when listener completes successfully`() {
        val channel = mockk<Channel>(relaxed = true)
        every { channel.isOpen } returns true
        val invocation = buildInvocation(channel, deliveryTag = 42L) { null }

        interceptor.invoke(invocation)

        verify(exactly = 1) { channel.basicAck(42L, false) }
        verify(exactly = 0) { channel.basicNack(any(), any(), any()) }
    }

    @Test
    fun `should basicNack with requeue when listener throws`() {
        val channel = mockk<Channel>(relaxed = true)
        every { channel.isOpen } returns true
        val invocation = buildInvocation(channel, deliveryTag = 7L) { throw RuntimeException("processing failed") }

        assertThrows<RuntimeException> { interceptor.invoke(invocation) }

        verify(exactly = 0) { channel.basicAck(any(), any()) }
        verify(exactly = 1) { channel.basicNack(7L, false, true) }
    }

    @Test
    fun `should not call basicAck when channel is closed`() {
        val channel = mockk<Channel>(relaxed = true)
        every { channel.isOpen } returns false
        val invocation = buildInvocation(channel, deliveryTag = 1L) { null }

        interceptor.invoke(invocation)

        verify(exactly = 0) { channel.basicAck(any(), any()) }
    }
}
