package com.tequipy.challenge.adapter.out.persistence.repository

import com.tequipy.challenge.adapter.out.persistence.entity.AllocationRequestEntity
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface AllocationRequestJpaRepository : JpaRepository<AllocationRequestEntity, UUID>

