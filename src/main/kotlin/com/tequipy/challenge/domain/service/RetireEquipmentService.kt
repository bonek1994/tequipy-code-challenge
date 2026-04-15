package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.ConflictException
import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.command.RetireEquipmentCommand
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.api.RetireEquipmentUseCase
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class RetireEquipmentService(
    private val equipmentRepository: EquipmentRepository
) : RetireEquipmentUseCase {

    private val logger = KotlinLogging.logger {}

    override fun retireEquipment(command: RetireEquipmentCommand): Equipment {
        if (command.reason.isBlank()) {
            throw BadRequestException("Retirement reason must not be blank")
        }

        val equipment = equipmentRepository.findById(command.id)
            ?: throw NotFoundException("Equipment not found with id: ${command.id}")

        if (equipment.state != EquipmentState.AVAILABLE) {
            throw ConflictException("Only available equipment can be retired")
        }

        logger.info { "Retiring equipment: id=${command.id}" }
        val retired = equipmentRepository.update(
            equipment.copy(
                state = EquipmentState.RETIRED,
                retiredReason = command.reason
            )
        )
        logger.info { "Equipment retired: id=${retired.id}" }
        return retired
    }
}

