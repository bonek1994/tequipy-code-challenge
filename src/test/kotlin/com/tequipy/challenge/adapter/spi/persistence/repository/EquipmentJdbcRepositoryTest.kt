package com.tequipy.challenge.adapter.spi.persistence.repository

import com.tequipy.challenge.adapter.spi.persistence.entity.EquipmentEntity
import com.tequipy.challenge.domain.model.EquipmentType
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.jupiter.api.Test
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper

class EquipmentJdbcRepositoryTest {

    private val jdbcTemplate: JdbcTemplate = mockk(relaxed = true)
    private val repository = EquipmentJdbcRepository(jdbcTemplate)

    @Test
    fun `findAvailableWithMinConditionScore uses explicit ordering by type and descending condition score`() {
        every {
            jdbcTemplate.query(any<String>(), any<RowMapper<EquipmentEntity>>(), *anyVararg())
        } returns emptyList()

        repository.findAvailableWithMinConditionScore(
            types = setOf(EquipmentType.KEYBOARD, EquipmentType.MONITOR),
            minScore = 0.7
        )

        verify(exactly = 1) {
            jdbcTemplate.query(
                match {
                    it.contains("ORDER BY type ASC, condition_score DESC") &&
                        it.contains("type IN (") &&
                        it.contains("condition_score >= ?")
                },
                any<RowMapper<EquipmentEntity>>(),
                *anyVararg()
            )
        }
    }
}

