package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import com.tequipy.challenge.domain.port.`in`.EquipmentUseCase
import com.tequipy.challenge.domain.port.out.EquipmentRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.util.UUID

@Service
@Transactional
class EquipmentService(
    private val equipmentRepository: EquipmentRepository
) : EquipmentUseCase {

    override fun registerEquipment(
        type: EquipmentType,
        brand: String,
        model: String,
        conditionScore: Double,
        purchaseDate: LocalDate
    ): Equipment {
        if (conditionScore !in 0.0..1.0) {
            throw BadRequestException("conditionScore must be between 0.0 and 1.0")
        }
        if (brand.isBlank() || model.isBlank()) {
            throw BadRequestException("brand and model must not be blank")
        }

        val equipment = Equipment(
            id = UUID.randomUUID(),
            type = type,
            brand = brand,
            model = model,
            state = EquipmentState.AVAILABLE,
            conditionScore = conditionScore,
            purchaseDate = purchaseDate,
            retiredReason = null
        )
        return equipmentRepository.save(equipment)
    }

    @Transactional(readOnly = true)
    override fun getEquipment(id: UUID): Equipment {
        return equipmentRepository.findById(id)
            ?: throw NotFoundException("Equipment not found with id: $id")
    }

    @Transactional(readOnly = true)
    override fun listEquipment(state: EquipmentState?): List<Equipment> {
        return if (state == null) equipmentRepository.findAll() else equipmentRepository.findByState(state)
    }

    override fun retireEquipment(id: UUID, reason: String): Equipment {
        if (reason.isBlank()) {
            throw BadRequestException("Retirement reason must not be blank")
        }

        val equipment = equipmentRepository.findById(id)
            ?: throw NotFoundException("Equipment not found with id: $id")

        if (equipment.state != EquipmentState.AVAILABLE) {
            throw ConflictException("Only available equipment can be retired")
        }

        return equipmentRepository.save(
            equipment.copy(
                state = EquipmentState.RETIRED,
                retiredReason = reason
            )
        )
    }
}
