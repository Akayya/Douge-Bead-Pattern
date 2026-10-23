package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MaterialListCsvTest {

    private val palette = testPalette("#111111", "#222222", "#333333")

    @Test
    fun buildCsv_listsUsageWithBagsMoneyAndStock() {
        val usage = listOf(
            ColorUsage(palette.colors[0], 250),
            ColorUsage(palette.colors[1], 99),
            ColorUsage(palette.colors[2], 1),
        )
        val csv = MaterialListCsv.buildCsv(usage, 0.03f) { code ->
            when (code) {
                "T0" -> 200
                "T1" -> 99
                else -> null
            }
        }
        val lines = csv.split("\r\n").filter { it.isNotEmpty() }
        assertEquals("序号,色号,色系,HEX,颗数,袋数（100颗/袋）,单价（元/颗）,小计（元）,库存,还缺（颗）", lines[0])
        assertEquals("1,T0,黄 A,#111111,250,3,0.03,7.50,200,50", lines[1])
        assertEquals("2,T1,黄 A,#222222,99,1,0.03,2.97,99,0", lines[2])
        // 没记库存的色号，库存/缺口留空，不当成 0
        assertEquals("3,T2,黄 A,#333333,1,1,0.03,0.03,,", lines[3])
        assertEquals("合计,,,,350,5,,10.50,,50", lines[4])
        assertEquals(10, lines[1].split(",").size)
        assertEquals(10, lines[4].split(",").size)
        assertTrue(csv.endsWith("\r\n"))
    }

    @Test
    fun buildCsv_totalMissingBlankWhenNoStockRecorded() {
        val usage = listOf(ColorUsage(palette.colors[0], 10))
        val csv = MaterialListCsv.buildCsv(usage, 0.02f) { null }
        assertEquals("合计,,,,10,1,,0.20,,", csv.split("\r\n")[2])
    }

    @Test
    fun buildCsv_escapesCellsWithCommaAndQuote() {
        val color = BeadColor("T,\"x\"", "#000000", "A")
        val csv = MaterialListCsv.buildCsv(listOf(ColorUsage(color, 1)), 0.01f) { null }
        assertEquals("1,\"T,\"\"x\"\"\",黄 A,#000000,1,1,0.01,0.01,,", csv.split("\r\n")[1])
    }
}
