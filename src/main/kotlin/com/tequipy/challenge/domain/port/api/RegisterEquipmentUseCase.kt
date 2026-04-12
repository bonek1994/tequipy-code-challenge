package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.command.RegisterEquipmentCommand
import com.tequipy.challenge.domain.model.Equipment

interface RegisterEquipmentUseCase {
    fun registerEquipment(command: RegisterEquipmentCommand): Equipment
}

