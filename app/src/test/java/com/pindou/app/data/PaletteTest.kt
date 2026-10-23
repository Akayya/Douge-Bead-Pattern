package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class PaletteTest {

    @Test
    fun jsonRoundTripKeepsColorsAndGroups() {
        val original = Palette(
            id = "custom-abc123",
            title = "我的色板",
            groups = listOf("A", "B"),
            colors = listOf(
                BeadColor("A1", "#FF0000", "A"),
                BeadColor("B2", "#00FF00", "B"),
            ),
        )
        val parsed = Palette.fromJson(original.toJson())
        assertEquals(original.id, parsed.id)
        assertEquals(original.title, parsed.title)
        assertEquals(original.groups, parsed.groups)
        assertEquals(original.colors, parsed.colors)
    }

    @Test
    fun fromJsonInfersGroupsAndUppercasesCodes() {
        val json = """
            {
              "id": "custom-abc123",
              "colors": [
                {"code": "a1", "hex": "#ff0000"},
                {"code": "12", "hex": "00ff00"}
              ]
            }
        """.trimIndent()
        val palette = Palette.fromJson(json)
        assertEquals("custom-abc123", palette.title)
        assertEquals("A1", palette.colors[0].code)
        assertEquals("A", palette.colors[0].group)
        assertEquals("自定义", palette.colors[1].group)
        assertEquals(listOf("A", "自定义"), palette.groups)
    }

    @Test
    fun duplicateOrInvalidColorsAreRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            Palette.fromJson(
                """{"id":"custom-abc123","colors":[{"code":"A1","hex":"#111111"},{"code":"a1","hex":"#222222"}]}""",
            )
        }
        assertThrows(IllegalArgumentException::class.java) {
            Palette.fromJson("""{"id":"custom-abc123","colors":[{"code":"A1","hex":"#GGGGGG"}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Palette.fromJson("""{"id":"abc","colors":[{"code":"A1","hex":"#111111"}]}""")
        }
        assertThrows(IllegalArgumentException::class.java) {
            Palette.fromJson("""{"id":"custom-abc123","colors":[]}""")
        }
    }

    @Test
    fun fromJsonRejectsTooManyColors() {
        val colors = (0..Palette.MAX_COLORS).joinToString(",") {
            """{"code":"C$it","hex":"#111111"}"""
        }
        assertThrows(IllegalArgumentException::class.java) {
            Palette.fromJson("""{"id":"custom-abc123","colors":[$colors]}""")
        }
    }

    @Test
    fun groupOfUsesLetterPrefixAndFallsBackForDigits() {
        assertEquals("A", Palette.groupOf("A12"))
        assertEquals("ZG", Palette.groupOf("zg3"))
        assertEquals("自定义", Palette.groupOf("12"))
        assertEquals("自定义", Palette.groupOf(""))
    }

    @Test
    fun paletteIdValidation() {
        assertTrue(PaletteId.isValid("mard221"))
        assertTrue(PaletteId.isValid("custom-abc123"))
        assertTrue(PaletteId.isValid("my_palette-1"))
        assertFalse(PaletteId.isValid("abc"))
        assertFalse(PaletteId.isValid("bad id"))
        assertFalse(PaletteId.isValid(""))
        assertFalse(PaletteId.isValid("a".repeat(65)))
        assertTrue(PaletteId.isBuiltIn("mard221"))
        assertTrue(PaletteId.isBuiltIn("mard291"))
        assertFalse(PaletteId.isBuiltIn("custom-abc123"))
    }
}
