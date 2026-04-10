package com.tequipy.challenge.adapter.`in`.web

import com.tequipy.challenge.adapter.`in`.web.dto.EquipmentRequest
import com.tequipy.challenge.adapter.`in`.web.dto.EquipmentResponse
import com.tequipy.challenge.adapter.`in`.web.mapper.EquipmentMapper
import com.tequipy.challenge.domain.port.`in`.EquipmentUseCase
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/equipment")
class EquipmentController(
    private val equipmentUseCase: EquipmentUseCase,
    private val equipmentMapper: EquipmentMapper
) {

    @PostMapping
    fun createEquipment(@RequestBody request: EquipmentRequest): ResponseEntity<EquipmentResponse> {
        val equipment = equipmentUseCase.createEquipment(
            name = request.name,
            serialNumber = request.serialNumber
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(equipmentMapper.toResponse(equipment))
    }

    @GetMapping("/{id}")
    fun getEquipment(@PathVariable id: UUID): ResponseEntity<EquipmentResponse> {
        val equipment = equipmentUseCase.getEquipment(id)
        return ResponseEntity.ok(equipmentMapper.toResponse(equipment))
    }

    @GetMapping
    fun getAllEquipment(): ResponseEntity<List<EquipmentResponse>> {
        val equipment = equipmentUseCase.getAllEquipment()
        return ResponseEntity.ok(equipmentMapper.toResponseList(equipment))
    }

    @PutMapping("/{id}")
    fun updateEquipment(
        @PathVariable id: UUID,
        @RequestBody request: EquipmentRequest
    ): ResponseEntity<EquipmentResponse> {
        val equipment = equipmentUseCase.updateEquipment(
            id = id,
            name = request.name,
            serialNumber = request.serialNumber
        )
        return ResponseEntity.ok(equipmentMapper.toResponse(equipment))
    }

    @DeleteMapping("/{id}")
    fun deleteEquipment(@PathVariable id: UUID): ResponseEntity<Void> {
        equipmentUseCase.deleteEquipment(id)
        return ResponseEntity.noContent().build()
    }

    @PutMapping("/{id}/assign/{employeeId}")
    fun assignEquipment(
        @PathVariable id: UUID,
        @PathVariable employeeId: UUID
    ): ResponseEntity<EquipmentResponse> {
        val equipment = equipmentUseCase.assignEquipment(id, employeeId)
        return ResponseEntity.ok(equipmentMapper.toResponse(equipment))
    }

    @PutMapping("/{id}/unassign")
    fun unassignEquipment(@PathVariable id: UUID): ResponseEntity<EquipmentResponse> {
        val equipment = equipmentUseCase.unassignEquipment(id)
        return ResponseEntity.ok(equipmentMapper.toResponse(equipment))
    }
}
