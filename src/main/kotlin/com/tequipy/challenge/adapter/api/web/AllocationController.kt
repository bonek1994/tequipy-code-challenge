package com.tequipy.challenge.adapter.api.web

import com.tequipy.challenge.adapter.api.web.dto.AllocationResponse
import com.tequipy.challenge.adapter.api.web.dto.CreateAllocationRequest
import com.tequipy.challenge.adapter.api.web.mapper.AllocationMapper
import com.tequipy.challenge.adapter.api.web.mapper.EquipmentMapper
import com.tequipy.challenge.domain.port.api.CancelAllocationUseCase
import com.tequipy.challenge.domain.port.api.ConfirmAllocationUseCase
import com.tequipy.challenge.domain.port.api.CreateAllocationUseCase
import com.tequipy.challenge.domain.port.api.GetAllocationUseCase
import com.tequipy.challenge.domain.port.api.GetEquipmentUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
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
@Tag(name = "Allocations", description = "Manage equipment allocation requests")
class AllocationController(
    private val createAllocationUseCase: CreateAllocationUseCase,
    private val getAllocationUseCase: GetAllocationUseCase,
    private val confirmAllocationUseCase: ConfirmAllocationUseCase,
    private val cancelAllocationUseCase: CancelAllocationUseCase,
    private val getEquipmentUseCase: GetEquipmentUseCase,
    private val allocationMapper: AllocationMapper,
    private val equipmentMapper: EquipmentMapper
) {

    @Operation(
        summary = "Create an allocation request",
        description = "Submits a new allocation request. Processing is asynchronous via RabbitMQ — " +
            "the returned allocation starts in PENDING state and transitions to ALLOCATED or FAILED once processed."
    )
    @ApiResponses(
        ApiResponse(responseCode = "202", description = "Allocation request accepted and queued for async processing",
            content = [Content(schema = Schema(implementation = AllocationResponse::class))]),
        ApiResponse(responseCode = "400", description = "Invalid allocation policy (empty policy, quantity ≤ 0, or invalid condition score)",
            content = [Content(schema = Schema(implementation = Map::class))])
    )
    @PostMapping
    fun createAllocation(@Valid @RequestBody request: CreateAllocationRequest): ResponseEntity<AllocationResponse> {
        val allocation = createAllocationUseCase.createAllocation(
            policy = request.policy.map(allocationMapper::toDomain)
        )
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(toResponse(allocation.id))
    }

    @Operation(summary = "Get an allocation by ID")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Allocation found",
            content = [Content(schema = Schema(implementation = AllocationResponse::class))]),
        ApiResponse(responseCode = "404", description = "Allocation not found",
            content = [Content(schema = Schema(implementation = Map::class))])
    )
    @GetMapping("/{id}")
    fun getAllocation(@PathVariable id: UUID): ResponseEntity<AllocationResponse> {
        return ResponseEntity.ok(toResponse(id))
    }

    @Operation(
        summary = "Confirm an allocation",
        description = "Confirms an ALLOCATED request, moving all reserved equipment to ASSIGNED state."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Allocation confirmed",
            content = [Content(schema = Schema(implementation = AllocationResponse::class))]),
        ApiResponse(responseCode = "404", description = "Allocation not found",
            content = [Content(schema = Schema(implementation = Map::class))]),
        ApiResponse(responseCode = "409", description = "Allocation is not in ALLOCATED state",
            content = [Content(schema = Schema(implementation = Map::class))])
    )
    @PostMapping("/{id}/confirm")
    fun confirmAllocation(@PathVariable id: UUID): ResponseEntity<AllocationResponse> {
        confirmAllocationUseCase.confirmAllocation(id)
        return ResponseEntity.ok(toResponse(id))
    }

    @Operation(
        summary = "Cancel an allocation",
        description = "Cancels a PENDING, ALLOCATED, or FAILED allocation. Reserved equipment is released back to AVAILABLE."
    )
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Allocation cancelled",
            content = [Content(schema = Schema(implementation = AllocationResponse::class))]),
        ApiResponse(responseCode = "404", description = "Allocation not found",
            content = [Content(schema = Schema(implementation = Map::class))]),
        ApiResponse(responseCode = "409", description = "Allocation cannot be cancelled in its current state",
            content = [Content(schema = Schema(implementation = Map::class))])
    )
    @PostMapping("/{id}/cancel")
    fun cancelAllocation(@PathVariable id: UUID): ResponseEntity<AllocationResponse> {
        cancelAllocationUseCase.cancelAllocation(id)
        return ResponseEntity.ok(toResponse(id))
    }

    private fun toResponse(id: UUID): AllocationResponse {
        val allocation = getAllocationUseCase.getAllocation(id)
        val equipments = allocation.allocatedEquipmentIds.map(getEquipmentUseCase::getEquipment).map(equipmentMapper::toResponse)
        return allocationMapper.toResponse(allocation, equipments)
    }
}
