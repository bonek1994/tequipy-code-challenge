package com.tequipy.challenge.adapter.`in`.web

import com.tequipy.challenge.adapter.`in`.web.dto.EquipmentRequest
import com.tequipy.challenge.adapter.`in`.web.dto.EquipmentResponse
import com.tequipy.challenge.adapter.`in`.web.dto.RetireEquipmentRequest
import com.tequipy.challenge.adapter.`in`.web.mapper.EquipmentMapper
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.`in`.EquipmentUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/equipments")
class EquipmentController(
    private val equipmentUseCase: EquipmentUseCase,
    private val equipmentMapper: EquipmentMapper
) {

    @PostMapping
    fun createEquipment(@Valid @RequestBody request: EquipmentRequest): ResponseEntity<EquipmentResponse> {
        val equipment = equipmentUseCase.registerEquipment(
            type = request.type,
            brand = request.brand,
            model = request.model,
            conditionScore = request.conditionScore,
            purchaseDate = request.purchaseDate
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(equipmentMapper.toResponse(equipment))
    }


    @GetMapping
    fun getAllEquipment(@RequestParam(required = false) state: EquipmentState?): ResponseEntity<List<EquipmentResponse>> {
        val equipment = equipmentUseCase.listEquipment(state)
        return ResponseEntity.ok(equipmentMapper.toResponseList(equipment))
    }

    @PostMapping("/{id}/retire")
    fun retireEquipment(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RetireEquipmentRequest
    ): ResponseEntity<EquipmentResponse> {
        val equipment = equipmentUseCase.retireEquipment(
            id = id,
            reason = request.reason
        )
        return ResponseEntity.ok(equipmentMapper.toResponse(equipment))
    }
}
