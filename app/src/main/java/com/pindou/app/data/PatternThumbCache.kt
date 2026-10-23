package com.pindou.app.data

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.util.LruCache
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream

/**
 * 库页缩略图缓存：按图纸内容渲染一次 256px 位图，内存与 filesDir/thumbs 各存一份。
 * 施工打勾只会改 updatedAt，cells 哈希不变，所以不会反复重建。
 * key 里带色板颜色指纹：校准后旧图自动失效，不再需要全量 clear；
 * 缓存改成在图纸实际显示时懒重建，旧指纹文件在下次渲染同一张图纸时清掉。
 */
object PatternThumbCache {

    private const val SIDE = 256
    private const val MAX_BYTES = 8 * 1024 * 1024

    private val memory = object : LruCache<String, Bitmap>(MAX_BYTES) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val renderLock = Mutex()

    private fun dir(context: Context): File =
        File(context.filesDir, "thumbs").apply { if (!exists()) mkdirs() }

    private fun key(pattern: Pattern, palette: Palette): String {
        val hash = pattern.cells.contentHashCode().toUInt().toString(16)
        return "${pattern.id}-${pattern.width}x${pattern.height}-${pattern.paletteId}-${palette.colorsFingerprint}-$hash"
    }

    /** 只在内存里查，不读盘，供首帧决定是否还需要占位绘制。 */
    fun memoryGet(pattern: Pattern, palette: Palette): Bitmap? = memory.get(key(pattern, palette))

    suspend fun load(context: Context, pattern: Pattern, palette: Palette): Bitmap? {
        val k = key(pattern, palette)
        memory.get(k)?.let { return it }
        return withContext(Dispatchers.IO) {
            renderLock.withLock {
                memory.get(k)?.let { return@withLock it }
                val file = File(dir(context), "$k.png")
                if (file.exists()) {
                    val cached = runCatching { android.graphics.BitmapFactory.decodeFile(file.absolutePath) }
                        .getOrNull()
                    if (cached != null) {
                        memory.put(k, cached)
                        return@withLock cached
                    }
                    file.delete()
                }
                val bitmap = runCatching { render(pattern, palette) }.getOrNull()
                    ?: return@withLock null
                runCatching {
                    FileOutputStream(file).use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    // 同一张图纸改过内容后，旧 key 的文件不会再用到
                    dir(context).listFiles { f ->
                        f.isFile && f.name.startsWith("${pattern.id}-") && f.name != file.name
                    }?.forEach { it.delete() }
                }
                memory.put(k, bitmap)
                bitmap
            }
        }
    }

    fun delete(context: Context, id: String) {
        memory.snapshot().keys
            .filter { it.startsWith("$id-") }
            .forEach { memory.remove(it) }
        dir(context).listFiles { f -> f.isFile && f.name.startsWith("$id-") }?.forEach { it.delete() }
    }

    private fun render(pattern: Pattern, palette: Palette): Bitmap {
        val bitmap = Bitmap.createBitmap(SIDE, SIDE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val cell = minOf(SIDE.toFloat() / pattern.width, SIDE.toFloat() / pattern.height)
        val originX = (SIDE - cell * pattern.width) / 2f
        val originY = (SIDE - cell * pattern.height) / 2f
        val paint = Paint().apply {
            isAntiAlias = false
            isFilterBitmap = false
        }
        for (y in 0 until pattern.height) {
            for (x in 0 until pattern.width) {
                val v = pattern.cells[y * pattern.width + x]
                if (v < 0) continue
                val argb = palette.get(v)?.argb ?: continue
                paint.color = argb
                val left = originX + x * cell
                val top = originY + y * cell
                // 多画 0.5px，避免相邻格子间出现 1px 透明缝
                canvas.drawRect(left, top, left + cell + 0.5f, top + cell + 0.5f, paint)
            }
        }
        return bitmap
    }
}
