package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.command.RetireEquipmentCommand
import com.tequipy.challenge.domain.model.Equipment

interface RetireEquipmentUseCase {
    fun retireEquipment(command: RetireEquipmentCommand): Equipment
}

