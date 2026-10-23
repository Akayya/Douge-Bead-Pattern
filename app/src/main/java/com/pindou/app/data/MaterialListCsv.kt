package com.pindou.app.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * 材料单 CSV：按用量顺序列出色号、颗数、整袋数和成本，方便采购或对着表格备料。
 * 用 MediaStore 写「下载/豆格」，和 PNG/PDF 一样不需要存储权限。
 */
object MaterialListCsv {

    data class Result(val savedAs: String)

    fun export(
        context: Context,
        pattern: Pattern,
        palette: Palette,
        cost: CostSettings,
        stockOf: (String) -> Int?,
    ): Result? {
        val csv = buildCsv(pattern.usage(palette), cost.pricePerBead, stockOf)
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeName = pattern.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(24)
        val fileName = "豆格-材料单-$safeName-$stamp.csv"

        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "text/csv")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/豆格")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                // BOM：Windows 版 Excel 直接双击打开也不乱码
                out.write("\uFEFF".toByteArray(Charsets.UTF_8))
                out.write(csv.toByteArray(Charsets.UTF_8))
                out.flush()
            } ?: return null
        } catch (e: IOException) {
            return null
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Result(savedAs = "下载/豆格/$fileName")
    }

    internal fun buildCsv(
        usage: List<ColorUsage>,
        pricePerBead: Float,
        stockOf: (String) -> Int?,
    ): String {
        val sb = StringBuilder()
        sb.append("序号,色号,色系,HEX,颗数,袋数（100颗/袋）,单价（元/颗）,小计（元）,库存,还缺（颗）\r\n")
        var totalCount = 0
        var totalBags = 0
        var totalPrice = 0.0
        var totalMissing = 0
        var missingKnown = false
        usage.forEachIndexed { index, item ->
            val count = item.count
            val bags = ceil(count / 100.0).toInt()
            val price = count * pricePerBead.toDouble()
            val have = stockOf(item.color.code)
            val missing = if (have != null) (count - have).coerceAtLeast(0) else 0
            if (have != null) missingKnown = true
            totalCount += count
            totalBags += bags
            totalPrice += price
            totalMissing += missing
            sb.append(index + 1).append(',')
            sb.append(cell(item.color.code)).append(',')
            sb.append(cell(PaletteGroups.label(item.color.group))).append(',')
            sb.append(cell(item.color.hex)).append(',')
            sb.append(count).append(',')
            sb.append(bags).append(',')
            sb.append(money(pricePerBead.toDouble())).append(',')
            sb.append(money(price)).append(',')
            sb.append(have?.toString().orEmpty()).append(',')
            sb.append(if (have == null) "" else missing.toString()).append("\r\n")
        }
        sb.append("合计,,,,").append(totalCount).append(',')
        sb.append(totalBags).append(",,").append(money(totalPrice)).append(',')
        sb.append(',')
        sb.append(if (missingKnown) totalMissing.toString() else "").append("\r\n")
        return sb.toString()
    }

    private fun money(v: Double): String = String.format(Locale.US, "%.2f", v)

    private fun cell(raw: String): String {
        if (raw.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return raw
        return "\"" + raw.replace("\"", "\"\"") + "\""
    }
}
