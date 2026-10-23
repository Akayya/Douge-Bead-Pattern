package com.pindou.app.data

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * 识别「原图一个格子是多少像素」。
 *
 * 思路很朴素：像素画的格子边界是一条条等距的竖线/横线，把每一列的相邻像素差加起来得到
 * 一条「边缘能量曲线」，再对这条曲线做自相关 —— 相关值最高的那个位移，就是格子周期。
 *
 * 找到周期 p 之后，还要找相位（格子边界从第几列开始），否则采样会跨格子。
 * 这样就能逐格 1:1 取色，而不是把原图硬缩成 29 格、细节全糊在一起。
 */
object PixelGridDetector {

    /**
     * @param cellX/cellY 原图一个格子的宽/高（像素，带小数；截图缩放常让格子不是整数）
     * @param offsetX/offsetY 第一格从裁剪区的第几列/行开始
     * @param cols/rows 裁剪区里大概有多少格
     * @param confidence 0..1，太低说明这张图不是像素画（照片），不要用
     */
    data class Grid(
        val cellX: Float,
        val cellY: Float,
        val offsetX: Int,
        val offsetY: Int,
        val cols: Int,
        val rows: Int,
        val confidence: Float,
    ) {
        val summary: String get() = "${fmt(cellX)}×${fmt(cellY)}px 一格 · $cols × $rows 格"

        private fun fmt(v: Float): String =
            if (kotlin.math.abs(v - v.roundToInt()) < 0.05f) v.roundToInt().toString()
            else String.format(java.util.Locale.US, "%.1f", v)
    }

    private const val MAX_SIDE = 240

    fun detect(source: Bitmap, crop: RectF): Grid? {
        val region = ImageToPattern.cropBitmap(source, crop) ?: return null
        try {
            val w = region.width
            val h = region.height
            if (w < 24 || h < 24) return null
            val pixels = IntArray(w * h)
            region.getPixels(pixels, 0, w, 0, 0, w, h)
            return detect(pixels, w, h)
        } finally {
            if (region !== source) region.recycle()
        }
    }

    /** 纯算法入口，方便用合成像素阵列做 JVM 单测。 */
    internal fun detect(pixels: IntArray, width: Int, height: Int): Grid? {
        if (width < 24 || height < 24 || pixels.size < width * height) return null
        val w = width
        val h = height

        // 灰度
        val gray = FloatArray(w * h)
        for (i in pixels.indices) {
            val p = pixels[i]
            val a = (p ushr 24) and 0xFF
            val r = (p shr 16) and 0xFF
            val g = (p shr 8) and 0xFF
            val b = p and 0xFF
            gray[i] = if (a < 128) 255f else 0.299f * r + 0.587f * g + 0.114f * b
        }

        // 列/行边缘能量
        val colEdge = FloatArray(w)
        for (x in 1 until w) {
            var s = 0f
            for (y in 0 until h) s += abs(gray[y * w + x] - gray[y * w + x - 1])
            colEdge[x] = s
        }
        val rowEdge = FloatArray(h)
        for (y in 1 until h) {
            var s = 0f
            val base = y * w
            val prev = base - w
            for (x in 0 until w) s += abs(gray[base + x] - gray[prev + x])
            rowEdge[y] = s
        }

        val px = period(colEdge) ?: return null
        val py = period(rowEdge) ?: return null
        val conf = min(px.second, py.second)
        if (conf < 0.30f) return null
        // 两个方向的格子应该差不多大；差太多说明识别到的是图案内部的条纹而不是格子
        if (abs(px.first - py.first).toFloat() > max(1f, px.first / 4f)) return null

        val cellX = refinePeriod(colEdge, px.first)
        val cellY = refinePeriod(rowEdge, py.first)
        if (cellX < 2f || cellY < 2f) return null
        val offsetX = refinePhase(colEdge, cellX)
        val offsetY = refinePhase(rowEdge, cellY)
        val cols = ((w - offsetX).coerceAtLeast(1).toFloat() / cellX).roundToInt().coerceIn(2, MAX_SIDE)
        val rows = ((h - offsetY).coerceAtLeast(1).toFloat() / cellY).roundToInt().coerceIn(2, MAX_SIDE)
        return Grid(cellX, cellY, offsetX, offsetY, cols, rows, conf)
    }

    /**
     * 周期细化到小数。整数周期只能到 1px，而 53 格累积下来就是半格漂移：
     * 右边的格子会整体取错色。按 0.05px 步长在整数周期附近重扫一遍，取边界能量最强的那个。
     */
    private fun refinePeriod(edge: FloatArray, p0: Int): Float {
        var bestP = p0.toFloat()
        var best = -1f
        var p = max(2f, p0 - 0.8f)
        while (p <= p0 + 0.8f) {
            var sum = 0f
            var count = 0
            var k = 0
            while (true) {
                val idx = (k * p).roundToInt()
                if (idx >= edge.size) break
                sum += edge[idx]
                count++
                k++
            }
            val score = if (count > 0) sum / count else 0f
            if (score > best) { best = score; bestP = p }
            p += 0.05f
        }
        return bestP
    }

    /** 自相关找周期；返回 (周期, 归一化相关分)。 */
    private fun period(edge: FloatArray): Pair<Int, Float>? {
        val n = edge.size
        if (n < 16) return null
        var energy = 0.0
        for (v in edge) energy += v.toDouble() * v
        if (energy < 1e-6) return null

        val maxP = min(64, n / 4)
        if (maxP < 2) return null
        val scores = DoubleArray(maxP + 1)
        for (p in 2..maxP) {
            var s = 0.0
            var i = 0
            while (i + p < n) {
                s += edge[i].toDouble() * edge[i + p]
                i++
            }
            scores[p] = s / energy
        }
        var best = 0.0
        for (p in 2..maxP) if (scores[p] > best) best = scores[p]
        if (best < 0.30) return null
        // 2p 的相关分通常也不低，所以取「接近最高分里最小的那个」当基频
        for (p in 2..maxP) {
            if (scores[p] >= best * 0.85) return p to scores[p].toFloat()
        }
        return 0 to 0f
    }

    /** 小数周期下，边界落在哪个相位。 */
    private fun refinePhase(edge: FloatArray, p: Float): Int {
        var bestO = 0
        var best = -1f
        val span = p.roundToInt() + 1
        for (o in 0 until span) {
            var sum = 0f
            var count = 0
            var k = 0
            while (true) {
                val idx = (o + k * p).roundToInt()
                if (idx >= edge.size) break
                sum += edge[idx]
                count++
                k++
            }
            val score = if (count > 0) sum / count else 0f
            if (score > best) { best = score; bestO = o }
        }
        return bestO
    }
}
