package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.BadRequestException
import com.tequipy.challenge.domain.command.RegisterEquipmentCommand
import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.api.RegisterEquipmentUseCase
import com.tequipy.challenge.domain.port.spi.EquipmentRepository
import io.github.oshai.kotlinlogging.KotlinLogging
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class RegisterEquipmentService(
    private val equipmentRepository: EquipmentRepository
) : RegisterEquipmentUseCase {

    private val logger = KotlinLogging.logger {}

    override fun registerEquipment(command: RegisterEquipmentCommand): Equipment {
        if (command.conditionScore !in 0.0..1.0) {
            throw BadRequestException("conditionScore must be between 0.0 and 1.0")
        }
        if (command.brand.isBlank() || command.model.isBlank()) {
            throw BadRequestException("brand and model must not be blank")
        }

        logger.info { "Registering equipment: type=${command.type}, brand=${command.brand}, model=${command.model}" }
        val equipment = Equipment(
            id = UUID.randomUUID(),
            type = command.type,
            brand = command.brand,
            model = command.model,
            state = EquipmentState.AVAILABLE,
            conditionScore = command.conditionScore,
            purchaseDate = command.purchaseDate,
            retiredReason = null
        )
        val saved = equipmentRepository.save(equipment)
        logger.info { "Equipment registered: id=${saved.id}" }
        return saved
    }
}

