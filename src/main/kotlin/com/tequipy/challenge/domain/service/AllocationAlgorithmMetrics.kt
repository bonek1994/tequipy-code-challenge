package com.tequipy.challenge.domain.service

import java.util.concurrent.ConcurrentLinkedQueue

@Suppress("unused")
object AllocationAlgorithmMetrics {

    private val durationsNanos = ConcurrentLinkedQueue<Long>()

    fun record(durationNanos: Long) {
        durationsNanos.add(durationNanos)
    }

    fun snapshotNanos(): List<Long> = durationsNanos.toList()

    fun reset() {
        durationsNanos.clear()
    }
}

