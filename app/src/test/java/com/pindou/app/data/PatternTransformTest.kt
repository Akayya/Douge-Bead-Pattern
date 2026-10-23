package com.pindou.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternTransformTest {

    @Test
    fun trimBlankRemovesEmptyRowsAndColumns() {
        val cells = intArrayOf(
            -1, -1, -1, -1,
            -1, 1, 2, -1,
            -1, 3, 4, -1,
            -1, -1, -1, -1,
        )
        val result = PatternTransform.trimBlank(cells, 4, 4)

        assertEquals(2, result.width)
        assertEquals(2, result.height)
        assertArrayEquals(intArrayOf(1, 2, 3, 4), result.cells)
        assertTrue(result.changed)
    }

    @Test
    fun trimBlankKeepsAlreadyTightCanvas() {
        val cells = intArrayOf(1, 2, 3, 4)
        val result = PatternTransform.trimBlank(cells, 2, 2)

        assertFalse(result.changed)
        assertEquals(2, result.width)
        assertEquals(2, result.height)
    }

    @Test
    fun trimBlankIgnoresEmptyPattern() {
        val cells = IntArray(6) { -1 }
        val result = PatternTransform.trimBlank(cells, 3, 2)

        assertFalse(result.changed)
        assertNull(result.label)
        assertEquals(3, result.width)
    }

    @Test
    fun padAddsOneEmptyCellAround() {
        val result = PatternTransform.pad(intArrayOf(1, 2, 3, 4), 2, 2)

        assertEquals(4, result.width)
        assertEquals(4, result.height)
        assertEquals(-1, result.cells[0])
        assertEquals(1, result.cells[5])
        assertEquals(2, result.cells[6])
        assertEquals(3, result.cells[9])
        assertEquals(4, result.cells[10])
    }

    @Test
    fun rotateCwSwapsWidthAndHeight() {
        val result = PatternTransform.rotateCw(
            cells = intArrayOf(
                1, 2, 3,
                4, 5, 6,
            ),
            width = 3,
            height = 2,
        )

        assertEquals(2, result.width)
        assertEquals(3, result.height)
        assertArrayEquals(intArrayOf(4, 1, 5, 2, 6, 3), result.cells)
    }

    @Test
    fun flipHorizontalMirrorsEachRow() {
        val result = PatternTransform.flipHorizontal(
            cells = intArrayOf(
                1, 2, 3,
                4, 5, 6,
            ),
            width = 3,
            height = 2,
        )

        assertArrayEquals(intArrayOf(3, 2, 1, 6, 5, 4), result.cells)
    }
}
