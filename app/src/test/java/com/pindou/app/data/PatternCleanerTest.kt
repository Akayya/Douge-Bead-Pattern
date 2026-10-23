package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PatternCleanerTest {

    private val palette = testPalette(
        "#FFFFFF", // 0 白
        "#000000", // 1 黑
        "#CCCCCC", // 2 白黑过渡灰
        "#F8F8F8", // 3 接近白的压缩噪点
    )

    @Test
    fun removesNearlyWhitePixel() {
        val cells = intArrayOf(
            0, 0, 0,
            0, 3, 0,
            0, 0, 0,
        )
        val result = PatternCleaner.despeckle(cells, 3, 3, palette)

        assertEquals(1, result.changed)
        assertEquals(0, result.cells[4])
    }

    @Test
    fun removesColorBlendedBetweenTwoNeighbors() {
        val cells = intArrayOf(
            0, 0, 0,
            0, 2, 0,
            0, 1, 1,
        )
        val result = PatternCleaner.despeckle(cells, 3, 3, palette)

        assertEquals(1, result.changed)
        assertEquals(0, result.cells[4])
    }

    @Test
    fun keepsIntentionalBlackDotOnWhite() {
        val cells = intArrayOf(
            0, 0, 0,
            0, 1, 0,
            0, 0, 0,
        )
        val result = PatternCleaner.despeckle(cells, 3, 3, palette)

        assertEquals(0, result.changed)
        assertEquals(1, result.cells[4])
    }

    @Test
    fun invalidCanvasReturnsCopyWithoutChanges() {
        val cells = intArrayOf(0, 1, 1, 0)
        val result = PatternCleaner.despeckle(cells, 2, 2, palette)

        assertEquals(0, result.changed)
        assertEquals(0, result.passes)
        assertEquals(cells.toList(), result.cells.toList())
    }

    @Test
    fun isolationCountReportsOnlyLonelyFilledCell() {
        val cells = intArrayOf(
            0, 0, 0,
            0, 1, 0,
            0, 0, 0,
        )

        assertEquals(1, PatternCleaner.isolationCount(cells, 3, 3))
    }
}
