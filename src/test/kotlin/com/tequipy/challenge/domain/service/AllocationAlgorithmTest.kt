package com.tequipy.challenge.domain.service

import com.tequipy.challenge.domain.model.Equipment
import com.tequipy.challenge.domain.model.EquipmentPolicyRequirement
import com.tequipy.challenge.domain.model.EquipmentState
import com.tequipy.challenge.domain.model.EquipmentType
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import java.time.LocalDate
import java.util.UUID

class AllocationAlgorithmTest {
    private val algorithm = AllocationAlgorithm()

    @Test
    fun `allocate should return empty list for empty policy`() {
        // given
        val availableEquipment = listOf(equipment(type = EquipmentType.MONITOR, condition = 0.8))

        // when
        val result = algorithm.allocate(
            policy = emptyList(),
            availableEquipment = availableEquipment
        )

        // then
        assertNotNull(result)
        assertTrue(result!!.isEmpty())
    }

    @Test
    fun `allocate should satisfy hard constraints and prefer matching brand`() {
        // given
        val apple = equipment(brand = "Apple", model = "MacBook Pro", type = EquipmentType.MAIN_COMPUTER, condition = 0.95)
        val dell = equipment(brand = "Dell", model = "Latitude", type = EquipmentType.MAIN_COMPUTER, condition = 0.96)

        // when
        val result = algorithm.allocate(
            policy = listOf(
                EquipmentPolicyRequirement(
                    type = EquipmentType.MAIN_COMPUTER,
                    minimumConditionScore = 0.8,
                    preferredBrand = "Apple"
                )
            ),
            availableEquipment = listOf(apple, dell)
        )

        // then
        assertNotNull(result)
        assertEquals(listOf(apple.id), result!!.map { it.id })
    }

    @Test
    fun `allocate should return null when hard constraints cannot be met`() {
        // given
        val monitor = equipment(type = EquipmentType.MONITOR, condition = 0.5)

        // when
        val result = algorithm.allocate(
            policy = listOf(
                EquipmentPolicyRequirement(
                    type = EquipmentType.MONITOR,
                    minimumConditionScore = 0.8
                )
            ),
            availableEquipment = listOf(monitor)
        )

        // then
        assertNull(result)
    }

    @Test
    fun `allocate should use globally feasible combination for competing slots`() {
        // given
        val strongMonitor = equipment(type = EquipmentType.MONITOR, brand = "Dell", model = "Strong", condition = 0.95)
        val mediumMonitor = equipment(type = EquipmentType.MONITOR, brand = "LG", model = "Medium", condition = 0.75)
        val weakMonitor = equipment(type = EquipmentType.MONITOR, brand = "AOC", model = "Weak", condition = 0.65)

        // when
        val result = algorithm.allocate(
            policy = listOf(
                EquipmentPolicyRequirement(type = EquipmentType.MONITOR, minimumConditionScore = 0.9),
                EquipmentPolicyRequirement(type = EquipmentType.MONITOR, minimumConditionScore = 0.7)
            ),
            availableEquipment = listOf(strongMonitor, mediumMonitor, weakMonitor)
        )

        // then
        assertNotNull(result)
        val allocatedIds = result!!.map { it.id }.toSet()
        assertTrue(allocatedIds.contains(strongMonitor.id))
        assertTrue(allocatedIds.contains(mediumMonitor.id))
        assertFalse(allocatedIds.contains(weakMonitor.id))
    }

    @Test
    fun `allocate should ignore equipment that is not available`() {
        // given
        val reservedMonitor = equipment(type = EquipmentType.MONITOR, condition = 0.95).copy(state = EquipmentState.RESERVED)

        // when
        val result = algorithm.allocate(
            policy = listOf(EquipmentPolicyRequirement(type = EquipmentType.MONITOR, minimumConditionScore = 0.8)),
            availableEquipment = listOf(reservedMonitor)
        )

        // then
        assertNull(result)
    }

    @Test
    fun `allocate should satisfy quantity using distinct equipment items`() {
        // given
        val firstMonitor = equipment(type = EquipmentType.MONITOR, brand = "Dell", model = "One", condition = 0.91)
        val secondMonitor = equipment(type = EquipmentType.MONITOR, brand = "LG", model = "Two", condition = 0.89)

        // when
        val result = algorithm.allocate(
            policy = listOf(EquipmentPolicyRequirement(type = EquipmentType.MONITOR, quantity = 2, minimumConditionScore = 0.8)),
            availableEquipment = listOf(firstMonitor, secondMonitor)
        )

        // then
        assertNotNull(result)
        assertEquals(2, result!!.size)
        assertEquals(2, result.map { it.id }.toSet().size)
    }

    @Test
    fun `allocate should handle all equipment with same purchase date without error`() {
        // given - both have identical scores; algorithm must still pick one
        val monitor1 = equipment(type = EquipmentType.MONITOR, condition = 0.8, purchaseDate = LocalDate.of(2023, 6, 1))
        val monitor2 = equipment(type = EquipmentType.MONITOR, condition = 0.8, purchaseDate = LocalDate.of(2023, 6, 1))

        // when
        val result = algorithm.allocate(
            policy = listOf(EquipmentPolicyRequirement(type = EquipmentType.MONITOR)),
            availableEquipment = listOf(monitor1, monitor2)
        )

        // then
        assertNotNull(result)
        assertEquals(1, result!!.size)
    }

    @Test
    fun `allocate should prefer newer equipment when condition scores are equal`() {
        // given
        val olderMonitor = equipment(type = EquipmentType.MONITOR, condition = 0.8, purchaseDate = LocalDate.of(2020, 1, 1))
        val newerMonitor = equipment(type = EquipmentType.MONITOR, condition = 0.8, purchaseDate = LocalDate.of(2024, 1, 1))

        // when
        val result = algorithm.allocate(
            policy = listOf(EquipmentPolicyRequirement(type = EquipmentType.MONITOR)),
            availableEquipment = listOf(olderMonitor, newerMonitor)
        )

        // then
        assertNotNull(result)
        assertEquals(listOf(newerMonitor.id), result!!.map { it.id })
    }

    @Test
    fun `allocate should match preferred brand case insensitively`() {
        // given
        val apple = equipment(brand = "Apple", model = "MacBook Pro", type = EquipmentType.MAIN_COMPUTER, condition = 0.9)
        val dell = equipment(brand = "Dell", model = "Latitude", type = EquipmentType.MAIN_COMPUTER, condition = 0.95)

        // when
        val result = algorithm.allocate(
            policy = listOf(
                EquipmentPolicyRequirement(
                    type = EquipmentType.MAIN_COMPUTER,
                    preferredBrand = "apple"
                )
            ),
            availableEquipment = listOf(apple, dell)
        )

        // then
        assertNotNull(result)
        assertEquals(listOf(apple.id), result!!.map { it.id })
    }

    private fun equipment(
        id: UUID = UUID.randomUUID(),
        type: EquipmentType,
        brand: String = "Dell",
        model: String = "Model",
        condition: Double,
        purchaseDate: LocalDate = LocalDate.of(2025, 1, 1)
    ) = Equipment(
        id = id,
        type = type,
        brand = brand,
        model = model,
        state = EquipmentState.AVAILABLE,
        conditionScore = condition,
        purchaseDate = purchaseDate,
        retiredReason = null
    )
}
