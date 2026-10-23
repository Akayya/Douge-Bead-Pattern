package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class PatternTest {

    private val palette = testPalette("#000000", "#FFFFFF", "#FF0000")

    private fun pattern(cells: IntArray, width: Int, height: Int, done: Set<Int> = emptySet()): Pattern =
        Pattern(
            id = "test",
            name = "测试",
            width = width,
            height = height,
            paletteId = palette.id,
            cells = cells,
            createdAt = 0,
            updatedAt = 0,
            done = done.toMutableSet(),
        )

    @Test
    fun usageIsSortedByCountDescending() {
        val pattern = pattern(intArrayOf(0, 0, 1, 2, 2, 2, -1), 7, 1)
        val usage = pattern.usage(palette)

        assertEquals(3, usage.size)
        assertEquals("T2", usage[0].color.code)
        assertEquals(3, usage[0].count)
        assertEquals("T0", usage[1].color.code)
        assertEquals(2, usage[1].count)
        assertEquals("T1", usage[2].color.code)
        assertEquals(1, usage[2].count)
    }

    @Test
    fun doneCountIgnoresEmptyAndOutOfRangeCells() {
        val pattern = pattern(
            cells = intArrayOf(0, -1, 1, 0),
            width = 2,
            height = 2,
            done = setOf(0, 1, 2, 5),
        )

        assertEquals(3, pattern.beadCount)
        assertEquals(2, pattern.doneCount)
        assertEquals(2f / 3f, pattern.progress, 0.0001f)
    }

    @Test
    fun emptyPatternHasZeroProgress() {
        val pattern = pattern(intArrayOf(-1, -1, -1, -1), 2, 2, setOf(0))

        assertEquals(0, pattern.beadCount)
        assertEquals(0, pattern.doneCount)
        assertEquals(0f, pattern.progress, 0.0001f)
    }

    @Test
    fun rejectsMismatchedCellCount() {
        assertThrows(IllegalArgumentException::class.java) {
            pattern(intArrayOf(0, 1, 2), 2, 2)
        }
    }
}
