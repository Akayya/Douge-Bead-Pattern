package com.pindou.app.data

/**
 * 把一张图纸压到 N 色：保留用量最多的 N 个色号，其余色号的格子改投到最近的保留色。
 * 用于「色太多拼不动」时一键减色，比重转一张图快，也不改变构图。
 */
object PatternReducer {

    data class Result(
        val cells: IntArray,
        val colorCount: Int,
        val changed: Int,
        val mergedColors: Int,
    )

    fun reduce(
        cells: IntArray,
        width: Int,
        height: Int,
        palette: Palette,
        targetColors: Int,
    ): Result {
        val counts = HashMap<Int, Int>()
        for (v in cells) if (v >= 0) counts[v] = (counts[v] ?: 0) + 1
        val sorted = counts.entries.sortedByDescending { it.value }
        if (sorted.size <= targetColors) {
            return Result(cells.copyOf(), sorted.size, 0, 0)
        }
        val keep = sorted.take(targetColors).map { it.key }.toIntArray()
        val keepArgb = IntArray(keep.size) { palette.get(keep[it])?.argb ?: 0 }

        // 被并掉的色号 -> 最近的保留色号下标
        val mapping = HashMap<Int, Int>()
        for (e in sorted.drop(targetColors)) {
            val src = palette.get(e.key)?.argb ?: continue
            val r = (src shr 16) and 0xFF
            val g = (src shr 8) and 0xFF
            val b = src and 0xFF
            var best = 0
            var bestD = Long.MAX_VALUE
            for (i in keepArgb.indices) {
                val d = ImageToPattern.distance(keepArgb[i], r, g, b).toLong()
                if (d < bestD) {
                    bestD = d
                    best = i
                }
            }
            mapping[e.key] = keep[best]
        }

        val out = IntArray(cells.size)
        var changed = 0
        for (i in cells.indices) {
            val v = cells[i]
            if (v < 0) {
                out[i] = v
                continue
            }
            val to = mapping[v]
            if (to != null) {
                out[i] = to
                changed++
            } else {
                out[i] = v
            }
        }
        return Result(out, sorted.size - mapping.size, changed, mapping.size)
    }
}
