package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.model.Equipment
import java.util.UUID

interface RetireEquipmentUseCase {
    fun retireEquipment(id: UUID, reason: String): Equipment
}

