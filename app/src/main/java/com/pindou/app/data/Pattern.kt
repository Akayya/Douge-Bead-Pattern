package com.pindou.app.data

import java.util.UUID

/**
 * 一张图纸。cells 与 width*height 一一对应，值 = 色板里的下标，-1 表示空格（不放豆）。
 * done 记录施工模式里已打勾的格子下标，供进度恢复用。
 */
class Pattern(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val paletteId: String,
    val cells: IntArray,
    val createdAt: Long,
    val updatedAt: Long,
    val done: MutableSet<Int> = mutableSetOf(),
) {
    init {
        require(cells.size == width * height) { "cells.size=${cells.size} != $width*$height" }
    }

    val beadCount: Int get() = cells.count { it >= 0 }

    val doneCount: Int get() = done.count { it in cells.indices && cells[it] >= 0 }

    val progress: Float
        get() = if (beadCount == 0) 0f else doneCount.toFloat() / beadCount

    fun usage(palette: Palette): List<ColorUsage> {
        val counts = HashMap<Int, Int>()
        for (v in cells) if (v >= 0) counts[v] = (counts[v] ?: 0) + 1
        return counts.entries
            .sortedByDescending { it.value }
            .mapNotNull { e -> palette.get(e.key)?.let { ColorUsage(it, e.value) } }
    }

    companion object {
        fun newId(): String = UUID.randomUUID().toString().replace("-", "").take(12)
    }
}

data class ColorUsage(val color: BeadColor, val count: Int)
