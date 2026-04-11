package com.tequipy.challenge.adapter.api.web.dto

import jakarta.validation.constraints.NotBlank

data class RetireEquipmentRequest(
    @field:NotBlank
    val reason: String
)

