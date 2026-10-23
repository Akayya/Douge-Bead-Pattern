package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class PatternPdfPaginationTest {

    @Test
    fun smallPattern_usesOneTilePerBoard() {
        // 47×50：29 格板需要 2×2，58 格板只要 1×1
        assertEquals(5, PatternPdf.pageCount(47, 50, PatternPdf.BOARD_SMALL))
        assertEquals(2, PatternPdf.pageCount(47, 50, PatternPdf.BOARD_LARGE))
    }

    @Test
    fun exactBoardSizes_doNotAddExtraPage() {
        assertEquals(2, PatternPdf.pageCount(29, 29, PatternPdf.BOARD_SMALL))
        assertEquals(2, PatternPdf.pageCount(58, 58, PatternPdf.BOARD_LARGE))
        assertEquals(3, PatternPdf.pageCount(30, 29, PatternPdf.BOARD_SMALL))
    }

    @Test
    fun largePattern_countsBoardAlignedTiles() {
        assertEquals(9, PatternPdf.pageCount(51, 115, PatternPdf.BOARD_SMALL))
        assertEquals(3, PatternPdf.pageCount(51, 115, PatternPdf.BOARD_LARGE))
    }

    @Test
    fun unknownSpan_fallsBackTo29() {
        assertEquals(PatternPdf.pageCount(47, 50, PatternPdf.BOARD_SMALL), PatternPdf.pageCount(47, 50, 0))
    }
}
