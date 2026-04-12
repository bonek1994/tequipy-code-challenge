package com.tequipy.challenge.domain.port.api

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentState

interface ListEquipmentUseCase {
    fun listEquipment(state: EquipmentState?): List<Equipment>
}

