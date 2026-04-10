package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.Employee
import com.tequipy.challenge.domain.port.`in`.EmployeeUseCase
import com.tequipy.challenge.domain.port.out.EmployeeRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class EmployeeService(private val employeeRepository: EmployeeRepository) : EmployeeUseCase {

    override fun createEmployee(name: String, email: String, department: String): Employee {
        val employee = Employee(
            id = UUID.randomUUID(),
            name = name,
            email = email,
            department = department
        )
        return employeeRepository.save(employee)
    }

    @Transactional(readOnly = true)
    override fun getEmployee(id: UUID): Employee {
        return employeeRepository.findById(id)
            ?: throw NotFoundException("Employee not found with id: $id")
    }

    @Transactional(readOnly = true)
    override fun getAllEmployees(): List<Employee> {
        return employeeRepository.findAll()
    }

    override fun updateEmployee(id: UUID, name: String, email: String, department: String): Employee {
        val existing = employeeRepository.findById(id)
            ?: throw NotFoundException("Employee not found with id: $id")
        val updated = existing.copy(name = name, email = email, department = department)
        return employeeRepository.save(updated)
    }

    override fun deleteEmployee(id: UUID) {
        if (!employeeRepository.existsById(id)) {
            throw NotFoundException("Employee not found with id: $id")
        }
        employeeRepository.deleteById(id)
    }
}
