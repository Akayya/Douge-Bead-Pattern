package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryDeductionTest {

    private val palette = testPalette("#111111", "#222222", "#333333")

    private fun usage(vararg counts: Int): List<ColorUsage> =
        counts.mapIndexed { i, count -> ColorUsage(palette.colors[i], count) }

    @Test
    fun plan_deductsOnlyTrackedColors() {
        val plan = InventoryDeduction.plan(usage(120, 30, 5)) { code ->
            when (code) {
                "T0" -> 200
                "T1" -> 30
                else -> null
            }
        }

        assertEquals(listOf("T0", "T1"), plan.lines.map { it.code })
        assertEquals(150, plan.neededBeads)
        assertEquals(150, plan.deductedBeads)
        assertEquals(1, plan.untrackedColors)
        assertEquals(5, plan.untrackedBeads)
        assertEquals(mapOf("T0" to 80, "T1" to 0), plan.after)
        assertEquals(0, plan.shortColors)
    }

    @Test
    fun plan_clampsShortageAndNeverGoesNegative() {
        val plan = InventoryDeduction.plan(usage(123, 50)) { code ->
            if (code == "T0") 100 else 10
        }

        val first = plan.lines[0]
        assertEquals(100, first.stock)
        assertEquals(0, first.after)
        assertEquals(23, first.shortage)
        val second = plan.lines[1]
        assertEquals(0, second.after)
        assertEquals(40, second.shortage)
        assertEquals(2, plan.shortColors)
        assertEquals(63, plan.shortBeads)
        assertEquals(110, plan.deductedBeads)
    }

    @Test
    fun plan_allUntrackedYieldsNoDeduction() {
        val plan = InventoryDeduction.plan(usage(12, 7)) { null }

        assertTrue(plan.lines.isEmpty())
        assertEquals(2, plan.untrackedColors)
        assertEquals(19, plan.untrackedBeads)
        assertTrue(plan.after.isEmpty())
    }

    @Test
    fun plan_zeroStockIsStillTracked() {
        val plan = InventoryDeduction.plan(usage(9)) { 0 }

        assertEquals(1, plan.trackedColors)
        assertEquals(0, plan.lines[0].after)
        assertEquals(9, plan.shortBeads)
    }

    @Test
    fun plan_skipsZeroUsage() {
        val plan = InventoryDeduction.plan(usage(0, 3)) { 10 }

        assertEquals(1, plan.lines.size)
        assertEquals("T1", plan.lines[0].code)
        assertEquals(7, plan.after["T1"])
    }
}
