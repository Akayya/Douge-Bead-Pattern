package com.pindou.app.data

/**
 * 自定义色板 CSV：一枚色号一行，第一列色号，第二列 HEX。
 * 带表头时认「色号/编号/code」和「HEX/颜色/色值/color」，否则默认前两列。
 */
object PaletteCsv {

    data class Parsed(
        val title: String,
        val colors: List<BeadColor> = emptyList(),
        val invalidRows: Int = 0,
        val totalRows: Int = 0,
        val error: String? = null,
    )

    private val codeHeaders = setOf("色号", "编号", "颜色编号", "code", "color", "colorcode")
    private val hexHeaders = setOf("hex", "颜色", "色值", "颜色值", "colorhex", "rgb")
    private val codePattern = Regex("^[\\p{L}\\p{N}_-]{1,16}$")

    fun parse(text: String, title: String): Parsed {
        val lines = text.removePrefix("\uFEFF")
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .toList()
        if (lines.isEmpty()) return Parsed(title, error = "文件是空的")

        val first = InventoryCsv.parseRow(lines.first())
        val codeCol = first.indexOfFirst { normalize(it) in codeHeaders }
        val hexCol = first.indexOfFirst { normalize(it) in hexHeaders }
        if (codeCol >= 0 && hexCol < 0) {
            return Parsed(title, error = "表头里没有「HEX」或「颜色」列")
        }
        if (hexCol >= 0 && codeCol < 0) {
            return Parsed(title, error = "表头里没有「色号」列")
        }
        val hasHeader = codeCol >= 0 && hexCol >= 0
        val cCol = if (hasHeader) codeCol else 0
        val hCol = if (hasHeader) hexCol else 1

        val colors = LinkedHashMap<String, BeadColor>()
        var invalid = 0
        var total = 0
        for (line in lines.drop(if (hasHeader) 1 else 0)) {
            val row = InventoryCsv.parseRow(line)
            if (row.size <= maxOf(cCol, hCol)) {
                invalid++
                total++
                continue
            }
            val rawCode = row[cCol].trim()
            if (rawCode.isEmpty() || rawCode.startsWith("#")) continue
            total++
            val code = rawCode.uppercase()
            if (!codePattern.matches(code)) {
                invalid++
                continue
            }
            val hex = HexColor.normalize(row[hCol]) ?: run {
                invalid++
                continue
            }
            if (colors.containsKey(code)) {
                invalid++
                continue
            }
            colors[code] = BeadColor(code, hex, Palette.groupOf(code))
        }

        if (colors.isEmpty()) {
            return Parsed(title, invalidRows = invalid, totalRows = total, error = "没有可导入的色号")
        }
        if (colors.size > Palette.MAX_COLORS) {
            return Parsed(title, error = "一个色板最多 ${Palette.MAX_COLORS} 色")
        }
        return Parsed(
            title = title.trim().ifBlank { "自定义色板" }.take(30),
            colors = colors.values.toList(),
            invalidRows = invalid,
            totalRows = total,
        )
    }

    private fun normalize(raw: String): String =
        raw.trim().lowercase().replace(" ", "").replace("_", "")
}
