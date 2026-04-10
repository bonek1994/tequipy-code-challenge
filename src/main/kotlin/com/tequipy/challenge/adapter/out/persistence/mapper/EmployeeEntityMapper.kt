package com.tequipy.challenge.adapter.out.persistence.mapper

import com.tequipy.challenge.adapter.out.persistence.entity.EmployeeEntity
import com.tequipy.challenge.domain.model.Employee
import org.springframework.stereotype.Component

@Component
class EmployeeEntityMapper {

    fun toDomain(entity: EmployeeEntity): Employee = Employee(
        id = entity.id,
        name = entity.name,
        email = entity.email,
        department = entity.department
    )

    fun toEntity(domain: Employee): EmployeeEntity = EmployeeEntity(
        id = domain.id,
        name = domain.name,
        email = domain.email,
        department = domain.department
    )
}
