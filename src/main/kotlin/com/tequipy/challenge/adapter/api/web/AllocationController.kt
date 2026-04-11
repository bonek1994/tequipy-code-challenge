package com.tequipy.challenge.adapter.api.web

import com.tequipy.challenge.adapter.api.web.dto.AllocationResponse
import com.tequipy.challenge.adapter.api.web.dto.CreateAllocationRequest
import com.tequipy.challenge.adapter.api.web.mapper.AllocationMapper
import com.tequipy.challenge.adapter.api.web.mapper.EquipmentMapper
import com.tequipy.challenge.domain.port.`in`.AllocationUseCase
import com.tequipy.challenge.domain.port.`in`.EquipmentUseCase
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/allocations")
class AllocationController(
    private val allocationUseCase: AllocationUseCase,
    private val equipmentUseCase: EquipmentUseCase,
    private val allocationMapper: AllocationMapper,
    private val equipmentMapper: EquipmentMapper
) {

    @PostMapping
    fun createAllocation(@Valid @RequestBody request: CreateAllocationRequest): ResponseEntity<AllocationResponse> {
        val allocation = allocationUseCase.createAllocation(
            policy = request.policy.map(allocationMapper::toDomain)
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(toResponse(allocation.id))
    }

    @GetMapping("/{id}")
    fun getAllocation(@PathVariable id: UUID): ResponseEntity<AllocationResponse> {
        return ResponseEntity.ok(toResponse(id))
    }

    @PostMapping("/{id}/confirm")
    fun confirmAllocation(@PathVariable id: UUID): ResponseEntity<AllocationResponse> {
        allocationUseCase.confirmAllocation(id)
        return ResponseEntity.ok(toResponse(id))
    }

    @PostMapping("/{id}/cancel")
    fun cancelAllocation(@PathVariable id: UUID): ResponseEntity<AllocationResponse> {
        allocationUseCase.cancelAllocation(id)
        return ResponseEntity.ok(toResponse(id))
    }

    private fun toResponse(id: UUID): AllocationResponse {
        val allocation = allocationUseCase.getAllocation(id)
        val equipments = allocation.allocatedEquipmentIds.map(equipmentUseCase::getEquipment).map(equipmentMapper::toResponse)
        return allocationMapper.toResponse(allocation, equipments)
    }
}

