package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.api.ListEquipmentUseCase
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional(readOnly = true)
class ListEquipmentService(
    private val equipmentRepository: EquipmentRepository
) : ListEquipmentUseCase {

    override fun listEquipment(state: EquipmentState?): List<Equipment> {
        return if (state == null) equipmentRepository.findAll() else equipmentRepository.findByState(state)
    }
}

