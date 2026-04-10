package com.tequipy.challenge.adapter.out.persistence.repository

import com.tequipy.challenge.adapter.out.persistence.entity.EmployeeEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface EmployeeJpaRepository : JpaRepository<EmployeeEntity, UUID>
