package com.pindou.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把图纸画成一张 PNG：带网格、29 格分区线、底部色号清单。
 * 导出走 MediaStore 存相册（Pictures/豆格），分享走 cache + FileProvider；都不申请存储权限。
 */
object PatternExporter {

    data class Result(val savedAs: String, val path: String, val uri: Uri?, val cellPx: Int)

    const val PREFERRED_CELL_PX = 26
    private const val MIN_CELL_PX = 10

    // 12M 像素 ≈ 48MB ARGB_8888；再大低端机导出时容易 OOM。
    private const val MAX_PIXELS = 12_000_000L

    private const val GRID = 0xFFD6D6DD.toInt()
    private const val BOARD = 0xCC4A4A55.toInt()
    private const val EMPTY = 0xFFF4F4F8.toInt()

    /** 返回 null 表示图纸太大，连最小格子尺寸都放不进内存预算。 */
    fun exportPng(
        context: Context,
        pattern: Pattern,
        palette: Palette,
    ): Result? {
        val rendered = renderPng(pattern, palette) ?: return null
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "豆格-$stamp.png"
        return save(context, rendered.bitmap, fileName, rendered.cellPx)
    }

    /** 生成一张临时 PNG 供 ACTION_SEND 分享；返回 null 表示图纸太大。 */
    fun sharePng(
        context: Context,
        pattern: Pattern,
        palette: Palette,
    ): Uri? {
        val rendered = renderPng(pattern, palette) ?: return null
        val bmp = rendered.bitmap
        val dir = File(context.cacheDir, "share").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val file = File(dir, "豆格-$stamp.png")
        try {
            file.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } finally {
            bmp.recycle()
        }
        return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    }

    /**
     * 在内存预算内选最大的格子像素。
     * 尺寸按最终位图算，包含边距、标题和底部色号清单，不只算格子区域。
     */
    internal fun fitCellPx(width: Int, height: Int, colorCount: Int): Int? {
        val legend = 30L * colorCount + 18L
        for (cellPx in PREFERRED_CELL_PX downTo MIN_CELL_PX) {
            val bmpW = width.toLong() * cellPx + 2L * cellPx
            val bmpH = height.toLong() * cellPx + 4L * cellPx + legend
            if (bmpW <= Int.MAX_VALUE && bmpH <= Int.MAX_VALUE && bmpW * bmpH <= MAX_PIXELS) {
                return cellPx
            }
        }
        return null
    }

    private data class Rendered(val bitmap: Bitmap, val cellPx: Int)

    private fun renderPng(pattern: Pattern, palette: Palette): Rendered? {
        val usage = pattern.usage(palette)
        val cellPx = fitCellPx(pattern.width, pattern.height, usage.size) ?: return null
        val margin = cellPx
        val header = cellPx * 2
        val legendRow = 30
        val legendH = legendRow * usage.size + 18

        val bmpW = pattern.width * cellPx + margin * 2
        val bmpH = pattern.height * cellPx + margin * 2 + header + legendH

        val bmp = try {
            Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return null
        }
        val canvas = Canvas(bmp)
        canvas.drawColor(0xFFFFFFFF.toInt())

        val fill = Paint().apply { isAntiAlias = false }

        // 标题
        val title = Paint().apply {
            isAntiAlias = true
            color = 0xFF1B1B1F.toInt()
            textSize = cellPx.toFloat() * 0.9f
            typeface = Typeface.DEFAULT_BOLD
        }
        canvas.drawText(pattern.name, margin.toFloat(), margin.toFloat() + cellPx * 0.9f, title)
        val subtitle = Paint().apply {
            isAntiAlias = true
            color = 0xFF6B6B76.toInt()
            textSize = cellPx.toFloat() * 0.62f
        }
        canvas.drawText(
            "${pattern.width} × ${pattern.height} 格 · ${pattern.beadCount} 颗 · ${usage.size} 色",
            margin.toFloat(),
            margin.toFloat() + cellPx * 1.65f,
            subtitle,
        )

        val originX = margin.toFloat()
        val originY = (margin + header).toFloat()

        // 空格先铺底色
        fill.color = EMPTY
        for (y in 0 until pattern.height) {
            for (x in 0 until pattern.width) {
                if (pattern.cells[y * pattern.width + x] < 0) {
                    canvas.drawRect(
                        originX + x * cellPx,
                        originY + y * cellPx,
                        originX + (x + 1) * cellPx,
                        originY + (y + 1) * cellPx,
                        fill,
                    )
                }
            }
        }

        // 豆子
        for (y in 0 until pattern.height) {
            for (x in 0 until pattern.width) {
                val v = pattern.cells[y * pattern.width + x]
                if (v < 0) continue
                val argb = palette.get(v)?.argb ?: continue
                fill.color = argb
                canvas.drawRect(
                    originX + x * cellPx,
                    originY + y * cellPx,
                    originX + (x + 1) * cellPx,
                    originY + (y + 1) * cellPx,
                    fill,
                )
            }
        }

        // 网格
        val grid = Paint().apply {
            isAntiAlias = false
            color = GRID
            strokeWidth = 1f
        }
        val board = Paint().apply {
            isAntiAlias = false
            color = BOARD
            strokeWidth = (cellPx * 0.12f).coerceAtLeast(2f)
        }
        val right = originX + pattern.width * cellPx
        val bottom = originY + pattern.height * cellPx
        for (x in 0..pattern.width) {
            val px = originX + x * cellPx
            val isBoard = x > 0 && x < pattern.width && x % 29 == 0
            canvas.drawLine(px, originY, px, bottom, if (isBoard) board else grid)
        }
        for (y in 0..pattern.height) {
            val py = originY + y * cellPx
            val isBoard = y > 0 && y < pattern.height && y % 29 == 0
            canvas.drawLine(originX, py, right, py, if (isBoard) board else grid)
        }

        // 色号清单
        val code = Paint().apply {
            isAntiAlias = true
            color = 0xFF1B1B1F.toInt()
            textSize = 22f
            typeface = Typeface.DEFAULT_BOLD
        }
        val body = Paint().apply {
            isAntiAlias = true
            color = 0xFF3C3C44.toInt()
            textSize = 21f
        }
        val swatchBorder = Paint().apply {
            isAntiAlias = false
            style = Paint.Style.STROKE
            strokeWidth = 1f
            color = 0xFFB0B0B8.toInt()
        }
        var ly = bottom + 34f
        for (item in usage) {
            fill.color = item.color.argb
            canvas.drawRect(margin.toFloat(), ly - 16f, margin + 22f, ly + 6f, fill)
            canvas.drawRect(margin.toFloat(), ly - 16f, margin + 22f, ly + 6f, swatchBorder)
            canvas.drawText(item.color.code, margin + 34f, ly, code)
            canvas.drawText(item.color.hex, margin + 130f, ly, body)
            canvas.drawText("${item.count} 颗", margin + 290f, ly, body)
            ly += legendRow
        }

        return Rendered(bmp, cellPx)
    }

    private fun save(context: Context, bmp: Bitmap, fileName: String, cellPx: Int): Result {
        try {
            // minSdk 30：MediaStore 分区存储从 API 29 起可用，不需要存储权限
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/豆格")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("插入相册失败")
            resolver.openOutputStream(uri)?.use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                ?: throw IOException("打不开输出流")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            return Result("相册/Pictures/豆格/$fileName", "Pictures/豆格/$fileName", uri, cellPx)
        } finally {
            bmp.recycle()
        }
    }
}
