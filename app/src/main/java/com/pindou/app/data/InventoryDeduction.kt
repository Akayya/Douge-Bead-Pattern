package com.pindou.app.data

/**
 * 拼完一张图后，从豆仓扣掉用量。
 *
 * 只扣记过库存的色号；库存不足时扣到 0，缺口单独统计，不产生负数。
 * 没记过库存的色号保持“不追踪”，不会被当成 0 或负数。
 */
object InventoryDeduction {

    data class Line(
        val code: String,
        val needed: Int,
        val stock: Int,
        val after: Int,
        val shortage: Int,
    )

    data class Plan(
        val lines: List<Line> = emptyList(),
        val untrackedColors: Int = 0,
        val untrackedBeads: Int = 0,
    ) {
        val trackedColors: Int get() = lines.size
        val neededBeads: Int get() = lines.sumOf { it.needed }
        val deductedBeads: Int get() = neededBeads - shortBeads
        val shortColors: Int get() = lines.count { it.shortage > 0 }
        val shortBeads: Int get() = lines.sumOf { it.shortage }

        /** 扣减后各色号的新库存，可直接交给 InventoryStore.setAll。 */
        val after: Map<String, Int> get() = lines.associate { it.code to it.after }
    }

    fun plan(usage: List<ColorUsage>, stockOf: (code: String) -> Int?): Plan {
        val lines = ArrayList<Line>(usage.size)
        var untrackedColors = 0
        var untrackedBeads = 0
        for (item in usage) {
            if (item.count <= 0) continue
            val stock = stockOf(item.color.code)
            if (stock == null) {
                untrackedColors++
                untrackedBeads += item.count
                continue
            }
            val safeStock = stock.coerceAtLeast(0)
            val after = (safeStock - item.count).coerceAtLeast(0)
            lines.add(
                Line(
                    code = item.color.code,
                    needed = item.count,
                    stock = safeStock,
                    after = after,
                    shortage = (item.count - safeStock).coerceAtLeast(0),
                ),
            )
        }
        return Plan(lines, untrackedColors, untrackedBeads)
    }
}
