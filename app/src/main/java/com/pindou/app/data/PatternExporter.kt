package com.pindou.app.data

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 把图纸画成一张 PNG：带网格、29 格分区线、底部色号清单。
 * 存到相册（Pictures/豆格），不申请存储权限：Android 10+ 用 MediaStore 插入自己创建的图片，10 以下落到应用私有目录再通知相册。
 */
object PatternExporter {

    data class Result(val savedAs: String, val path: String, val uri: Uri?)

    private const val GRID = 0xFFD6D6DD.toInt()
    private const val BOARD = 0xCC4A4A55.toInt()
    private const val EMPTY = 0xFFF4F4F8.toInt()

    fun exportPng(
        context: Context,
        pattern: Pattern,
        palette: Palette,
        cellPx: Int = 26,
    ): Result {
        val usage = pattern.usage(palette)
        val margin = cellPx
        val header = cellPx * 2
        val legendRow = 30
        val legendH = legendRow * usage.size + 18

        val bmpW = pattern.width * cellPx + margin * 2
        val bmpH = pattern.height * cellPx + margin * 2 + header + legendH

        val bmp = Bitmap.createBitmap(bmpW, bmpH, Bitmap.Config.ARGB_8888)
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

        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val fileName = "豆格-$stamp.png"
        return save(context, bmp, fileName)
    }

    private fun save(context: Context, bmp: Bitmap, fileName: String): Result {
        // minSdk 31：只用 MediaStore，不再有老系统兜底分支
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
        bmp.recycle()
        return Result("相册/Pictures/豆格/$fileName", "Pictures/豆格/$fileName", uri)
    }
}
