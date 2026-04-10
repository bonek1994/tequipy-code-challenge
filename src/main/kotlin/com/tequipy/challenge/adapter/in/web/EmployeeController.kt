package com.tequipy.challenge.adapter.`in`.web

import com.tequipy.challenge.adapter.`in`.web.dto.EmployeeRequest
import com.tequipy.challenge.adapter.`in`.web.dto.EmployeeResponse
import com.tequipy.challenge.adapter.`in`.web.mapper.EmployeeMapper
import com.tequipy.challenge.domain.port.`in`.EmployeeUseCase
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.*
import java.util.UUID

@RestController
@RequestMapping("/api/employees")
class EmployeeController(
    private val employeeUseCase: EmployeeUseCase,
    private val employeeMapper: EmployeeMapper
) {

    @PostMapping
    fun createEmployee(@RequestBody request: EmployeeRequest): ResponseEntity<EmployeeResponse> {
        val employee = employeeUseCase.createEmployee(
            name = request.name,
            email = request.email,
            department = request.department
        )
        return ResponseEntity.status(HttpStatus.CREATED).body(employeeMapper.toResponse(employee))
    }

    @GetMapping("/{id}")
    fun getEmployee(@PathVariable id: UUID): ResponseEntity<EmployeeResponse> {
        val employee = employeeUseCase.getEmployee(id)
        return ResponseEntity.ok(employeeMapper.toResponse(employee))
    }

    @GetMapping
    fun getAllEmployees(): ResponseEntity<List<EmployeeResponse>> {
        val employees = employeeUseCase.getAllEmployees()
        return ResponseEntity.ok(employeeMapper.toResponseList(employees))
    }

    @PutMapping("/{id}")
    fun updateEmployee(
        @PathVariable id: UUID,
        @RequestBody request: EmployeeRequest
    ): ResponseEntity<EmployeeResponse> {
        val employee = employeeUseCase.updateEmployee(
            id = id,
            name = request.name,
            email = request.email,
            department = request.department
        )
        return ResponseEntity.ok(employeeMapper.toResponse(employee))
    }

    @DeleteMapping("/{id}")
    fun deleteEmployee(@PathVariable id: UUID): ResponseEntity<Void> {
        employeeUseCase.deleteEmployee(id)
        return ResponseEntity.noContent().build()
    }
}
