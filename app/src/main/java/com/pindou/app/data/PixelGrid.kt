package com.pindou.app.data

import android.graphics.Bitmap
import android.graphics.RectF
import kotlin.math.abs
import kotlin.math.floor
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
    private const val MIN_CELL = 2.8f
    // 真实网格的边界能量集中度实测 3 以上；照片的平滑边缘只有 1.0–1.2。
    private const val MIN_CONCENTRATION = 1.5f
    private const val MAX_DETECT_SIDE = 1024

    fun detect(source: Bitmap, crop: RectF): Grid? {
        val region = ImageToPattern.cropBitmap(source, crop) ?: return null
        try {
            val w = region.width
            val h = region.height
            if (w < 24 || h < 24) return null
            if (w <= MAX_DETECT_SIDE && h <= MAX_DETECT_SIDE) {
                val pixels = IntArray(w * h)
                region.getPixels(pixels, 0, w, 0, 0, w, h)
                return detect(pixels, w, h)
            }
            // 大图不整张取像素：照片通常在一个窗口里就会判失败，
            // 1024 窗口也足够覆盖 64px 以内的格子周期（period 的 maxP）。
            return detectInWindows(region, w, h)
        } finally {
            if (region !== source) region.recycle()
        }
    }

    /**
     * 只在几个 1024×1024 窗口里找周期。窗口内的相位加上窗口起点再对周期取模，
     * 就换算回整张裁剪区的相位；格数仍按整张裁剪区重新计算。
     */
    private fun detectInWindows(region: Bitmap, width: Int, height: Int): Grid? {
        val pw = min(width, MAX_DETECT_SIDE)
        val ph = min(height, MAX_DETECT_SIDE)
        val xs = intArrayOf((width - pw) / 2, 0, width - pw).distinct()
        val ys = intArrayOf((height - ph) / 2, 0, height - ph).distinct()
        val pixels = IntArray(pw * ph)
        var best: Grid? = null
        for (y in ys) {
            for (x in xs) {
                region.getPixels(pixels, 0, pw, x, y, pw, ph)
                val local = detect(pixels, pw, ph) ?: continue
                val offsetX = shiftPhase(x + local.offsetX, local.cellX)
                val offsetY = shiftPhase(y + local.offsetY, local.cellY)
                val cols = ((width - offsetX).coerceAtLeast(1).toFloat() / local.cellX).roundToInt()
                val rows = ((height - offsetY).coerceAtLeast(1).toFloat() / local.cellY).roundToInt()
                if (cols < 2 || rows < 2 || cols > MAX_SIDE || rows > MAX_SIDE) continue
                val candidate = local.copy(offsetX = offsetX, offsetY = offsetY, cols = cols, rows = rows)
                val current = best
                if (current == null || candidate.confidence > current.confidence) best = candidate
            }
        }
        return best
    }

    private fun shiftPhase(value: Int, cell: Float): Int {
        val r = value - floor(value / cell) * cell
        val i = r.roundToInt()
        return if (i.toFloat() >= cell) 0 else i
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
        // 平滑照片的边缘能量自相关在 2–3px 处也很高，但那是「相邻像素差不多」，
        // 不是格子边界。真实拼豆截图一格通常远大于 3px。
        if (px.first < 3 || py.first < 3) return null
        val conf = min(px.second, py.second)
        if (conf < 0.30f) return null
        // 两个方向的格子应该差不多大；差太多说明识别到的是图案内部的条纹而不是格子
        if (abs(px.first - py.first).toFloat() > max(1f, px.first / 4f)) return null

        val cellX = refinePeriod(colEdge, px.first)
        val cellY = refinePeriod(rowEdge, py.first)
        if (cellX < MIN_CELL || cellY < MIN_CELL) return null
        val offsetX = refinePhase(colEdge, cellX)
        val offsetY = refinePhase(rowEdge, cellY)
        // 照片的边缘能量摊在每一列上；真实网格集中在周期性的边界列上。
        if (min(
                boundaryConcentration(colEdge, cellX, offsetX),
                boundaryConcentration(rowEdge, cellY, offsetY),
            ) < MIN_CONCENTRATION
        ) return null
        val cols = ((w - offsetX).coerceAtLeast(1).toFloat() / cellX).roundToInt()
        val rows = ((h - offsetY).coerceAtLeast(1).toFloat() / cellY).roundToInt()
        // 不能把超大格数截断成 240：那会把照片伪装成一张 240×240 的图纸。
        if (cols < 2 || rows < 2 || cols > MAX_SIDE || rows > MAX_SIDE) return null
        return Grid(cellX, cellY, offsetX, offsetY, cols, rows, conf)
    }

    /** 周期边界处的边缘能量峰值 / 全图平均边缘能量。 */
    private fun boundaryConcentration(edge: FloatArray, period: Float, phase: Int): Float {
        val n = edge.size
        if (n < 2) return 0f
        var total = 0.0
        for (i in 1 until n) total += edge[i]
        val mean = total / (n - 1)
        if (mean <= 1e-6) return 0f
        var sum = 0f
        var count = 0
        var k = 0
        while (true) {
            val idx = (phase + k * period).roundToInt()
            if (idx >= n) break
            if (idx >= 1) {
                var peak = edge[idx]
                if (idx - 1 >= 1) peak = max(peak, edge[idx - 1])
                if (idx + 1 < n) peak = max(peak, edge[idx + 1])
                sum += peak
                count++
            }
            k++
        }
        return if (count == 0) 0f else (sum / count) / mean.toFloat()
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
