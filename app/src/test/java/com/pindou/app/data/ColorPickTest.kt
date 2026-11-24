package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Test

class ColorPickTest {

    @Test
    fun argbToHexIgnoresAlpha() {
        // 不透明 / 半透明 / 全透明，只看 RGB
        assertEquals("F73D48", ColorPick.argbToHex(0xFFF73D48.toInt()))
        assertEquals("F73D48", ColorPick.argbToHex(0x80F73D48.toInt()))
        assertEquals("F73D48", ColorPick.argbToHex(0x00F73D48))
    }

    @Test
    fun argbToHexBlackWhiteBoundaries() {
        assertEquals("000000", ColorPick.argbToHex(0xFF000000.toInt()))
        assertEquals("FFFFFF", ColorPick.argbToHex(0xFFFFFFFF.toInt()))
        assertEquals("0000FF", ColorPick.argbToHex(0xFF0000FF.toInt()))
        assertEquals("00FF00", ColorPick.argbToHex(0xFF00FF00.toInt()))
    }

    @Test
    fun hexRoundTripsThroughNormalize() {
        val colors = intArrayOf(
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFF73D48.toInt(),
            0x80123456.toInt(),
            0x0000ABCD,
        )
        for (argb in colors) {
            val body = ColorPick.argbToHex(argb)
            assertEquals(6, body.length)
            assertEquals(body.uppercase(), body)
            assertEquals("#$body", HexColor.normalize(body))
        }
    }

    @Test
    fun mapsCenterOfSquare() {
        assertEquals(50 to 50, ColorPick.mapTapToPixel(100f, 100f, 200f, 200f, 100, 100))
    }

    @Test
    fun mapsCornersAndClampsBeyondEdges() {
        // 显示区域与图片同比例：四角直接映射到像素角，越界 clamp
        assertEquals(0 to 0, ColorPick.mapTapToPixel(0f, 0f, 200f, 200f, 100, 100))
        assertEquals(99 to 99, ColorPick.mapTapToPixel(200f, 200f, 200f, 200f, 100, 100))
        assertEquals(0 to 0, ColorPick.mapTapToPixel(-50f, -50f, 200f, 200f, 100, 100))
        assertEquals(99 to 99, ColorPick.mapTapToPixel(999f, 999f, 200f, 200f, 100, 100))
    }

    @Test
    fun mapsWithContainLetterbox() {
        // 200×100 的显示区放 100×100 方图：等比缩放到 100×100，左右各留 50px
        assertEquals(0 to 0, ColorPick.mapTapToPixel(50f, 0f, 200f, 100f, 100, 100))
        assertEquals(99 to 99, ColorPick.mapTapToPixel(149f, 99f, 200f, 100f, 100, 100))
        assertEquals(50 to 50, ColorPick.mapTapToPixel(100f, 50f, 200f, 100f, 100, 100))
        // 点在左侧留边上：clamp 到第 0 列
        assertEquals(0 to 50, ColorPick.mapTapToPixel(0f, 50f, 200f, 100f, 100, 100))
    }

    @Test
    fun mapsWideBitmapWithVerticalLetterbox() {
        // 100×100 的显示区放 200×100 宽图：缩到 100×50，上下各留 25px
        assertEquals(0 to 0, ColorPick.mapTapToPixel(0f, 25f, 100f, 100f, 200, 100))
        assertEquals(199 to 99, ColorPick.mapTapToPixel(99.5f, 74.5f, 100f, 100f, 200, 100))
        assertEquals(100 to 49, ColorPick.mapTapToPixel(50f, 49.5f, 100f, 100f, 200, 100))
        // 点在下方留边上：clamp 到最后一行
        assertEquals(100 to 99, ColorPick.mapTapToPixel(50f, 99f, 100f, 100f, 200, 100))
    }

    @Test
    fun degenerateSizesDoNotCrash() {
        assertEquals(0 to 0, ColorPick.mapTapToPixel(10f, 10f, 0f, 100f, 100, 100))
        assertEquals(0 to 0, ColorPick.mapTapToPixel(10f, 10f, 100f, 100f, 0, 100))
    }
}