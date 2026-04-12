package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.port.api.GetEquipmentUseCase
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional(readOnly = true)
class GetEquipmentService(
    private val equipmentRepository: EquipmentRepository
) : GetEquipmentUseCase {

    override fun getEquipment(id: UUID): Equipment {
        return equipmentRepository.findById(id)
            ?: throw NotFoundException("Equipment not found with id: $id")
    }
}

