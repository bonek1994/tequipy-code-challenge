package com.tequipy.challenge.adapter.out.persistence.adapter

import com.tequipy.challenge.adapter.out.persistence.mapper.EmployeeEntityMapper
import com.tequipy.challenge.adapter.out.persistence.repository.EmployeeJpaRepository
import com.tequipy.challenge.domain.model.Employee
import com.tequipy.challenge.domain.port.out.EmployeeRepository
import org.springframework.stereotype.Component
import java.util.UUID

@Component
class EmployeePersistenceAdapter(
    private val jpaRepository: EmployeeJpaRepository,
    private val mapper: EmployeeEntityMapper
) : EmployeeRepository {

    override fun save(employee: Employee): Employee {
        val entity = mapper.toEntity(employee)
        val saved = jpaRepository.save(entity)
        return mapper.toDomain(saved)
    }

    override fun findById(id: UUID): Employee? {
        return jpaRepository.findById(id).orElse(null)?.let { mapper.toDomain(it) }
    }

    override fun findAll(): List<Employee> {
        return jpaRepository.findAll().map { mapper.toDomain(it) }
    }

    override fun deleteById(id: UUID) {
        jpaRepository.deleteById(id)
    }

    override fun existsById(id: UUID): Boolean {
        return jpaRepository.existsById(id)
    }
}
