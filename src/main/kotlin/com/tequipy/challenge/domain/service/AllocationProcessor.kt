package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.AllocationState
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.out.AllocationRepository
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import org.springframework.stereotype.Component

@Component
class AllocationProcessor(
    private val allocationRepository: AllocationRepository,
    private val equipmentRepository: EquipmentRepository
) {
    private val allocationAlgorithm = AllocationAlgorithm()

    fun processAllocation(allocationId: java.util.UUID) {
        val allocation = allocationRepository.findById(allocationId) ?: return
        if (allocation.state != AllocationState.PENDING) return

        val selected = allocationAlgorithm.allocate(
            policy = allocation.policy,
            availableEquipment = equipmentRepository.findByState(EquipmentState.AVAILABLE)
        )

        if (selected == null) {
            allocationRepository.save(allocation.copy(state = AllocationState.FAILED, allocatedEquipmentIds = emptyList()))
            return
        }

        equipmentRepository.saveAll(selected.map { it.copy(state = EquipmentState.RESERVED) })
        allocationRepository.save(
            allocation.copy(
                state = AllocationState.ALLOCATED,
                allocatedEquipmentIds = selected.map { it.id }
            )
        )
    }
}

