package com.pindou.app.data

/**
 * 图纸的画布变换：裁掉空白边、四周加边、旋转、翻转。
 * 这些操作都会改变 cells 的坐标，施工进度（done）不再对应，调用方需要清空进度。
 */
object PatternTransform {

    data class Result(
        val cells: IntArray,
        val width: Int,
        val height: Int,
        /** null 表示没有任何改动 */
        val label: String? = null,
    ) {
        val changed: Boolean get() = label != null
    }

    /** 裁掉上下左右全空的整行整列 */
    fun trimBlank(cells: IntArray, width: Int, height: Int): Result {
        var top = height
        var bottom = -1
        var left = width
        var right = -1
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (cells[y * width + x] >= 0) {
                    if (y < top) top = y
                    if (y > bottom) bottom = y
                    if (x < left) left = x
                    if (x > right) right = x
                }
            }
        }
        if (bottom < 0) return Result(cells, width, height, null)
        if (top == 0 && bottom == height - 1 && left == 0 && right == width - 1) {
            return Result(cells, width, height, null)
        }
        val newW = right - left + 1
        val newH = bottom - top + 1
        val out = IntArray(newW * newH) { -1 }
        for (y in 0 until newH) {
            for (x in 0 until newW) {
                out[y * newW + x] = cells[(y + top) * width + (x + left)]
            }
        }
        return Result(out, newW, newH, "裁掉空白边")
    }

    /** 四周各加 1 格空位 */
    fun pad(cells: IntArray, width: Int, height: Int): Result {
        val newW = width + 2
        val newH = height + 2
        val out = IntArray(newW * newH) { -1 }
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[(y + 1) * newW + (x + 1)] = cells[y * width + x]
            }
        }
        return Result(out, newW, newH, "四周各加 1 格")
    }

    /** 顺时针旋转 90°：宽高互换 */
    fun rotateCw(cells: IntArray, width: Int, height: Int): Result {
        val newW = height
        val newH = width
        val out = IntArray(newW * newH)
        for (y in 0 until height) {
            for (x in 0 until width) {
                val nx = height - 1 - y
                val ny = x
                out[ny * newW + nx] = cells[y * width + x]
            }
        }
        return Result(out, newW, newH, "顺时针旋转 90°")
    }

    /** 水平翻转 */
    fun flipHorizontal(cells: IntArray, width: Int, height: Int): Result {
        val out = IntArray(cells.size)
        for (y in 0 until height) {
            for (x in 0 until width) {
                out[y * width + (width - 1 - x)] = cells[y * width + x]
            }
        }
        return Result(out, width, height, "水平翻转")
    }
}
