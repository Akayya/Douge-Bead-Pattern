package com.pindou.app.data

/**
 * 库存 CSV 批量导入。
 *
 * 支持两种格式：
 * - 带表头：找到「色号」列与「库存」列（其次「数量」「颗数」等）；
 * - 不带表头：第一列色号，第二列数量。
 *
 * 空白数量表示「该色号不更新」，不会把库存清零；未知色号和坏行会跳过并在预览里报数。
 */
object InventoryCsv {

    data class Parsed(
        val counts: Map<String, Int> = emptyMap(),
        val unknown: List<String> = emptyList(),
        val invalidRows: Int = 0,
        val totalRows: Int = 0,
        val error: String? = null,
    )

    private val codeHeaders = setOf("色号", "编号", "颜色编号", "code", "color", "colorcode")
    private val countHeaders = listOf("库存", "数量", "颗数", "count", "stock", "qty")

    fun parse(text: String, palette: Palette): Parsed {
        val lines = text.removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return Parsed(error = "文件是空的")

        val first = parseRow(lines.first())
        val codeCol = first.indexOfFirst { normalize(it) in codeHeaders }
        val countCol = if (codeCol >= 0) findCountColumn(first) else -1
        val hasHeader = codeCol >= 0 && countCol >= 0
        if (codeCol >= 0 && countCol < 0) {
            return Parsed(error = "表头里没有「库存」或「数量」列")
        }
        val dataStart = if (hasHeader) 1 else 0
        val cCol = if (hasHeader) codeCol else 0
        val nCol = if (hasHeader) countCol else 1

        val counts = LinkedHashMap<String, Int>()
        val unknown = ArrayList<String>()
        var invalid = 0
        var total = 0
        for (line in lines.drop(dataStart)) {
            val row = parseRow(line)
            if (row.size <= maxOf(cCol, nCol)) {
                invalid++
                total++
                continue
            }
            val rawCode = row[cCol].trim()
            if (rawCode.isEmpty() || rawCode.startsWith("#")) continue
            total++
            val code = rawCode.uppercase()
            if (palette.indexOf(code) < 0) {
                unknown.add(code)
                continue
            }
            val rawCount = row[nCol].trim()
            if (rawCount.isEmpty()) continue
            val count = rawCount
                .removeSuffix("颗")
                .replace(",", "")
                .trim()
                .toIntOrNull()
            if (count == null || count < 0) {
                invalid++
                continue
            }
            counts[code] = count.coerceAtMost(999_999)
        }
        return Parsed(
            counts = counts,
            unknown = unknown,
            invalidRows = invalid,
            totalRows = total,
        )
    }

    private fun findCountColumn(header: List<String>): Int {
        for (name in countHeaders) {
            val index = header.indexOfFirst { normalize(it) == name }
            if (index >= 0) return index
        }
        return -1
    }

    private fun normalize(raw: String): String =
        raw.trim().lowercase().replace(" ", "").replace("_", "")

    internal fun parseRow(line: String): List<String> {
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var quoted = false
        var i = 0
        while (i < line.length) {
            val ch = line[i]
            if (quoted) {
                if (ch == '"') {
                    if (i + 1 < line.length && line[i + 1] == '"') {
                        cell.append('"')
                        i++
                    } else {
                        quoted = false
                    }
                } else {
                    cell.append(ch)
                }
            } else {
                when (ch) {
                    '"' -> quoted = true
                    ',', '\t' -> {
                        out.add(cell.toString())
                        cell.clear()
                    }
                    else -> cell.append(ch)
                }
            }
            i++
        }
        out.add(cell.toString())
        return out
    }
}
