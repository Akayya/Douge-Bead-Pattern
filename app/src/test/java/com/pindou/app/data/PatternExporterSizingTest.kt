package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class PatternExporterSizingTest {

    private fun pixelsFor(width: Int, height: Int, cellPx: Int, colors: Int): Long {
        val bmpW = width.toLong() * cellPx + 2L * cellPx
        val bmpH = height.toLong() * cellPx + 4L * cellPx + 30L * colors + 18L
        return bmpW * bmpH
    }

    @Test
    fun normalSizesStayAtPreferredCellSize() {
        assertEquals(26, PatternExporter.fitCellPx(29, 29, 6))
        assertEquals(26, PatternExporter.fitCellPx(100, 100, 40))
    }

    @Test
    fun largeCanvasIsReducedToFitBudget() {
        val cell = PatternExporter.fitCellPx(240, 240, 40)
        assertNotNull(cell)
        assertEquals(11, cell)
        assertTrue(pixelsFor(240, 240, cell!!, 40) <= 12_000_000L)
    }

    @Test
    fun mediumLargeCanvasUsesFourteenPixels() {
        val cell = PatternExporter.fitCellPx(200, 200, 40)
        assertNotNull(cell)
        assertEquals(14, cell)
        assertTrue(pixelsFor(200, 200, cell!!, 40) <= 12_000_000L)
    }

    @Test
    fun extremeColorCountReturnsNull() {
        assertNull(PatternExporter.fitCellPx(240, 240, 291))
    }

    @Test
    fun everyReturnedSizeRespectsBudgetAndMinimum() {
        val cases = listOf(
            Triple(29, 29, 6),
            Triple(57, 57, 40),
            Triple(200, 200, 40),
            Triple(240, 240, 40),
            Triple(240, 100, 20),
        )
        for ((w, h, colors) in cases) {
            val cell = PatternExporter.fitCellPx(w, h, colors)
            assertNotNull(cell)
            assertTrue("cell=$cell", cell!! >= 10)
            assertTrue("cell=$cell", pixelsFor(w, h, cell, colors) <= 12_000_000L)
            assertTrue(abs(cell - 26) <= 16)
        }
    }
}
