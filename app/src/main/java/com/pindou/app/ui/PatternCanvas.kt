package com.pindou.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.pindou.app.data.Palette
import com.pindou.app.data.Pattern
import com.pindou.app.data.PatternThumbCache

/**
 * 图纸缩略图 / 预览：直接按色板颜色铺方块。空格留背景色。
 *
 * @param boardSize 拼豆板边长（大板 29×29）。每 boardSize 格画一条粗线，
 *                  施工时按板分区、对板号用；缩略图里太密就不画。
 */
@Composable
fun PatternCanvas(
    pattern: Pattern,
    palette: Palette,
    modifier: Modifier = Modifier,
    cellPadding: Dp = 0.dp,
    showGrid: Boolean = false,
    gridColor: Color = Color(0x33000000),
    boardSize: Int = 29,
    boardColor: Color = Color(0xC0000000),
) {
    val emptyColor = MaterialTheme.colorScheme.surfaceVariant
    Canvas(modifier = modifier) {
        val cell = minOf(size.width / pattern.width, size.height / pattern.height)
        val originX = (size.width - cell * pattern.width) / 2f
        val originY = (size.height - cell * pattern.height) / 2f
        val pad = cellPadding.toPx()
        for (y in 0 until pattern.height) {
            for (x in 0 until pattern.width) {
                val v = pattern.cells[y * pattern.width + x]
                val color = if (v >= 0) Color(palette.get(v)?.argb ?: 0) else emptyColor
                drawRect(
                    color = color,
                    topLeft = Offset(originX + x * cell + pad / 2f, originY + y * cell + pad / 2f),
                    size = Size(cell - pad, cell - pad),
                )
            }
        }
        if (showGrid) {
            val stroke = 1.dp.toPx()
            for (x in 0..pattern.width) {
                val px = originX + x * cell
                drawLine(gridColor, Offset(px, originY), Offset(px, originY + pattern.height * cell), stroke)
            }
            for (y in 0..pattern.height) {
                val py = originY + y * cell
                drawLine(gridColor, Offset(originX, py), Offset(originX + pattern.width * cell, py), stroke)
            }
        }
        // 拼豆板分区线：每 boardSize 格一条，比普通网格粗一档
        if (boardSize >= 4 && cell * boardSize >= 24.dp.toPx()) {
            val stroke = (cell * 0.16f).coerceIn(1.5f.dp.toPx(), 3.5f.dp.toPx())
            var x = boardSize
            while (x < pattern.width) {
                val px = originX + x * cell
                drawLine(boardColor, Offset(px, originY), Offset(px, originY + pattern.height * cell), stroke)
                x += boardSize
            }
            var y = boardSize
            while (y < pattern.height) {
                val py = originY + y * cell
                drawLine(boardColor, Offset(originX, py), Offset(originX + pattern.width * cell, py), stroke)
                y += boardSize
            }
        }
    }
}

/** 卡片里用的小缩略图，白底圆角，尺寸固定。优先显示落盘的位图缓存。 */
@Composable
fun PatternThumb(pattern: Pattern, palette: Palette, size: Dp, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val cellsHash = pattern.cells.contentHashCode()
    var bitmap by remember(pattern.id, cellsHash) {
        mutableStateOf(PatternThumbCache.memoryGet(pattern))
    }
    LaunchedEffect(pattern.id, cellsHash, palette) {
        val cached = PatternThumbCache.load(context, pattern, palette)
        if (cached != null) bitmap = cached
    }
    Surface(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(12.dp)),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Box(Modifier.fillMaxSize().padding(4.dp)) {
            val bmp = bitmap
            if (bmp != null) {
                val image = remember(bmp) { bmp.asImageBitmap() }
                Image(
                    bitmap = image,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
            } else {
                PatternCanvas(
                    pattern = pattern,
                    palette = palette,
                    modifier = Modifier.fillMaxSize(),
                    // 缩略图太小，画分区线只会变成一道黑杠，关掉
                    boardSize = 0,
                )
            }
        }
    }
}
