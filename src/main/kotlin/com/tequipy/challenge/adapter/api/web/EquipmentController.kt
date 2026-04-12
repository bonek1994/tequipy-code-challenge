package com.tequipy.challenge.adapter.api.web

import com.tequipy.challenge.adapter.api.web.dto.EquipmentRequest
import com.tequipy.challenge.adapter.api.web.dto.EquipmentResponse
import com.tequipy.challenge.adapter.api.web.dto.RetireEquipmentRequest
import com.tequipy.challenge.adapter.api.web.mapper.EquipmentMapper
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.port.api.ListEquipmentUseCase
import com.tequipy.challenge.domain.port.api.RegisterEquipmentUseCase
import com.tequipy.challenge.domain.port.api.RetireEquipmentUseCase
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.media.ArraySchema
import io.swagger.v3.oas.annotations.media.Content
import io.swagger.v3.oas.annotations.media.Schema
import io.swagger.v3.oas.annotations.responses.ApiResponse
import io.swagger.v3.oas.annotations.responses.ApiResponses
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/equipments")
@Tag(name = "Equipments", description = "Manage equipment inventory")
class EquipmentController(
    private val registerEquipmentUseCase: RegisterEquipmentUseCase,
    private val listEquipmentUseCase: ListEquipmentUseCase,
    private val retireEquipmentUseCase: RetireEquipmentUseCase,
    private val equipmentMapper: EquipmentMapper
) {

    @Operation(summary = "Register new equipment")
    @ApiResponses(
        ApiResponse(responseCode = "201", description = "Equipment registered successfully",
            content = [Content(schema = Schema(implementation = EquipmentResponse::class))]),
        ApiResponse(responseCode = "400", description = "Invalid request (blank brand/model, invalid condition score)",
            content = [Content(schema = Schema(implementation = Map::class))])
    )
    @PostMapping
    fun createEquipment(@Valid @RequestBody request: EquipmentRequest): ResponseEntity<EquipmentResponse> {
        val equipment = registerEquipmentUseCase.registerEquipment(
            type = request.type,
            brand = request.brand,
            model = request.model,
            conditionScore = request.conditionScore,
            purchaseDate = request.purchaseDate
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(equipmentMapper.toResponse(equipment))
    }

    @Operation(summary = "List equipment", description = "Returns all equipment, optionally filtered by state.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "List of equipment",
            content = [Content(array = ArraySchema(schema = Schema(implementation = EquipmentResponse::class)))])
    )
    @GetMapping
    fun getAllEquipment(
        @Parameter(description = "Filter by equipment state") @RequestParam(required = false) state: EquipmentState?
    ): ResponseEntity<List<EquipmentResponse>> {
        val equipment = listEquipmentUseCase.listEquipment(state)
        return ResponseEntity.ok(equipmentMapper.toResponseList(equipment))
    }

    @Operation(summary = "Retire equipment", description = "Retires an AVAILABLE piece of equipment with a mandatory reason.")
    @ApiResponses(
        ApiResponse(responseCode = "200", description = "Equipment retired",
            content = [Content(schema = Schema(implementation = EquipmentResponse::class))]),
        ApiResponse(responseCode = "400", description = "Blank retirement reason",
            content = [Content(schema = Schema(implementation = Map::class))]),
        ApiResponse(responseCode = "404", description = "Equipment not found",
            content = [Content(schema = Schema(implementation = Map::class))]),
        ApiResponse(responseCode = "409", description = "Equipment is not in AVAILABLE state",
            content = [Content(schema = Schema(implementation = Map::class))])
    )
    @PostMapping("/{id}/retire")
    fun retireEquipment(
        @PathVariable id: UUID,
        @Valid @RequestBody request: RetireEquipmentRequest
    ): ResponseEntity<EquipmentResponse> {
        val equipment = retireEquipmentUseCase.retireEquipment(
            id = id,
            reason = request.reason
        )
        return ResponseEntity.ok(equipmentMapper.toResponse(equipment))
    }
}
