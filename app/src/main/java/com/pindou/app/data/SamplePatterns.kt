package com.pindou.app.data

/**
 * 首次启动时放一张示例图纸，让图纸库不是空的，也让「点开 → 看预览 → 算豆数」这条链路可验证。
 * 用隐式方程画心形，不依赖任何图片资源。
 */
object SamplePatterns {

    private const val WIDTH = 19
    private const val HEIGHT = 19

    fun heart(palette: Palette, now: Long = System.currentTimeMillis()): Pattern? {
        val outline = palette.indexOf("F4")
        val fill = palette.indexOf("F2")
        val highlight = palette.indexOf("E2")
        if (outline < 0 || fill < 0 || highlight < 0) return null

        fun inside(cx: Int, cy: Int): Boolean {
            val x = (cx + 0.5f) / WIDTH * 2.6f - 1.3f
            val y = 1.35f - (cy + 0.5f) / HEIGHT * 2.6f
            val a = x * x + y * y - 1f
            return a * a * a - x * x * y * y * y <= 0f
        }

        val cells = IntArray(WIDTH * HEIGHT) { -1 }
        for (cy in 0 until HEIGHT) {
            for (cx in 0 until WIDTH) {
                if (!inside(cx, cy)) continue
                val edge = !inside(cx - 1, cy) || !inside(cx + 1, cy) ||
                    !inside(cx, cy - 1) || !inside(cx, cy + 1)
                // 左上角一点高光，斜着三颗，像反光
                val isHighlight = !edge && ((cx == 4 && cy == 6) || (cx == 5 && cy == 5) || (cx == 6 && cy == 4))
                cells[cy * WIDTH + cx] = when {
                    edge -> outline
                    isHighlight -> highlight
                    else -> fill
                }
            }
        }

        return Pattern(
            id = Pattern.newId(),
            name = "示例 · 心形",
            width = WIDTH,
            height = HEIGHT,
            paletteId = palette.id,
            cells = cells,
            createdAt = now,
            updatedAt = now,
        )
    }
}
