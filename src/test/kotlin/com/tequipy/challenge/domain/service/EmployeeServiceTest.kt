package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.NotFoundException
import com.tequipy.challenge.domain.model.Employee
import com.tequipy.challenge.domain.port.out.EmployeeRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.util.UUID

class EmployeeServiceTest {

    private val employeeRepository: EmployeeRepository = mockk()
    private val employeeService = EmployeeService(employeeRepository)

    @Test
    fun `createEmployee should save and return employee`() {
        val name = "John Doe"
        val email = "john@example.com"
        val department = "Engineering"
        val saved = Employee(UUID.randomUUID(), name, email, department)

        every { employeeRepository.save(any()) } returns saved

        val result = employeeService.createEmployee(name, email, department)

        assertEquals(saved, result)
        verify { employeeRepository.save(any()) }
    }

    @Test
    fun `getEmployee should return employee when found`() {
        val id = UUID.randomUUID()
        val employee = Employee(id, "Jane Doe", "jane@example.com", "HR")

        every { employeeRepository.findById(id) } returns employee

        val result = employeeService.getEmployee(id)

        assertEquals(employee, result)
    }

    @Test
    fun `getEmployee should throw NotFoundException when not found`() {
        val id = UUID.randomUUID()

        every { employeeRepository.findById(id) } returns null

        assertThrows<NotFoundException> {
            employeeService.getEmployee(id)
        }
    }

    @Test
    fun `getAllEmployees should return all employees`() {
        val employees = listOf(
            Employee(UUID.randomUUID(), "Alice", "alice@example.com", "IT"),
            Employee(UUID.randomUUID(), "Bob", "bob@example.com", "Finance")
        )

        every { employeeRepository.findAll() } returns employees

        val result = employeeService.getAllEmployees()

        assertEquals(2, result.size)
        assertEquals(employees, result)
    }

    @Test
    fun `updateEmployee should update and return employee when found`() {
        val id = UUID.randomUUID()
        val existing = Employee(id, "Old Name", "old@example.com", "OldDept")
        val updated = existing.copy(name = "New Name", email = "new@example.com", department = "NewDept")

        every { employeeRepository.findById(id) } returns existing
        every { employeeRepository.save(updated) } returns updated

        val result = employeeService.updateEmployee(id, "New Name", "new@example.com", "NewDept")

        assertEquals(updated, result)
        verify { employeeRepository.save(updated) }
    }

    @Test
    fun `updateEmployee should throw NotFoundException when not found`() {
        val id = UUID.randomUUID()

        every { employeeRepository.findById(id) } returns null

        assertThrows<NotFoundException> {
            employeeService.updateEmployee(id, "Name", "email@example.com", "Dept")
        }
    }

    @Test
    fun `deleteEmployee should delete when employee exists`() {
        val id = UUID.randomUUID()

        every { employeeRepository.existsById(id) } returns true
        every { employeeRepository.deleteById(id) } returns Unit

        assertDoesNotThrow {
            employeeService.deleteEmployee(id)
        }

        verify { employeeRepository.deleteById(id) }
    }

    @Test
    fun `deleteEmployee should throw NotFoundException when not found`() {
        val id = UUID.randomUUID()

        every { employeeRepository.existsById(id) } returns false

        assertThrows<NotFoundException> {
            employeeService.deleteEmployee(id)
        }
    }
}
