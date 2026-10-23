package com.pindou.app.data

import kotlin.math.min

/**
 * 把图纸按 29×29 / 58×58 的拼豆板切块。
 * 板号按行优先：先从左到右，再从上到下。
 */
object BoardLayout {

    data class Bounds(
        val col0: Int,
        val row0: Int,
        val width: Int,
        val height: Int,
    )

    fun normalizeSpan(span: Int): Int = if (span == 58) 58 else 29

    fun columns(width: Int, span: Int): Int {
        val s = normalizeSpan(span)
        return if (width <= 0) 0 else (width + s - 1) / s
    }

    fun rows(height: Int, span: Int): Int {
        val s = normalizeSpan(span)
        return if (height <= 0) 0 else (height + s - 1) / s
    }

    fun count(width: Int, height: Int, span: Int): Int =
        columns(width, span) * rows(height, span)

    fun indexAt(cellIndex: Int, width: Int, span: Int): Int {
        if (cellIndex < 0 || width <= 0) return -1
        val s = normalizeSpan(span)
        val col = cellIndex % width
        val row = cellIndex / width
        return (row / s) * columns(width, s) + (col / s)
    }

    fun bounds(index: Int, width: Int, height: Int, span: Int): Bounds? {
        val s = normalizeSpan(span)
        val cols = columns(width, s)
        if (index !in 0 until count(width, height, s)) return null
        val boardCol = index % cols
        val boardRow = index / cols
        return Bounds(
            col0 = boardCol * s,
            row0 = boardRow * s,
            width = min(s, width - boardCol * s),
            height = min(s, height - boardRow * s),
        )
    }
}
