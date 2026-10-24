package com.pindou.app.data

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import android.graphics.RectF
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * 图片转图纸。
 *
 * 流程：裁剪 → 决定输出格数（按原图网格 or 长边格数）→ 每格取「主色」→ 在"我有"的色号里
 * 做最近色映射 → 按用量挑前 N 色 → 被砍掉的格子改投到保留色。
 *
 * 两个关键决定：
 * 1. 每格取**主色**（出现次数最多的那个颜色）而不是平均色。像素画/截图的每个格子本来是纯色，
 *    平均色会在细线、轮廓边缘平均出灰色，正是「细小处出现异常灰色色块」的来源。
 *    只有主色占比不到一半时（照片），才退回平均色，否则照片会变成色斑。
 * 2. 不用 k-means。色板是固定的几百个离散色号，最终每格都必须落到真实色号上，
 *    直接用色板当聚类中心既省事又不会产生"色板上不存在的中间色"。
 */
object ImageToPattern {

    /**
     * 两个色号"看起来一样"的阈值（加权 RGB 距离的平方，约合每通道差 16 左右）。
     * 低于它的颜色会被合并成一个，省下色号预算，也顺手吃掉压缩噪点。
     */
    private const val MERGE_DISTANCE = 2400

    /**
     * 1:1 采样时每格从四边各收进来多少（占格子边长的比例）。
     * 0.18 → 只取格心 64%，避开抗锯齿边缘；太小会带回灰边，太大细描边可能被切。
     */
    private const val INSET_RATIO = 0.18f

    /**
     * 取像素的绝对上限。实际还会按输出格数收紧到每格 8×8 个采样点：
     * 4000 万像素的照片整张取出来光 IntArray 就 160MB，手机必 OOM。
     */
    private const val MAX_SAMPLE_PIXELS = 2_000_000
    private const val SAMPLES_PER_CELL = 8

    data class Result(
        val width: Int,
        val height: Int,
        val cells: IntArray,
        val colorCount: Int,
        val beadCount: Int,
        /** 是否按原图网格 1:1 采样（false = 缩放后逐格采样） */
        val alignedToSource: Boolean = false,
    )

    /**
     * @param source   原图（任意尺寸）
     * @param crop     归一化裁剪区（0..1，相对于原图）
     * @param palette  色板，返回的 cells 里存的是 palette 下标
     * @param allowed  允许使用的 palette 下标（即"我有"的色号）；为空则退回全色板
     * @param longSide 目标长边格数（grid 为 null 时生效）
     * @param maxColors 最多用多少种颜色
     * @param grid     识别到的原图网格；给了就按它 1:1 采样，不再缩放
     */
    fun build(
        source: Bitmap,
        crop: RectF,
        palette: Palette,
        allowed: IntArray,
        longSide: Int,
        maxColors: Int,
        grid: PixelGridDetector.Grid? = null,
    ): Result? {
        val candidates = if (allowed.isEmpty()) IntArray(palette.count) { it } else allowed
        if (candidates.isEmpty()) return null

        val bounds = cropRect(source, crop) ?: return null
        val croppedW = bounds.width()
        val croppedH = bounds.height()
        if (croppedW < 1 || croppedH < 1) return null

        val useGrid = grid != null && grid.cols >= 2 && grid.rows >= 2
        val outW: Int
        val outH: Int
        if (useGrid) {
            outW = grid!!.cols
            outH = grid!!.rows
        } else {
            val (w, h) = targetSize(croppedW, croppedH, longSide)
            outW = w
            outH = h
        }
        if (outW < 1 || outH < 1) return null

        // 采样分辨率跟着输出格数走：每格 8×8 个点足够区分主色和均值，
        // 拖尺寸滑块时也不用每次把几千万像素重读一遍。
        val cap = min(
            MAX_SAMPLE_PIXELS.toLong(),
            max(96, outW * SAMPLES_PER_CELL).toLong() * max(96, outH * SAMPLES_PER_CELL),
        )
        val full = bounds.left == 0 && bounds.top == 0 &&
            bounds.right == source.width && bounds.bottom == source.height
        val region: Bitmap
        var scale = 1f
        if (croppedW.toLong() * croppedH > cap) {
            // 裁剪和缩放一次做完：先 createBitmap 裁出整块再缩放的话，
            // 中间那张大图本身就可能是几十 MB。
            val factor = sqrt(cap.toDouble() / (croppedW.toDouble() * croppedH)).toFloat()
            val nw = max(1, (croppedW * factor).roundToInt())
            val nh = max(1, (croppedH * factor).roundToInt())
            scale = nw.toFloat() / croppedW
            val matrix = Matrix().apply {
                setScale(scale, nh.toFloat() / croppedH)
            }
            region = runCatching {
                Bitmap.createBitmap(source, bounds.left, bounds.top, croppedW, croppedH, matrix, true)
            }.getOrNull() ?: return null
        } else if (full) {
            region = source
        } else {
            region = runCatching {
                Bitmap.createBitmap(source, bounds.left, bounds.top, croppedW, croppedH)
            }.getOrNull() ?: return null
        }
        val cw = region.width
        val ch = region.height
        if (cw < 1 || ch < 1) {
            if (region !== source) region.recycle()
            return null
        }

        val pixels = IntArray(cw * ch)
        region.getPixels(pixels, 0, cw, 0, 0, cw, ch)
        if (region !== source) region.recycle()

        // 每格取一个代表色（主色优先，照片退回平均色）
        val rep = IntArray(outW * outH)
        val counter = HashMap<Int, Int>(256)
        val stepX = if (useGrid) grid!!.cellX * scale else cw.toFloat() / outW
        val stepY = if (useGrid) grid!!.cellY * scale else ch.toFloat() / outH
        val originX = if (useGrid) grid!!.offsetX * scale else 0f
        val originY = if (useGrid) grid!!.offsetY * scale else 0f
        // 按原图 1:1 采样时，格子边界那一两圈像素常常是抗锯齿/缩放的混合色，
        // 取格心 64% 的区域能避开它们；否则描边外侧会拖出一圈灰边。
        val insetX = if (useGrid) (stepX * INSET_RATIO).roundToInt() else 0
        val insetY = if (useGrid) (stepY * INSET_RATIO).roundToInt() else 0
        for (j in 0 until outH) {
            var y0 = (originY + j * stepY).roundToInt().coerceIn(0, ch - 1)
            var y1 = (originY + (j + 1) * stepY).roundToInt().coerceIn(y0 + 1, ch)
            if (insetY > 0) {
                y0 = (y0 + insetY).coerceIn(y0, y1 - 1)
                y1 = (y1 - insetY).coerceIn(y0 + 1, y1)
            }
            for (i in 0 until outW) {
                var x0 = (originX + i * stepX).roundToInt().coerceIn(0, cw - 1)
                var x1 = (originX + (i + 1) * stepX).roundToInt().coerceIn(x0 + 1, cw)
                if (insetX > 0) {
                    x0 = (x0 + insetX).coerceIn(x0, x1 - 1)
                    x1 = (x1 - insetX).coerceIn(x0 + 1, x1)
                }
                rep[j * outW + i] = representative(pixels, cw, x0, y0, x1, y1, counter)
            }
        }

        // 每一格先落到最近的"我有"色号（同一个 RGB 只算一次）
        val n = rep.size
        val nearestCache = HashMap<Int, Int>(1024)
        val slot = IntArray(n) { -1 }          // 在 candidates 里的位置，-2 = 透明空格
        val histogram = IntArray(candidates.size)
        for (i in 0 until n) {
            val p = rep[i]
            if (p == 0) { slot[i] = -2; continue }
            val rgb = p and 0xFFFFFF
            val hit = nearestCache.getOrPut(rgb) { nearestIndex(candidates, palette, rgb) }
            slot[i] = hit
            histogram[hit]++
        }

        // 按用量取前 maxColors 个，但"太像"的颜色只留一个：
        // 色板里有十几个近乎一样的白/黑/灰，纯按用量取会把预算全花在它们身上，
        // 既浪费色号、又会把 JPEG 噪点放大成一格格的灰白杂点。
        val limit = maxColors.coerceIn(1, candidates.size)
        val order = histogram.indices
            .filter { histogram[it] > 0 }
            .sortedByDescending { histogram[it] }
        val keptList = ArrayList<Int>(limit)
        for (pos in order) {
            if (keptList.size >= limit) break
            val c = palette.get(candidates[pos]) ?: continue
            val cr = (c.argb shr 16) and 0xFF
            val cg = (c.argb shr 8) and 0xFF
            val cb = c.argb and 0xFF
            var tooClose = false
            for (k in keptList) {
                val kc = palette.get(candidates[k]) ?: continue
                if (distance(kc.argb, cr, cg, cb) < MERGE_DISTANCE) { tooClose = true; break }
            }
            if (!tooClose) keptList.add(pos)
        }
        if (keptList.isEmpty()) return null
        val kept = keptList.toIntArray()

        // kept 里存的是 candidates 的下标；set 用于判断这个颜色是否被保留
        val keepSet = HashSet<Int>(kept.size)
        for (p in kept) keepSet.add(p)

        val cells = IntArray(n) { -1 }
        for (i in 0 until n) {
            val pos = slot[i]
            if (pos < 0) continue
            // 被砍掉的色号 → 改投到保留色里最近的一个（nearestAmong 返回 kept 的下标）
            val finalPos = if (keepSet.contains(pos)) pos
            else kept[nearestAmong(kept, candidates, palette, rep[i] and 0xFFFFFF)]
            cells[i] = candidates[finalPos]
        }

        var beads = 0
        for (v in cells) if (v >= 0) beads++
        val colorCount = cells.filter { it >= 0 }.distinct().size
        return Result(outW, outH, cells, colorCount, beads, useGrid)
    }

    /**
     * 一格的代表色：出现次数过半的那一色（像素画/截图的标准情况），否则取平均色（照片）。
     * 返回 ARGB，全透明返回 0。
     *
     * 注意：绝不能拿 -1 当"透明"的哨兵 —— 纯白 #FFFFFF 的 Int 值正好就是 -1，
     * 那会把整片白色（白底、白毛、白色豆子）当成透明空格丢掉。
     */
    private fun representative(
        pixels: IntArray,
        w: Int,
        x0: Int,
        y0: Int,
        x1: Int,
        y1: Int,
        counter: HashMap<Int, Int>,
    ): Int {
        counter.clear()
        var n = 0
        var sr = 0
        var sg = 0
        var sb = 0
        for (y in y0 until y1) {
            val row = y * w
            for (x in x0 until x1) {
                val p = pixels[row + x]
                if ((p ushr 24) < 128) continue
                n++
                sr += (p shr 16) and 0xFF
                sg += (p shr 8) and 0xFF
                sb += p and 0xFF
                val key = p and 0xFFFFFF
                counter[key] = (counter[key] ?: 0) + 1
            }
        }
        if (n == 0) return 0
        var bestKey = 0
        var bestCount = 0
        for ((k, c) in counter) {
            if (c > bestCount) { bestCount = c; bestKey = k }
        }
        // 主色占一半以上 → 直接用主色，避免与相邻色平均出灰
        if (bestCount * 2 >= n) return (0xFF shl 24) or bestKey
        val r = sr / n
        val g = sg / n
        val b = sb / n
        return (0xFF shl 24) or (r shl 16) or (g shl 8) or b
    }

    /** 裁剪区（归一化）→ 像素矩形，至少 1×1，且不越界。 */
    internal fun cropRect(source: Bitmap, crop: RectF): Rect? {
        val bw = source.width
        val bh = source.height
        if (bw < 1 || bh < 1) return null
        val left = (crop.left.coerceIn(0f, 1f) * bw).roundToInt().coerceIn(0, bw - 1)
        val top = (crop.top.coerceIn(0f, 1f) * bh).roundToInt().coerceIn(0, bh - 1)
        val right = (crop.right.coerceIn(0f, 1f) * bw).roundToInt().coerceIn(left + 1, bw)
        val bottom = (crop.bottom.coerceIn(0f, 1f) * bh).roundToInt().coerceIn(top + 1, bh)
        return Rect(left, top, right, bottom)
    }

    internal fun cropBitmap(source: Bitmap, crop: RectF): Bitmap? {
        val r = cropRect(source, crop) ?: return null
        return runCatching { Bitmap.createBitmap(source, r.left, r.top, r.width(), r.height()) }.getOrNull()
    }

    /** 长边固定为 longSide，短边按比例取整，避免 1 格宽/高的退化情况。 */
    private fun targetSize(w: Int, h: Int, longSide: Int): Pair<Int, Int> {
        val side = longSide.coerceIn(4, 200)
        return if (w >= h) {
            side to max(1, (h.toFloat() / w * side).roundToInt())
        } else {
            max(1, (w.toFloat() / h * side).roundToInt()) to side
        }
    }

    private fun nearestIndex(candidates: IntArray, palette: Palette, rgb: Int): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        var bestPos = 0
        var best = Int.MAX_VALUE
        for (i in candidates.indices) {
            val c = palette.get(candidates[i]) ?: continue
            val d = distance(c.argb, r, g, b)
            if (d < best) { best = d; bestPos = i }
        }
        return bestPos
    }

    private fun nearestAmong(
        kept: IntArray,
        candidates: IntArray,
        palette: Palette,
        rgb: Int,
    ): Int {
        val r = (rgb shr 16) and 0xFF
        val g = (rgb shr 8) and 0xFF
        val b = rgb and 0xFF
        var bestPos = 0
        var best = Int.MAX_VALUE
        for (i in kept.indices) {
            val c = palette.get(candidates[kept[i]]) ?: continue
            val d = distance(c.argb, r, g, b)
            if (d < best) { best = d; bestPos = i }
        }
        return bestPos
    }

    /** 加权 RGB 距离（近似感知差异，够用且比转 Lab 快得多）。 */
    internal fun distance(argb: Int, r: Int, g: Int, b: Int): Int {
        val dr = ((argb shr 16) and 0xFF) - r
        val dg = ((argb shr 8) and 0xFF) - g
        val db = (argb and 0xFF) - b
        return 2 * dr * dr + 4 * dg * dg + 3 * db * db
    }

    /** 把结果包成一张没入库的临时图纸，方便直接复用 PatternCanvas 预览。 */
    fun toPattern(result: Result, palette: Palette, name: String = "预览"): Pattern {
        val now = System.currentTimeMillis()
        return Pattern(
            id = Pattern.newId(),
            name = name,
            width = result.width,
            height = result.height,
            paletteId = palette.id,
            cells = result.cells,
            createdAt = now,
            updatedAt = now,
        )
    }

    /** 默认裁剪区：整张图。 */
    fun fullRect(): RectF = RectF(0f, 0f, 1f, 1f)

    fun clampRect(rect: RectF, minSize: Float = 0.03f): RectF {
        val l = min(rect.left, rect.right - minSize).coerceIn(0f, 1f - minSize)
        val t = min(rect.top, rect.bottom - minSize).coerceIn(0f, 1f - minSize)
        val r = max(rect.right, l + minSize).coerceIn(l + minSize, 1f)
        val b = max(rect.bottom, t + minSize).coerceIn(t + minSize, 1f)
        return RectF(l, t, r, b)
    }
}
