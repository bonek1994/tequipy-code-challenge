package com.tequipy.challenge.domain.port.out

import com.tequipy.challenge.domain.model.Employee
import java.util.UUID

interface EmployeeRepository {
    fun save(employee: Employee): Employee
    fun findById(id: UUID): Employee?
    fun findAll(): List<Employee>
    fun deleteById(id: UUID)
    fun existsById(id: UUID): Boolean
}
