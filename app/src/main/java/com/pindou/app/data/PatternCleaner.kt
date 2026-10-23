package com.pindou.app.data

/**
 * 去杂点：只把"过渡色/压缩噪点"并回邻居，**不动真正的图案像素**。
 *
 * 前一版只看"这格是不是孤立"，结果把黑眼珠、鼻子尖、线条端点也一起并掉了——
 * 那些恰恰是画面里最该保留的细节，还会表现成"黑色被平均成灰色"。
 * 现在多一道颜色判断，只有两种情况才算杂点：
 *
 *  1. 和主邻居色几乎一样（加权距离 < NEAR_SAME）——压缩噪点；
 *  2. 颜色落在"主邻居色"和"次邻居色"的连线上（偏差 ≤ BLEND_TOLERANCE）——两个色混出来的过渡色。
 *
 * 黑画在白上、白画在黑上这类对比色孤点两个条件都不满足，一律保留。
 * 距离口径与取色时一致：2R² + 4G² + 3B²。
 */
object PatternCleaner {

    /** 与主邻居色差小于它按噪点处理（RGB 加权距离，约每通道差 12）。 */
    private const val NEAR_SAME = 1300

    /** 落在两邻居色连线上允许的偏离比例。 */
    private const val BLEND_TOLERANCE = 0.18f

    enum class Strength(val sameLimit: Int, val passes: Int, val label: String) {
        LIGHT(0, 1, "轻"),
        MEDIUM(1, 1, "中"),
        STRONG(1, 3, "强"),
    }

    data class Result(val cells: IntArray, val changed: Int, val passes: Int)

    /**
     * @param cells 每格一个色板下标，-1 = 空
     * @param palette 用来把下标换算成颜色（判"是不是过渡色"必须看真实颜色）
     */
    fun despeckle(
        cells: IntArray,
        width: Int,
        height: Int,
        palette: Palette,
        strength: Strength = Strength.MEDIUM,
    ): Result {
        if (width < 3 || height < 3 || cells.size != width * height) {
            return Result(cells.copyOf(), 0, 0)
        }
        val slots = (cells.maxOrNull() ?: 0) + 1
        val argb = arrayOfNulls<Int>(slots)
        for (idx in 0 until slots) argb[idx] = palette.get(idx)?.argb
        val freq = IntArray(slots)
        val touched = IntArray(8)

        var current = cells.copyOf()
        var totalChanged = 0
        var pass = 0
        while (pass < strength.passes) {
            pass++
            val next = current.copyOf()
            var changedThisPass = 0

            for (y in 0 until height) {
                for (x in 0 until width) {
                    val i = y * width + x
                    val self = current[i]
                    if (self < 0) continue

                    var touchedCount = 0
                    var filled = 0
                    for (dy in -1..1) {
                        val ny = y + dy
                        if (ny < 0 || ny >= height) continue
                        for (dx in -1..1) {
                            if (dx == 0 && dy == 0) continue
                            val nx = x + dx
                            if (nx < 0 || nx >= width) continue
                            val v = current[ny * width + nx]
                            if (v < 0) continue
                            if (freq[v] == 0) touched[touchedCount++] = v
                            freq[v]++
                            filled++
                        }
                    }
                    if (filled == 0) continue

                    var topColor = -1
                    var topCount = 0
                    var secondColor = -1
                    var secondCount = 0
                    var sameCount = 0
                    for (k in 0 until touchedCount) {
                        val v = touched[k]
                        val c = freq[v]
                        when {
                            c > topCount -> {
                                secondColor = topColor
                                secondCount = topCount
                                topColor = v
                                topCount = c
                            }
                            c > secondCount -> {
                                secondColor = v
                                secondCount = c
                            }
                        }
                        if (v == self) sameCount = c
                        freq[v] = 0
                    }

                    if (topColor < 0 || topColor == self) continue
                    if (sameCount > strength.sameLimit) continue
                    if (filled < 5 || topCount < 5) continue

                    val selfArgb = argb[self] ?: continue
                    val topArgb = argb[topColor] ?: continue

                    val dTop = distance(selfArgb, topArgb)
                    val isNoise = dTop < NEAR_SAME
                    val secondArgb = if (secondColor >= 0 && secondCount > 0) argb[secondColor] else null
                    val isBlend = secondArgb != null && blendsBetween(selfArgb, topArgb, secondArgb)
                    if (!isNoise && !isBlend) continue

                    next[i] = topColor
                    changedThisPass++
                }
            }

            totalChanged += changedThisPass
            current = next
            if (changedThisPass == 0) break
        }
        return Result(current, totalChanged, pass)
    }

    /** 统计用：有多少格子四周没有同色邻居（给 UI 做提示，不改数据）。 */
    fun isolationCount(cells: IntArray, width: Int, height: Int): Int {
        if (width < 3 || height < 3 || cells.size != width * height) return 0
        var count = 0
        for (y in 0 until height) {
            for (x in 0 until width) {
                val self = cells[y * width + x]
                if (self < 0) continue
                var same = 0
                var filled = 0
                for (dy in -1..1) {
                    val ny = y + dy
                    if (ny < 0 || ny >= height) continue
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        if (nx < 0 || nx >= width) continue
                        val v = cells[ny * width + nx]
                        if (v < 0) continue
                        filled++
                        if (v == self) same++
                    }
                }
                if (filled >= 5 && same == 0) count++
            }
        }
        return count
    }

    /**
     * self 是否大致落在 a、b 两色的连线上（a 与 b 混出来的过渡色）。
     * 用三角形不等式近似：dist(a,self) + dist(self,b) 明显大于 dist(a,b) 就不是混合色。
     */
    private fun blendsBetween(self: Int, a: Int, b: Int): Boolean {
        val ab = distance(a, b)
        if (ab == 0) return false
        val excess = (distance(a, self) + distance(self, b) - ab).toFloat() / ab
        return excess <= BLEND_TOLERANCE
    }

    private fun distance(from: Int, to: Int): Int {
        val dr = ((from shr 16) and 0xFF) - ((to shr 16) and 0xFF)
        val dg = ((from shr 8) and 0xFF) - ((to shr 8) and 0xFF)
        val db = (from and 0xFF) - (to and 0xFF)
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db
    }
}
