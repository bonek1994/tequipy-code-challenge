package com.tequipy.challenge.adapter.`in`.web.mapper

import com.tequipy.challenge.adapter.`in`.web.dto.EmployeeRequest
import com.tequipy.challenge.adapter.`in`.web.dto.EmployeeResponse
import com.tequipy.challenge.domain.model.Employee
import org.springframework.stereotype.Component

@Component
class EmployeeMapper {

    fun toResponse(employee: Employee): EmployeeResponse = EmployeeResponse(
        id = employee.id,
        name = employee.name,
        email = employee.email,
        department = employee.department
    )

    fun toResponseList(employees: List<Employee>): List<EmployeeResponse> =
        employees.map { toResponse(it) }
}
