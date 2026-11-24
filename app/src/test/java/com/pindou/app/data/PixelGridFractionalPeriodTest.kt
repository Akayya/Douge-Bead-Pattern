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

/**
 * 网格识别对非整数倍周期的支持（0.6.25）。
 * 合成图：每格纯色 + 格边界 1px 深色线，可加相位偏移、格内条纹与噪声。
 */
class PixelGridFractionalPeriodTest {

    private val baseColors = intArrayOf(
        0xFF101820.toInt(),
        0xFFF2E8D0.toInt(),
        0xFF30A060.toInt(),
        0xFF4060C0.toInt(),
        0xFFC04050.toInt(),
    )

    private fun shade(argb: Int, factor: Float): Int {
        val r = (((argb shr 16) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val g = (((argb shr 8) and 0xFF) * factor).toInt().coerceIn(0, 255)
        val b = ((argb and 0xFF) * factor).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    private fun pixelArt(
        cols: Int,
        rows: Int,
        cellX: Float,
        cellY: Float = cellX,
        offsetX: Float = 0f,
        offsetY: Float = 0f,
        borders: Boolean = true,
        stripes: Int = 0,
        noise: Int = 0,
    ): Triple<IntArray, Int, Int> {
        val w = (offsetX + cols * cellX).roundToInt()
        val h = (offsetY + rows * cellY).roundToInt()
        val pixels = IntArray(w * h) { 0xFFFFFFFF.toInt() }
        for (y in 0 until h) {
            val gy = floor((y - offsetY) / cellY).toInt()
            if (y < offsetY || gy < 0 || gy >= rows) continue
            for (x in 0 until w) {
                val gx = floor((x - offsetX) / cellX).toInt()
                if (x < offsetX || gx < 0 || gx >= cols) continue
                var c = baseColors[(gx + gy) % baseColors.size]
                if (stripes > 0) {
                    val fx = x - (offsetX + gx * cellX)
                    val fy = y - (offsetY + gy * cellY)
                    val phase = floor(fx / (cellX / stripes)).toInt() +
                        floor(fy / (cellY / stripes)).toInt()
                    if (phase % 2 != 0) c = shade(c, 0.7f)
                }
                pixels[y * w + x] = c
            }
        }
        if (borders) {
            val ink = 0xFF000000.toInt()
            for (k in 0..cols) {
                val x = (offsetX + k * cellX).roundToInt()
                if (x in 0 until w) for (y in 0 until h) pixels[y * w + x] = ink
            }
            for (k in 0..rows) {
                val y = (offsetY + k * cellY).roundToInt()
                if (y in 0 until h) for (x in 0 until w) pixels[y * w + x] = ink
            }
        }
        if (noise > 0) {
            var seed = 20261124
            val range = 2 * noise + 1
            for (i in pixels.indices) {
                seed = seed * 1103515245 + 12345
                val d = (seed ushr 16) % range - noise
                val c = pixels[i]
                val r = (((c shr 16) and 0xFF) + d).coerceIn(0, 255)
                val g = (((c shr 8) and 0xFF) + d).coerceIn(0, 255)
                val b = ((c and 0xFF) + d).coerceIn(0, 255)
                pixels[i] = (0xFF shl 24) or (r shl 16) or (g shl 8) or b
            }
        }
        return Triple(pixels, w, h)
    }

    private fun assertCell(
        grid: PixelGridDetector.Grid?,
        cell: Float,
        cols: Int,
        rows: Int,
    ) {
        assertNotNull("应识别出网格", grid)
        assertEquals(cell, grid!!.cellX, 0.1f)
        assertEquals(cell, grid.cellY, 0.1f)
        assertEquals(cols, grid.cols)
        assertEquals(rows, grid.rows)
        assertTrue("置信度应过门槛", grid.confidence >= 0.30f)
    }

    @Test
    fun detects3Point3Cell() {
        val (pixels, w, h) = pixelArt(50, 50, 3.3f, offsetX = 0.6f, offsetY = 0.4f)
        assertCell(PixelGridDetector.detect(pixels, w, h), 3.3f, 50, 50)
    }

    @Test
    fun detects4Point5CellWithPhase() {
        val (pixels, w, h) = pixelArt(40, 40, 4.5f, offsetX = 2.2f, offsetY = 1.1f)
        assertCell(PixelGridDetector.detect(pixels, w, h), 4.5f, 40, 40)
    }

    @Test
    fun detects8Point5CellWithPhase() {
        val (pixels, w, h) = pixelArt(30, 30, 8.5f, offsetX = 3.7f, offsetY = 5.2f)
        assertCell(PixelGridDetector.detect(pixels, w, h), 8.5f, 30, 30)
    }

    @Test
    fun detects12Point7Cell() {
        val (pixels, w, h) = pixelArt(25, 25, 12.7f, offsetX = 4.4f, offsetY = 6.1f)
        assertCell(PixelGridDetector.detect(pixels, w, h), 12.7f, 25, 25)
    }

    @Test
    fun detects27Point3Cell() {
        val (pixels, w, h) = pixelArt(22, 22, 27.3f, offsetX = 5.3f, offsetY = 3.1f)
        assertCell(PixelGridDetector.detect(pixels, w, h), 27.3f, 22, 22)
    }

    @Test
    fun detects6Point2Cell() {
        val (pixels, w, h) = pixelArt(25, 25, 6.2f, offsetX = 1.1f, offsetY = 2.4f)
        assertCell(PixelGridDetector.detect(pixels, w, h), 6.2f, 25, 25)
    }

    @Test
    fun detects8Point5CellWithSlightNoise() {
        val (pixels, w, h) = pixelArt(
            30, 30, 8.5f,
            offsetX = 2f, offsetY = 1f, noise = 4,
        )
        assertCell(PixelGridDetector.detect(pixels, w, h), 8.5f, 30, 30)
    }

    @Test
    fun doesNotLockOntoDoubleFrequencyStripes() {
        // 每个格子内部再画 2× 竖/横条纹（对比度只有格线的一半多），母周期仍是 8px
        val (pixels, w, h) = pixelArt(20, 20, 8f, offsetX = 0.6f, offsetY = 0.6f, stripes = 2)
        assertCell(PixelGridDetector.detect(pixels, w, h), 8f, 20, 20)
    }

    @Test
    fun doesNotLockOntoTripleFrequencyStripes() {
        val (pixels, w, h) = pixelArt(20, 20, 12f, offsetX = 0.6f, offsetY = 0.6f, stripes = 3)
        assertCell(PixelGridDetector.detect(pixels, w, h), 12f, 20, 20)
    }

    @Test
    fun rejectsSmoothPhotoWithNoise() {
        val w = 320
        val h = 320
        var seed = 424242
        val pixels = IntArray(w * h) { i ->
            seed = seed * 1103515245 + 12345
            val noise = (seed ushr 16) % 7 - 3
            val x = i % w
            val y = i / w
            val v = (128.0 + 45.0 * sin(x / 17.0) + 35.0 * cos(y / 13.0) +
                20.0 * sin((x + y) / 29.0)).roundToInt() + noise
            val c = v.coerceIn(0, 255)
            (0xFF shl 24) or (c shl 16) or (c shl 8) or c
        }
        assertNull(PixelGridDetector.detect(pixels, w, h))
    }

    @Test
    fun rejectsCellBelowMinimum() {
        val (pixels, w, h) = pixelArt(40, 40, 2.7f)
        assertNull(PixelGridDetector.detect(pixels, w, h))
    }

    @Test
    fun hugeCellDoesNotCrash() {
        // 65px 超过 maxP=64：要么判失败，要么给出接近 65 的周期与正确格数，不能崩
        val (pixels, w, h) = pixelArt(10, 10, 65f)
        val grid = PixelGridDetector.detect(pixels, w, h)
        if (grid != null) {
            assertTrue(abs(grid.cellX - 65f) <= 1.5f)
            assertTrue(grid.cols in 9..10)
            assertTrue(grid.rows in 9..10)
        }
    }
}