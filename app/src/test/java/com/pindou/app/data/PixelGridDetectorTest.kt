package com.pindou.app.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sin

class PixelGridDetectorTest {

    /**
     * 合成一张像素画：格子尺寸可以是小数，offset 模拟裁剪相位。
     * 颜色按格子坐标变化，保证横竖方向都有边界。
     */
    private fun gridImage(
        cols: Int,
        rows: Int,
        cellX: Float,
        cellY: Float = cellX,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
    ): Triple<IntArray, Int, Int> {
        val colors = intArrayOf(
            0xFF101820.toInt(),
            0xFFF2E8D0.toInt(),
            0xFF30A060.toInt(),
            0xFF4060C0.toInt(),
            0xFFC04050.toInt(),
        )
        val w = (offsetX + cols * cellX).roundToInt()
        val h = (offsetY + rows * cellY).roundToInt()
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 0 until h) {
            val gy = floor((y - offsetY) / cellY).toInt()
            if (y < offsetY || gy < 0 || gy >= rows) continue
            for (x in 0 until w) {
                val gx = floor((x - offsetX) / cellX).toInt()
                if (x < offsetX || gx < 0 || gx >= cols) continue
                pixels[y * w + x] = colors[(gx + gy) % colors.size]
            }
        }
        return Triple(pixels, w, h)
    }

    @Test
    fun detectsIntegerCellSize() {
        val (pixels, w, h) = gridImage(29, 29, 3f)
        val grid = PixelGridDetector.detect(pixels, w, h)

        assertNotNull(grid)
        // 无外边距时相位会落在第一条内部边界上，最左一列不计入 cols
        assertTrue(grid!!.cols in 28..29)
        assertTrue(grid.rows in 28..29)
        assertEquals(3f, grid.cellX, 0.05f)
        assertEquals(3f, grid.cellY, 0.05f)
        assertTrue(grid.confidence >= 0.30f)
    }

    @Test
    fun detectsIntegerCellSizeWithPhaseOffset() {
        val (pixels, w, h) = gridImage(25, 22, 3f, 3f, 1.4f, 0.8f)
        val grid = PixelGridDetector.detect(pixels, w, h)

        assertNotNull(grid)
        assertTrue(abs(grid!!.cellX - 3f) < 0.2f)
        assertTrue(abs(grid.cellY - 3f) < 0.2f)
        assertTrue(grid.offsetX in 0..2)
        assertTrue(grid.offsetY in 0..2)
        assertTrue(grid.cols in 25..27)
        assertTrue(grid.rows in 21..23)
    }

    @Test
    fun rejectsFlatImage() {
        val pixels = IntArray(60 * 60) { 0xFFFFFFFF.toInt() }
        assertNull(PixelGridDetector.detect(pixels, 60, 60))
    }

    @Test
    fun rejectsOneDimensionalStripes() {
        val w = 81
        val h = 60
        val pixels = IntArray(w * h) { index ->
            if ((index % w / 3) % 2 == 0) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        assertNull(PixelGridDetector.detect(pixels, w, h))
    }

    @Test
    fun rejectsTooSmallRegion() {
        assertNull(PixelGridDetector.detect(IntArray(20 * 30), 20, 30))
    }

    @Test
    fun rejectsSmoothPhotoLikeImage() {
        val w = 320
        val h = 320
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val v = (128.0 + 45.0 * sin(x / 17.0) + 35.0 * cos(y / 13.0) +
                20.0 * sin((x + y) / 29.0)).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        assertNull(PixelGridDetector.detect(pixels, w, h))
    }

    @Test
    fun rejectsIrregularTexture() {
        val w = 300
        val h = 300
        val pixels = IntArray(w * h) { i ->
            val x = i % w
            val y = i / w
            val v = (128.0 + 40.0 * sin(2 * Math.PI * x / 5.5) +
                30.0 * sin(2 * Math.PI * y / 7.3)).roundToInt().coerceIn(0, 255)
            (0xFF shl 24) or (v shl 16) or (v shl 8) or v
        }
        assertNull(PixelGridDetector.detect(pixels, w, h))
    }

    @Test
    fun rejectsRandomNoise() {
        var seed = 12345
        val pixels = IntArray(200 * 200) {
            seed = seed * 1103515245 + 12345
            val v = (seed ushr 16) and 0xFF
            0xFF000000.toInt() or (v shl 16) or (v shl 8) or v
        }
        assertNull(PixelGridDetector.detect(pixels, 200, 200))
    }

    @Test
    fun rejectsGridDenserThanLimit() {
        val (pixels, w, h) = gridImage(250, 30, 3f)
        assertNull(PixelGridDetector.detect(pixels, w, h))
    }

    @Test
    fun detectsFractionalCellSize() {
        val (pixels, w, h) = gridImage(30, 30, 27.3f, 27.3f, 5.3f, 7.1f)
        val grid = PixelGridDetector.detect(pixels, w, h)

        assertNotNull(grid)
        assertTrue(abs(grid!!.cellX - 27.3f) < 0.6f)
        assertTrue(abs(grid.cellY - 27.3f) < 0.6f)
        assertTrue(grid.cols in 29..30)
        assertTrue(grid.rows in 29..30)
    }
}
