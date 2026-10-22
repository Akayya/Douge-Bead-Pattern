package com.pindou.app.ui.importsheet

import android.graphics.Bitmap
import android.graphics.RectF
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import com.pindou.app.data.ImageToPattern
import com.pindou.app.data.PixelGridDetector
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * 裁剪框：图片等比缩放塞进固定高度的框里，两种操作——
 *  - 拖四角：微调（只在离角 20dp 内生效，避免抢走"拉框"的起手）
 *  - 在图上任意处拖动：重新框选一块区域（就是截图工具那种拉一个框）
 *
 * 裁剪区用 0..1 的归一化坐标保存，跟屏幕尺寸无关，转图纸时再换算成像素。
 *
 * 关键点：pointerInput 不会因为 rect 变化而重启，里面必须用 rememberUpdatedState 拿最新的 rect，
 * 否则拖拽永远以初始整图框为基准（表现为怎么拖都只动一点点）。
 * 手势用 awaitEachGesture 自己分流，不用 detectDragGestures，否则没法区分这三种操作。
 */
@Composable
fun CropView(
    bitmap: Bitmap,
    rect: RectF,
    onRectChange: (RectF) -> Unit,
    modifier: Modifier = Modifier,
    height: Dp = 340.dp,
    grid: PixelGridDetector.Grid? = null,
) {
    val image = remember(bitmap) { bitmap.asImageBitmap() }
    var box by remember { mutableStateOf(IntSize.Zero) }
    val currentRect = rememberUpdatedState(rect)
    val handleRadius = 9.dp
    var marqueeActive by remember { mutableStateOf(false) }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(height)
            .clip(RoundedCornerShape(16.dp))
            .background(Color(0xFF141414))
            .onSizeChanged { box = it },
    ) {
        Canvas(
            modifier = Modifier.fillMaxSize().pointerInput(bitmap) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val dst = fitRect(box, bitmap.width, bitmap.height)
                    if (dst.width() <= 0f || dst.height() <= 0f) return@awaitEachGesture

                    val rect0 = RectF(currentRect.value)
                    val start = down.position
                    // 抓手要小：44dp 时从离角 100px 的地方起手也会被抓角，框选根本拉不出来
                    val threshold = 20.dp.toPx()

                    // 1) 先看是不是抓手：取最近的角
                    var corner = -1
                    var bestDist = threshold
                    corners(dst, rect0).forEachIndexed { i, c ->
                        val d = abs(c.x - start.x) + abs(c.y - start.y)
                        if (d < bestDist) { bestDist = d; corner = i }
                    }
                    marqueeActive = corner < 0

                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        val pos = change.position
                        val dx = pos.x - start.x
                        val dy = pos.y - start.y

                        val next: RectF? = when {
                            corner >= 0 -> {
                                val nx = rect0.left + dx / dst.width()
                                val ny = rect0.top + dy / dst.height()
                                val rx = rect0.right + dx / dst.width()
                                val by = rect0.bottom + dy / dst.height()
                                when (corner) {
                                    0 -> RectF(nx, ny, rect0.right, rect0.bottom)
                                    1 -> RectF(rect0.left, ny, rx, rect0.bottom)
                                    2 -> RectF(rect0.left, rect0.top, rx, by)
                                    else -> RectF(nx, rect0.top, rect0.right, by)
                                }
                            }

                            else -> {
                                // 框外拖动 = 重新框选；太小的位移当误触忽略
                                if (abs(dx) < 12.dp.toPx() && abs(dy) < 12.dp.toPx()) null
                                else {
                                    val ax = ((min(start.x, pos.x) - dst.left) / dst.width()).coerceIn(0f, 1f)
                                    val ay = ((min(start.y, pos.y) - dst.top) / dst.height()).coerceIn(0f, 1f)
                                    val bx = ((max(start.x, pos.x) - dst.left) / dst.width()).coerceIn(0f, 1f)
                                    val by = ((max(start.y, pos.y) - dst.top) / dst.height()).coerceIn(0f, 1f)
                                    RectF(ax, ay, bx, by)
                                }
                            }
                        }
                        if (next != null) {
                            onRectChange(ImageToPattern.clampRect(next, if (corner >= 0) 0.03f else 0.02f))
                        }
                        change.consume()
                    }
                    marqueeActive = false
                }
            },
        ) {
            val dst = fitRect(box, bitmap.width, bitmap.height)
            if (dst.width() <= 0f || dst.height() <= 0f) return@Canvas

            drawImage(
                image = image,
                dstOffset = IntOffset(dst.left.toInt(), dst.top.toInt()),
                dstSize = IntSize(dst.width().toInt(), dst.height().toInt()),
            )

            val crop = RectF(
                dst.left + currentRect.value.left * dst.width(),
                dst.top + currentRect.value.top * dst.height(),
                dst.left + currentRect.value.right * dst.width(),
                dst.top + currentRect.value.bottom * dst.height(),
            )

            // 裁剪框外压暗
            val dim = Color(0x99000000)
            drawRect(dim, topLeft = Offset(0f, 0f), size = Size(size.width, crop.top))
            drawRect(dim, topLeft = Offset(0f, crop.bottom), size = Size(size.width, size.height - crop.bottom))
            drawRect(dim, topLeft = Offset(0f, crop.top), size = Size(crop.left, crop.height()))
            drawRect(dim, topLeft = Offset(crop.right, crop.top), size = Size(size.width - crop.right, crop.height()))

            // 三分线
            val thin = Color(0x66FFFFFF)
            val stroke = 1.dp.toPx()
            for (i in 1..2) {
                val x = crop.left + crop.width() * i / 3f
                val y = crop.top + crop.height() * i / 3f
                drawLine(thin, Offset(x, crop.top), Offset(x, crop.bottom), stroke)
                drawLine(thin, Offset(crop.left, y), Offset(crop.right, y), stroke)
            }

            // 识别到的原图网格：给用户看清"一格在哪"，也用来确认框有没有对齐
            if (grid != null && grid.cellX >= 1f && grid.cellY >= 1f) {
                val screenCellW = grid.cellX / bitmap.width * dst.width()
                val screenCellH = grid.cellY / bitmap.height * dst.height()
                if (screenCellW >= 5f && screenCellH >= 5f) {
                    val gridThin = Color(0x33FFFFFF)
                    val gridStrong = Color(0x66FFFFFF)
                    val baseX = dst.left + (currentRect.value.left * bitmap.width + grid.offsetX) / bitmap.width * dst.width()
                    val baseY = dst.top + (currentRect.value.top * bitmap.height + grid.offsetY) / bitmap.height * dst.height()
                    var k = 1
                    while (true) {
                        val gx = baseX + k * screenCellW
                        if (gx >= crop.right - 0.5f) break
                        if (gx > crop.left + 0.5f) {
                            drawLine(
                                if (k % 5 == 0) gridStrong else gridThin,
                                Offset(gx, crop.top),
                                Offset(gx, crop.bottom),
                                1f,
                            )
                        }
                        k++
                    }
                    k = 1
                    while (true) {
                        val gy = baseY + k * screenCellH
                        if (gy >= crop.bottom - 0.5f) break
                        if (gy > crop.top + 0.5f) {
                            drawLine(
                                if (k % 5 == 0) gridStrong else gridThin,
                                Offset(crop.left, gy),
                                Offset(crop.right, gy),
                                1f,
                            )
                        }
                        k++
                    }
                }
            }

            // 边框（重新框选中用主色描边，提示这是"正在拉框"）
            drawRect(
                color = if (marqueeActive) Color(0xFF6C4BF4) else Color.White,
                topLeft = Offset(crop.left, crop.top),
                size = Size(crop.width(), crop.height()),
                style = Stroke(width = 2.dp.toPx()),
            )
            val r = handleRadius.toPx()
            corners(dst, currentRect.value).forEach { c ->
                drawCircle(Color.White, radius = r, center = c)
                drawCircle(Color(0xFF6C4BF4), radius = r * 0.55f, center = c)
            }
        }
    }
}

/** 图片在框内的绘制区域（等比缩放 + 居中）。 */
private fun fitRect(box: IntSize, bw: Int, bh: Int): RectF {
    if (box.width == 0 || box.height == 0 || bw == 0 || bh == 0) return RectF()
    val scale = min(box.width.toFloat() / bw, box.height.toFloat() / bh)
    val w = bw * scale
    val h = bh * scale
    val left = (box.width - w) / 2f
    val top = (box.height - h) / 2f
    return RectF(left, top, left + w, top + h)
}

/** 四个角的屏幕坐标，顺序：左上、右上、右下、左下。 */
private fun corners(dst: RectF, rect: RectF): List<Offset> = listOf(
    Offset(dst.left + rect.left * dst.width(), dst.top + rect.top * dst.height()),
    Offset(dst.left + rect.right * dst.width(), dst.top + rect.top * dst.height()),
    Offset(dst.left + rect.right * dst.width(), dst.top + rect.bottom * dst.height()),
    Offset(dst.left + rect.left * dst.width(), dst.top + rect.bottom * dst.height()),
)
