package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.event.AllocationCreatedEvent
import org.springframework.stereotype.Component
import org.springframework.transaction.event.TransactionPhase
import org.springframework.transaction.event.TransactionalEventListener

@Component
class AllocationEventHandler(
    private val allocationProcessor: AllocationProcessor
) {
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    fun onAllocationCreated(event: AllocationCreatedEvent) {
        allocationProcessor.processAllocation(event.allocationId)
    }
}





