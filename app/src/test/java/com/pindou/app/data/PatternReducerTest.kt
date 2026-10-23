package com.pindou.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PatternReducerTest {

    private val palette = testPalette("#000000", "#FFFFFF", "#FF0000")

    @Test
    fun keepsTopColorsAndMapsDroppedColorToNearest() {
        // 黑 3 格、白 2 格、红 4 格：保留红和黑，白映射到更近的红。
        val cells = intArrayOf(0, 0, 0, 1, 1, 2, 2, 2, 2)
        val result = PatternReducer.reduce(cells, 3, 3, palette, targetColors = 2)

        assertEquals(2, result.colorCount)
        assertEquals(1, result.mergedColors)
        assertEquals(2, result.changed)
        assertArrayEquals(intArrayOf(0, 0, 0, 2, 2, 2, 2, 2, 2), result.cells)
    }

    @Test
    fun returnsCopyWhenAlreadyWithinTarget() {
        val cells = intArrayOf(0, 1, 2, -1)
        val result = PatternReducer.reduce(cells, 2, 2, palette, targetColors = 5)

        assertEquals(3, result.colorCount)
        assertEquals(0, result.changed)
        assertEquals(0, result.mergedColors)
        assertArrayEquals(cells, result.cells)
    }

    @Test
    fun keepsEmptyCellsEmpty() {
        val cells = intArrayOf(-1, -1, -1, -1)
        val result = PatternReducer.reduce(cells, 2, 2, palette, targetColors = 1)

        assertEquals(0, result.colorCount)
        assertEquals(0, result.changed)
        assertArrayEquals(cells, result.cells)
    }

    @Test
    fun reducesToSingleColor() {
        val cells = intArrayOf(0, 1, 2, 2)
        val result = PatternReducer.reduce(cells, 2, 2, palette, targetColors = 1)

        assertEquals(1, result.colorCount)
        // 3 个原始色号里只有“2”保留，另外 2 个被并过去
        assertEquals(2, result.mergedColors)
        assertEquals(2, result.changed)
        assertArrayEquals(IntArray(4) { 2 }, result.cells)
    }
}
