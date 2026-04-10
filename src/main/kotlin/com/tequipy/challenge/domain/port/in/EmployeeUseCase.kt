package com.tequipy.challenge.domain.port.`in`

import com.tequipy.challenge.domain.model.Employee
import java.util.UUID

interface EmployeeUseCase {
    fun createEmployee(name: String, email: String, department: String): Employee
    fun getEmployee(id: UUID): Employee
    fun getAllEmployees(): List<Employee>
    fun updateEmployee(id: UUID, name: String, email: String, department: String): Employee
    fun deleteEmployee(id: UUID)
}
