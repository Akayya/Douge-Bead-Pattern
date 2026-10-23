package com.pindou.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.ceil

/**
 * A4 施工图 PDF：
 * 第 1 页是总览 + 色号清单（含成本），后面按拼豆板切块，
 * 每页固定 29×29（一块板）或 58×58（2×2 块板），页边界与板边界对齐，
 * 每页有页码、行列范围和拼接线，方便逐页拼起来施工。
 */
object PatternPdf {

    data class Result(val savedAs: String, val pages: Int)

    const val BOARD_SMALL = 29
    const val BOARD_LARGE = 58

    private const val A4_W = 595
    private const val A4_H = 842
    private const val MARGIN = 26f
    private const val HEADER = 44f
    private const val FOOTER = 26f

    fun export(
        context: Context,
        pattern: Pattern,
        palette: Palette,
        cost: CostSettings,
        boardSpan: Int = BOARD_SMALL,
    ): Result? {
        val span = normalizeSpan(boardSpan)
        val usage = pattern.usage(palette)
        val doc = PdfDocument()
        val text = Paint().apply { isAntiAlias = true; color = Color.BLACK }
        val fill = Paint().apply { isAntiAlias = false; style = Paint.Style.FILL }

        try {
            drawSummary(doc, pattern, usage, cost, span, text, fill)

            val tilesX = ceil(pattern.width.toFloat() / span).toInt()
            val tilesY = ceil(pattern.height.toFloat() / span).toInt()
            val totalTiles = tilesX * tilesY

            var index = 0
            for (ty in 0 until tilesY) {
                for (tx in 0 until tilesX) {
                    index++
                    val info = PdfDocument.PageInfo.Builder(A4_W, A4_H, index + 1).create()
                    val page = doc.startPage(info)
                    drawTile(
                        page.canvas, pattern, palette, text, fill,
                        pageNo = index, pageTotal = totalTiles,
                        tileX = tx, tileY = ty, span = span,
                    )
                    doc.finishPage(page)
                }
            }
            return save(context, doc, pattern)
        } finally {
            runCatching { doc.close() }
        }
    }

    private fun drawSummary(
        doc: PdfDocument,
        pattern: Pattern,
        usage: List<ColorUsage>,
        cost: CostSettings,
        span: Int,
        text: Paint,
        fill: Paint,
    ) {
        val info = PdfDocument.PageInfo.Builder(A4_W, A4_H, 1).create()
        val page = doc.startPage(info)
        val c = page.canvas

        var y = MARGIN + 22f
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 20f
        c.drawText(pattern.name, MARGIN, y, text)
        y += 18f
        text.typeface = Typeface.DEFAULT
        text.textSize = 10f
        text.color = Color.DKGRAY
        c.drawText(
            "${pattern.width} × ${pattern.height} 格 · ${pattern.beadCount} 颗 · ${usage.size} 色" +
                "  |  豆子 ${CostSettings.yuan(cost.priceOf(pattern.beadCount))}" +
                "  |  预计 ${CostSettings.duration(cost.minutesOf(pattern.beadCount))}",
            MARGIN, y, text,
        )
        y += 14f
        text.textSize = 8.5f
        c.drawText(
            "分页：每页 ${span}×${span}（${spanTitle(span)}），" +
                "共 ${pageCount(pattern.width, pattern.height, span) - 1} 页图纸",
            MARGIN, y, text,
        )
        text.color = Color.BLACK
        y += 16f
        fill.color = Color.parseColor("#33000000")
        c.drawRect(MARGIN, y, A4_W - MARGIN, y + 0.8f, fill)
        y += 16f

        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 12f
        c.drawText("取豆清单", MARGIN, y, text)
        text.typeface = Typeface.DEFAULT
        y += 14f

        // 两列排布
        val colW = (A4_W - MARGIN * 2) / 2f
        val rowH = 22f
        var row = 0
        for (item in usage) {
            val col = row / 26
            val line = row % 26
            val x0 = MARGIN + col * colW
            val yy = y + line * rowH
            fill.color = item.color.argb
            c.drawRect(x0, yy, x0 + 16f, yy + 16f, fill)
            fill.color = Color.parseColor("#B0B0B8")
            c.drawRect(x0, yy, x0 + 16f, yy + 0.8f, fill)
            c.drawRect(x0, yy + 15.2f, x0 + 16f, yy + 16f, fill)
            c.drawRect(x0, yy, x0 + 0.8f, yy + 16f, fill)
            c.drawRect(x0 + 15.2f, yy, x0 + 16f, yy + 16f, fill)

            text.textSize = 10f
            c.drawText(item.color.code, x0 + 22f, yy + 12f, text)
            text.textSize = 8f
            text.color = Color.GRAY
            c.drawText(item.color.hex, x0 + 58f, yy + 12f, text)
            text.color = Color.BLACK
            text.textSize = 10f
            c.drawText("${item.count} 颗", x0 + 112f, yy + 12f, text)
            text.textSize = 8f
            text.color = Color.GRAY
            c.drawText(CostSettings.yuan(cost.priceOf(item.count)), x0 + 152f, yy + 12f, text)
            text.color = Color.BLACK
            row++
        }

        // 页脚
        text.textSize = 8f
        text.color = Color.GRAY
        val footY = A4_H - MARGIN
        c.drawText("第 1 页 · 清单", MARGIN, footY, text)
        c.drawText("后面每页是 ${span}×${span}（${spanTitle(span)}），按页码顺序拼", A4_W - MARGIN - 240f, footY, text)
        doc.finishPage(page)
    }

    private fun drawTile(
        c: Canvas,
        pattern: Pattern,
        palette: Palette,
        text: Paint,
        fill: Paint,
        pageNo: Int,
        pageTotal: Int,
        tileX: Int,
        tileY: Int,
        span: Int,
    ) {
        val x0 = tileX * span
        val y0 = tileY * span
        val cols = minOf(span, pattern.width - x0)
        val rows = minOf(span, pattern.height - y0)

        val availW = A4_W - MARGIN * 2
        val availH = A4_H - MARGIN * 2 - HEADER - FOOTER
        // 整页按完整板宽排版：不足一板的末页也保持同样的格子和原点，拼页时不会错位
        val cell = minOf(availW / span, availH / span)
        val board = span * cell
        val gridLeft = MARGIN + (availW - board) / 2f
        // 各页统一从页眉下方起笔，末页不足一板时也保持相同原点，拼页不会错位
        val gridTop = MARGIN + HEADER + 8f

        // 页眉
        text.typeface = Typeface.create(Typeface.DEFAULT, Typeface.BOLD)
        text.textSize = 13f
        text.color = Color.BLACK
        c.drawText(pattern.name, MARGIN, MARGIN + 12f, text)
        text.typeface = Typeface.DEFAULT
        text.textSize = 9f
        text.color = Color.DKGRAY
        c.drawText(
            "图纸 $pageNo / $pageTotal · 列 ${x0 + 1}–${x0 + cols} · 行 ${y0 + 1}–${y0 + rows}" +
                " · 每页 $span 格",
            MARGIN, MARGIN + 26f, text,
        )
        text.color = Color.BLACK

        // 格子
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val v = pattern.cells[(y0 + y) * pattern.width + (x0 + x)]
                val left = gridLeft + x * cell
                val top = gridTop + y * cell
                if (v >= 0) {
                    fill.color = palette.get(v)?.argb ?: Color.WHITE
                    c.drawRect(left, top, left + cell, top + cell, fill)
                }
            }
        }

        // 空格填淡灰：打印后能一眼分开「白豆」和「不放豆」
        val emptyFill = Paint().apply {
            isAntiAlias = false
            color = Color.parseColor("#ECECF1")
        }
        for (y in 0 until rows) {
            for (x in 0 until cols) {
                val v = pattern.cells[(y0 + y) * pattern.width + (x0 + x)]
                if (v >= 0) continue
                val left = gridLeft + x * cell
                val top = gridTop + y * cell
                c.drawRect(left, top, left + cell, top + cell, emptyFill)
            }
        }

        // 网格线
        val thin = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeWidth = 0.4f
            color = Color.parseColor("#6690909C")
        }
        val thick = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeWidth = 1.3f
            color = Color.parseColor("#88101018")
        }
        for (gx in 0..cols) {
            val px = gridLeft + gx * cell
            val boardLine = (x0 + gx) % 29 == 0 && gx != 0
            c.drawLine(
                px, gridTop, px, gridTop + rows * cell,
                if (boardLine) thick else thin,
            )
        }
        for (gy in 0..rows) {
            val py = gridTop + gy * cell
            val boardLine = (y0 + gy) % 29 == 0 && gy != 0
            c.drawLine(
                gridLeft, py, gridLeft + cols * cell, py,
                if (boardLine) thick else thin,
            )
        }

        // 行列刻度：每 5 格标一次绝对行列号，拼的时候好数
        val tick = Paint().apply {
            isAntiAlias = true
            textSize = 6f
            color = Color.parseColor("#99101018")
        }
        tick.textAlign = Paint.Align.CENTER
        var cxTick = 0
        while (cxTick < cols) {
            c.drawText(
                "${x0 + cxTick + 1}",
                gridLeft + cxTick * cell + cell / 2f,
                gridTop - 4f,
                tick,
            )
            cxTick += 5
        }
        tick.textAlign = Paint.Align.RIGHT
        var ryTick = 0
        while (ryTick < rows) {
            c.drawText(
                "${y0 + ryTick + 1}",
                gridLeft - 3f,
                gridTop + ryTick * cell + cell * 0.7f,
                tick,
            )
            ryTick += 5
        }

        // 色号字（格子够大时才画）
        if (cell >= 13f) {
            val code = Paint().apply {
                isAntiAlias = true
                textSize = cell * 0.42f
                color = Color.parseColor("#66000000")
                textAlign = Paint.Align.CENTER
            }
            for (y in 0 until rows) {
                for (x in 0 until cols) {
                    val v = pattern.cells[(y0 + y) * pattern.width + (x0 + x)]
                    if (v < 0) continue
                    val cc = palette.get(v) ?: continue
                    val cx = gridLeft + x * cell + cell / 2f
                    val cy = gridTop + y * cell + cell / 2f + code.textSize / 3f
                    c.drawText(cc.code, cx, cy, code)
                }
            }
        }

        // 拼接提示
        text.textSize = 8f
        text.color = Color.GRAY
        val footY = A4_H - MARGIN
        c.drawText("第 ${pageNo + 1} 页 / 共 ${pageTotal + 1} 页", MARGIN, footY, text)
        val hint = buildString {
            if (tileX > 0) append("← 与左页拼接  ")
            if (tileY > 0) append("↑ 与上页拼接")
        }
        if (hint.isNotBlank()) {
            c.drawText(hint, A4_W - MARGIN - 130f, footY, text)
        }
        text.color = Color.BLACK
    }

    private fun save(context: Context, doc: PdfDocument, pattern: Pattern): Result? {
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val safeName = pattern.name.replace(Regex("[\\\\/:*?\"<>|]"), "_").take(24)
        val fileName = "豆格-$safeName-$stamp.pdf"
        val pages = doc.pages.size

        // minSdk 31：只用 MediaStore.Downloads
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
            put(MediaStore.MediaColumns.MIME_TYPE, "application/pdf")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/豆格")
            put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val resolver = context.contentResolver
        val uri: Uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        try {
            resolver.openOutputStream(uri)?.use { out ->
                doc.writeTo(out)
                out.flush()
            } ?: return null
        } catch (e: IOException) {
            return null
        }
        values.clear()
        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        return Result(savedAs = "下载/豆格/$fileName", pages = pages)
    }

    /** 供界面预估页数用，不用真的生成。返回含第 1 页清单在内的总页数。 */
    fun pageCount(pattern: Pattern, boardSpan: Int = BOARD_SMALL): Int =
        pageCount(pattern.width, pattern.height, boardSpan)

    internal fun pageCount(width: Int, height: Int, boardSpan: Int): Int {
        val span = normalizeSpan(boardSpan)
        val tilesX = ceil(width.toFloat() / span).toInt()
        val tilesY = ceil(height.toFloat() / span).toInt()
        return 1 + tilesX * tilesY
    }

    private fun normalizeSpan(boardSpan: Int): Int =
        if (boardSpan == BOARD_LARGE) BOARD_LARGE else BOARD_SMALL

    private fun spanTitle(span: Int): String =
        if (span == BOARD_LARGE) "2×2 块拼豆板" else "单块拼豆板"
}
