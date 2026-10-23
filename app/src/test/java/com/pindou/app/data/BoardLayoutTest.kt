package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BoardLayoutTest {

    @Test
    fun countAndColumns_useCeilPerBoard() {
        assertEquals(2, BoardLayout.columns(47, 29))
        assertEquals(2, BoardLayout.rows(50, 29))
        assertEquals(4, BoardLayout.count(47, 50, 29))
        assertEquals(1, BoardLayout.count(47, 50, 58))
        assertEquals(3, BoardLayout.columns(65, 29))
        assertEquals(2, BoardLayout.columns(65, 58))
        assertEquals(9, BoardLayout.count(65, 62, 29))
        assertEquals(4, BoardLayout.count(65, 62, 58))
    }

    @Test
    fun exactBoardSizeDoesNotCreateExtraBoard() {
        assertEquals(1, BoardLayout.count(29, 29, 29))
        assertEquals(1, BoardLayout.count(58, 58, 58))
        assertEquals(4, BoardLayout.count(30, 30, 29))
    }

    @Test
    fun indexAt_mapsRowMajorAndHandlesInvalid() {
        assertEquals(0, BoardLayout.indexAt(0, 47, 29))
        assertEquals(0, BoardLayout.indexAt(28 * 47 + 28, 47, 29))
        assertEquals(1, BoardLayout.indexAt(29, 47, 29))
        assertEquals(2, BoardLayout.indexAt(29 * 47, 47, 29))
        assertEquals(3, BoardLayout.indexAt(29 * 47 + 29, 47, 29))
        assertEquals(-1, BoardLayout.indexAt(-1, 47, 29))
        assertEquals(-1, BoardLayout.indexAt(0, 0, 29))
    }

    @Test
    fun bounds_clipLastPartialBoard() {
        assertEquals(
            BoardLayout.Bounds(0, 0, 29, 29),
            BoardLayout.bounds(0, 47, 50, 29),
        )
        assertEquals(
            BoardLayout.Bounds(29, 29, 18, 21),
            BoardLayout.bounds(3, 47, 50, 29),
        )
        assertEquals(
            BoardLayout.Bounds(0, 0, 47, 50),
            BoardLayout.bounds(0, 47, 50, 58),
        )
        assertNull(BoardLayout.bounds(4, 47, 50, 29))
    }

    @Test
    fun invalidSpanFallsBackTo29() {
        assertEquals(29, BoardLayout.normalizeSpan(0))
        assertEquals(29, BoardLayout.normalizeSpan(30))
        assertEquals(58, BoardLayout.normalizeSpan(58))
        assertEquals(4, BoardLayout.count(47, 50, 999))
    }
}
