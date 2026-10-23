package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PaletteCsvTest {

    @Test
    fun parsesHeaderAndNormalizesHex() {
        val parsed = PaletteCsv.parse(
            "\uFEFF色号,HEX\r\nA1,#ff0000\r\nB2,00ff00\r\n",
            "我的色板",
        )
        assertNull(parsed.error)
        assertEquals("我的色板", parsed.title)
        assertEquals(listOf("A1", "B2"), parsed.colors.map { it.code })
        assertEquals(listOf("#FF0000", "#00FF00"), parsed.colors.map { it.hex })
        assertEquals(listOf("A", "B"), parsed.colors.map { it.group })
        assertEquals(2, parsed.totalRows)
    }

    @Test
    fun parsesHeaderlessCsvAndCountsInvalidRows() {
        val parsed = PaletteCsv.parse(
            """
            A1,#FF0000
            A1,#111111
            B1,not-a-color
            C1,#0000FF
            """.trimIndent(),
            "色板",
        )
        assertNull(parsed.error)
        assertEquals(listOf("A1", "C1"), parsed.colors.map { it.code })
        assertEquals(4, parsed.totalRows)
        assertEquals(2, parsed.invalidRows)
    }

    @Test
    fun reportsEmptyAndAllInvalidFiles() {
        assertEquals("文件是空的", PaletteCsv.parse("", "x").error)
        assertEquals("没有可导入的色号", PaletteCsv.parse("A1,nope\nB2,#12345", "x").error)
    }

    @Test
    fun rejectsTooManyColors() {
        val text = (1..501).joinToString("\n") { i -> "C$i,#%06X".format(i) }
        assertEquals("一个色板最多 500 色", PaletteCsv.parse(text, "x").error)
    }
}
