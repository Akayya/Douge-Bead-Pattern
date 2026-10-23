package com.pindou.app.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PatternFileTest {

    private val palette = testPalette("#FF0000", "#00FF00", "#0000FF")

    private fun sourcePattern(
        cells: IntArray = intArrayOf(0, 1, -1, 0),
        done: Set<Int> = setOf(0, 3),
    ): Pattern = Pattern(
        id = "abcd1234",
        name = "小猫",
        width = 2,
        height = 2,
        paletteId = PaletteId.MARD_221,
        cells = cells,
        createdAt = 1000L,
        updatedAt = 2000L,
        done = done.toMutableSet(),
    )

    @Test
    fun roundTripKeepsPatternProgressAndColors() {
        val source = sourcePattern()

        val parsed = PatternFile.parse(PatternFile.encode(source, palette, "0.6.19"))

        assertEquals("小猫", parsed.pattern.name)
        assertEquals(PaletteId.MARD_221, parsed.pattern.paletteId)
        assertArrayEquals(source.cells, parsed.pattern.cells)
        assertEquals(setOf(0, 3), parsed.pattern.done)
        assertEquals(mapOf("T0" to "#FF0000", "T1" to "#00FF00"), parsed.colors)
        assertEquals(0, PatternFile.mismatchedColorCount(parsed, palette))
    }

    @Test
    fun importGetsFreshIdEvenWithoutLocalCollision() {
        val prepared = PatternFile.prepareForImport(
            sourcePattern(),
            usedIds = emptySet(),
            usedNames = emptySet(),
            now = 5000L,
        )

        assertNotEquals("abcd1234", prepared.pattern.id)
        assertEquals("小猫", prepared.pattern.name)
        assertFalse(prepared.renamed)
        assertEquals(5000L, prepared.pattern.createdAt)
        assertEquals(5000L, prepared.pattern.updatedAt)
        assertEquals(setOf(0, 3), prepared.pattern.done)
    }

    @Test
    fun duplicateNameGetsNumberedSuffix() {
        val prepared = PatternFile.prepareForImport(
            sourcePattern(),
            usedIds = emptySet(),
            usedNames = setOf("小猫", "小猫（导入）"),
            now = 5000L,
        )

        assertEquals("小猫（导入） 2", prepared.pattern.name)
        assertTrue(prepared.renamed)
    }

    @Test
    fun rejectsForeignOrBrokenFiles() {
        val encoded = PatternFile.encode(sourcePattern(), palette, "0.6.19")

        assertThrows(PatternFile.ImportException::class.java) { PatternFile.parse("{}") }
        assertThrows(PatternFile.ImportException::class.java) {
            PatternFile.parse(encoded.replace("\"app\":\"douge\"", "\"app\":\"other\""))
        }
        assertThrows(PatternFile.ImportException::class.java) {
            PatternFile.parse(encoded.replace("\"format\":1", "\"format\":2"))
        }
        assertThrows(PatternFile.ImportException::class.java) {
            PatternFile.parse(encoded.replace("\"kind\":\"pattern\"", "\"kind\":\"backup\""))
        }
        assertThrows(PatternFile.ImportException::class.java) {
            PatternFile.parse(encoded.replace("\"paletteId\":\"mard221\"", "\"paletteId\":\"other\""))
        }
        assertThrows(PatternFile.ImportException::class.java) {
            PatternFile.parse(encoded.replace("\"w\":2", "\"w\":3"))
        }
    }

    @Test
    fun reportsOnlyUsedColorDifferences() {
        val parsed = PatternFile.parse(PatternFile.encode(sourcePattern(), palette, "0.6.19"))

        val changed = testPalette("#FF0000", "#0000EE", "#654321")
        assertEquals(1, PatternFile.mismatchedColorCount(parsed, changed))

        val sameLowercase = testPalette("#ff0000", "#00ff00", "#0000ff")
        assertEquals(0, PatternFile.mismatchedColorCount(parsed, sameLowercase))
    }

    @Test
    fun detectsOutOfRangePaletteIndex() {
        val broken = sourcePattern(
            cells = intArrayOf(0, 3, -1, 0),
            done = emptySet(),
        )

        assertEquals(0, PatternFile.unknownColorCount(sourcePattern(), palette))
        assertEquals(1, PatternFile.unknownColorCount(broken, palette))
    }

    @Test
    fun shareFileNameStripsReservedCharacters() {
        assertEquals("a_b_c_", PatternFile.safeBaseName("a/b\\c:"))
        assertEquals("图纸", PatternFile.safeBaseName("   "))
        assertEquals(40, PatternFile.safeBaseName("x".repeat(80)).length)
    }
}
