package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class InventoryCsvTest {

    private val palette = testPalette("#111111", "#222222")
    private val p = testPalette("#111111", "#222222", "#333333")

    @Test
    fun header_usesStockColumnAndSkipsBlank() {
        val csv = "\uFEFF色号,颗数,库存\r\nT0,999,120\r\nT1,5,\r\n"
        val res = InventoryCsv.parse(csv, p)
        assertNull(res.error)
        assertEquals(mapOf("T0" to 120), res.counts)
        assertEquals(2, res.totalRows)
        assertEquals(0, res.invalidRows)
    }

    @Test
    fun header_fallsBackToQuantityWhenNoStockColumn() {
        val csv = "色号,数量\nT1,42\n"
        assertEquals(mapOf("T1" to 42), InventoryCsv.parse(csv, p).counts)
    }

    @Test
    fun noHeader_readsFirstTwoColumns() {
        val csv = "T0,10\nT2,7\n"
        assertEquals(mapOf("T0" to 10, "T2" to 7), InventoryCsv.parse(csv, p).counts)
    }

    @Test
    fun unknownCodesAndBadCountsAreReported() {
        val csv = "色号,库存\nZ9,3\nT0,abc\nT1,-2\nT2,5\n"
        val res = InventoryCsv.parse(csv, p)
        assertEquals(mapOf("T2" to 5), res.counts)
        assertEquals(listOf("Z9"), res.unknown)
        assertEquals(2, res.invalidRows)
        assertEquals(4, res.totalRows)
    }

    @Test
    fun duplicatesLastValueWins() {
        val csv = "T0,1\nT0,9\n"
        assertEquals(mapOf("T0" to 9), InventoryCsv.parse(csv, palette).counts)
    }

    @Test
    fun quotedCellsWithCommaAreParsed() {
        val row = InventoryCsv.parseRow("\"T,0\",\"1,200\"")
        assertEquals(listOf("T,0", "1,200"), row)
    }

    @Test
    fun missingCountHeaderReturnsError() {
        val res = InventoryCsv.parse("色号,HEX\nT0,#111111\n", p)
        assertTrue(res.error != null)
        assertTrue(res.counts.isEmpty())
    }

    @Test
    fun tabSeparatedFileIsAccepted() {
        val csv = "T0\t12\nT1\t34\n"
        assertEquals(mapOf("T0" to 12, "T1" to 34), InventoryCsv.parse(csv, p).counts)
    }
}
