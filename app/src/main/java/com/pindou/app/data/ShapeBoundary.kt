package com.pindou.app.data

/**
 * 自动识别图形边界。
 *
 * 解决两个反向的问题：
 * 1) 空像素入侵图形：图形内部本来有内容的格子被当成背景清掉 —— 这里不会发生，
 *    因为我们只清理「从图片四边连通出去的」背景，被描边围住的部分一律保留。
 * 2) 本来的空像素被替代：图形外面的空白背景被填上豆子（最常见于白色底图），
 *    以及边界一圈由两种颜色混出来的过渡色被当成独立色号，形成一圈脏描边。
 *
 * 做法：
 * - 先取四条边最常见的颜色当背景色；
 * - 从四条边向内做 4 邻域洪水填充，颜色接近背景、且与边界连通的格子 = 图外；
 * - 图外格子全部置空（-1）；
 * - 再对紧贴图外的一圈做「边缘吸附」：只处理全图里极少出现的颜色（说明它只是混色），
 *   且它的颜色正好落在「背景色」和「相邻主体色」之间，就并到主体色 —— 灰阶阴影这种
 *   用量大的颜色不会被误伤。
 */
object ShapeBoundary {

    data class Result(val cells: IntArray, val removed: Int, val snapped: Int)

    /** 与背景色的加权距离阈值，约等于每通道差 10。 */
    private const val BG_TOLERANCE = 900

    /** 混色判定：颜色偏离「背景—主体色」连线不超过这个比例。 */
    private const val BLEND_TOLERANCE = 0.20f

    /** 出现次数不超过这个比例的色号才允许被吸附掉（防止吃掉大面积灰阶）。 */
    private const val RARE_RATIO = 0.005f
    private const val RARE_MIN = 3

    fun trim(cells: IntArray, width: Int, height: Int, palette: Palette): Result {
        if (width <= 0 || height <= 0 || cells.size < width * height) {
            return Result(cells, 0, 0)
        }
        val slotCount = (cells.maxOrNull() ?: -1) + 1
        if (slotCount <= 0) return Result(cells, 0, 0)

        // 注意：这里用 0 当"无此色号"的哨兵。纯白 #FFFFFF 的 Int 值正好是 -1，
        // 拿 -1 当哨兵会把白色背景误判成无效色号。
        val argb = IntArray(slotCount) { idx ->
            if (idx < 0) 0 else palette.get(idx)?.argb ?: 0
        }

        // —— 1. 边界上最常见的颜色 = 背景 ——
        val borderVotes = HashMap<Int, Int>(16)
        fun vote(i: Int) {
            val c = cells[i]
            if (c >= 0) borderVotes[c] = (borderVotes[c] ?: 0) + 1
        }
        for (x in 0 until width) {
            vote(x)
            if (height > 1) vote((height - 1) * width + x)
        }
        for (y in 0 until height) {
            vote(y * width)
            if (width > 1) vote(y * width + width - 1)
        }
        val bg = borderVotes.maxByOrNull { it.value }?.key ?: return Result(cells, 0, 0)
        val bgArgb = argb[bg]
        if (bgArgb == 0) return Result(cells, 0, 0)

        // —— 2. 从四边洪水填充，找出"图外" ——
        val outside = BooleanArray(width * height)
        val queue = IntArray(width * height)
        var head = 0
        var tail = 0

        fun isBgLike(i: Int): Boolean {
            val c = cells[i]
            if (c < 0) return false
            if (c == bg) return true
            val a = argb[c]
            if (a == 0) return false
            return weightedDistance(a, bgArgb) < BG_TOLERANCE
        }

        fun seed(i: Int) {
            if (!outside[i] && isBgLike(i)) {
                outside[i] = true
                queue[tail++] = i
            }
        }
        for (x in 0 until width) {
            seed(x)
            if (height > 1) seed((height - 1) * width + x)
        }
        for (y in 0 until height) {
            seed(y * width)
            if (width > 1) seed(y * width + width - 1)
        }
        while (head < tail) {
            val i = queue[head++]
            val x = i % width
            val y = i / width
            if (x > 0) seed(i - 1)
            if (x < width - 1) seed(i + 1)
            if (y > 0) seed(i - width)
            if (y < height - 1) seed(i + width)
        }

        // —— 3. 图外置空 ——
        val out = cells.copyOf()
        var removed = 0
        for (i in out.indices) {
            if (outside[i] && out[i] >= 0) {
                out[i] = -1
                removed++
            }
        }
        if (removed == 0) return Result(cells, 0, 0)

        // —— 4. 边缘吸附：把紧贴图外的一圈混色并到主体色 ——
        val counts = IntArray(slotCount)
        var beads = 0
        for (c in out) if (c >= 0) {
            counts[c]++
            beads++
        }
        val rareLimit = maxOf(RARE_MIN, (beads * RARE_RATIO).toInt())

        var snapped = 0
        val snapshot = out.copyOf()
        for (y in 0 until height) {
            for (x in 0 until width) {
                val i = y * width + x
                val c = snapshot[i]
                if (c < 0 || outside[i]) continue
                if (counts[c] > rareLimit) continue
                // 必须紧贴图外
                var touchesOutside = false
                // 邻居里最常见的主体色
                val votes = HashMap<Int, Int>(8)
                for (dy in -1..1) {
                    for (dx in -1..1) {
                        if (dx == 0 && dy == 0) continue
                        val nx = x + dx
                        val ny = y + dy
                        if (nx < 0 || ny < 0 || nx >= width || ny >= height) continue
                        val ni = ny * width + nx
                        if (outside[ni]) {
                            touchesOutside = true
                        } else {
                            val nc = snapshot[ni]
                            if (nc >= 0 && nc != c) votes[nc] = (votes[nc] ?: 0) + 1
                        }
                    }
                }
                if (!touchesOutside) continue
                val main = votes.maxByOrNull { it.value }?.key ?: continue
                if (isBlendBetween(argb[c], bgArgb, argb[main])) {
                    out[i] = main
                    snapped++
                }
            }
        }

        if (removed == 0 && snapped == 0) return Result(cells, 0, 0)
        return Result(out, removed, snapped)
    }

    private fun isBlendBetween(self: Int, a: Int, b: Int): Boolean {
        if (self == 0 || a == 0 || b == 0) return false
        val ab = weightedDistance(a, b)
        if (ab < 400) return false
        val span = weightedDistance(a, self) + weightedDistance(self, b) - ab
        return span.toFloat() / ab.toFloat() <= BLEND_TOLERANCE
    }

    private fun weightedDistance(x: Int, y: Int): Int {
        val dr = ((x shr 16) and 0xFF) - ((y shr 16) and 0xFF)
        val dg = ((x shr 8) and 0xFF) - ((y shr 8) and 0xFF)
        val db = (x and 0xFF) - (y and 0xFF)
        return 2 * dr * dr + 3 * dg * dg + 4 * db * db
    }
}
