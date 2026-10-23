package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HexColorTest {

    @Test
    fun normalizeAcceptsSixDigitsWithOrWithoutHash() {
        assertEquals("#A1B2C3", HexColor.normalize("a1b2c3"))
        assertEquals("#A1B2C3", HexColor.normalize("#a1b2c3"))
        assertEquals("#A1B2C3", HexColor.normalize("  #A1b2C3  "))
    }

    @Test
    fun normalizeRejectsInvalidInput() {
        assertNull(HexColor.normalize(""))
        assertNull(HexColor.normalize("#12345"))
        assertNull(HexColor.normalize("#1234567"))
        assertNull(HexColor.normalize("GGGGGG"))
    }

    @Test
    fun parseArgbSixDigitsIsOpaque() {
        assertEquals(0xFF112233.toInt(), HexColor.parseArgb("#112233"))
        assertEquals(0xFF112233.toInt(), HexColor.parseArgb("112233"))
        assertEquals(0xFFAABBCC.toInt(), HexColor.parseArgb("#AaBbCc"))
    }

    @Test
    fun parseArgbEightDigitsKeepsAlpha() {
        assertEquals(0x80112233.toInt(), HexColor.parseArgb("#80112233"))
        assertEquals(0x00112233, HexColor.parseArgb("#00112233"))
    }

    @Test
    fun parseArgbFallsBackToBlack() {
        assertEquals(0xFF000000.toInt(), HexColor.parseArgb("nope"))
        assertEquals(0xFF000000.toInt(), HexColor.parseArgb(""))
        assertEquals(0xFF000000.toInt(), HexColor.parseArgb("#12345"))
    }
}
