package com.pindou.app.ui.build

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pindou.app.data.Palette
import com.pindou.app.data.Pattern
import com.pindou.app.data.PatternStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min

private enum class BuildView(val label: String) {
    All("全部"),
    ByColor("逐色"),
    ByRow("逐行"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildModeScreen(
    pattern: Pattern,
    palette: Palette,
    onBack: () -> Unit,
    onPatternChanged: (Pattern) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val done = remember(pattern.id) { pattern.done.toMutableSet() }
    var doneVersion by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf(BuildView.All) }
    var showCodes by remember { mutableStateOf(false) }
    var highlight by remember { mutableIntStateOf(-1) }
    var row by remember { mutableIntStateOf(0) }

    val usage = remember(pattern.id) { pattern.usage(palette) }

    fun persist() {
        val updated = Pattern(
            id = pattern.id,
            name = pattern.name,
            width = pattern.width,
            height = pattern.height,
            paletteId = pattern.paletteId,
            cells = pattern.cells,
            createdAt = pattern.createdAt,
            updatedAt = System.currentTimeMillis(),
            done = done.toMutableSet(),
        )
        PatternStore.save(context, updated)
        onPatternChanged(updated)
    }

    // 打勾后 500ms 落盘，避免每点一下都写文件
    LaunchedEffect(doneVersion) {
        if (doneVersion == 0) return@LaunchedEffect
        delay(500)
        persist()
    }

    val total = pattern.beadCount
    val finished = done.count { it in pattern.cells.indices && pattern.cells[it] >= 0 }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("施工 · ${pattern.name}", style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    TextButton(onClick = {
                        persist()
                        onBack()
                    }) { Text("返回") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BuildView.entries.forEach { v ->
                    FilterChip(
                        selected = view == v,
                        onClick = {
                            view = v
                            highlight = when (v) {
                                BuildView.All -> -1
                                BuildView.ByColor -> usage.firstOrNull()?.let { palette.indexOf(it.color.code) } ?: -1
                                BuildView.ByRow -> row
                            }
                        },
                        label = { Text(v.label) },
                    )
                }
                Spacer(Modifier.weight(1f))
                FilterChip(
                    selected = showCodes,
                    onClick = { showCodes = !showCodes },
                    label = { Text("色号") },
                )
            }

            if (view == BuildView.ByColor) {
                LazyRow(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(usage, key = { it.color.code }) { item ->
                        val idx = palette.indexOf(item.color.code)
                        val selected = highlight == idx
                        Box(
                            Modifier
                                .size(34.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(item.color.argb))
                                .border(
                                    width = if (selected) 3.dp else 1.dp,
                                    color = if (selected) {
                                        MaterialTheme.colorScheme.primary
                                    } else {
                                        Color(0x33000000)
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                )
                                .clickable { highlight = idx },
                        )
                    }
                }
            }

            if (view == BuildView.ByRow) {
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(onClick = {
                        row = (row - 1).coerceAtLeast(0)
                        highlight = row
                    }) { Text("‹ 上一行") }
                    Text("第 ${row + 1} / ${pattern.height} 行", style = MaterialTheme.typography.bodyMedium)
                    TextButton(onClick = {
                        row = (row + 1).coerceAtMost(pattern.height - 1)
                        highlight = row
                    }) { Text("下一行 ›") }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else finished.toFloat() / total },
                    modifier = Modifier.weight(1f),
                )
                Text("$finished / $total", style = MaterialTheme.typography.labelMedium)
            }

            val groupColor = if (view == BuildView.ByColor) highlight else -1
            val groupRow = if (view == BuildView.ByRow) highlight else -1

            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF7F7FA)),
            ) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val cell = min(boxW / pattern.width, boxH / pattern.height)

                fun indexAt(pos: Offset): Int {
                    val ox = (boxW - pattern.width * cell) / 2f
                    val oy = (boxH - pattern.height * cell) / 2f
                    val cx = ((pos.x - ox) / cell).toInt()
                    val cy = ((pos.y - oy) / cell).toInt()
                    return if (cx in 0 until pattern.width && cy in 0 until pattern.height) {
                        cy * pattern.width + cx
                    } else {
                        -1
                    }
                }

                fun toggle(idx: Int) {
                    if (idx < 0) return
                    if (pattern.cells[idx] < 0) return
                    if (!done.remove(idx)) done.add(idx)
                    doneVersion++
                }

                fun clearGroup(pos: Offset) {
                    val idx = indexAt(pos)
                    if (idx < 0) {
                        scope.launch { snackbar.showSnackbar("这里还没有打勾") }
                        return
                    }
                    val row = idx / pattern.width
                    val targets = when {
                        groupColor >= 0 -> done.filter { pattern.cells[it] == groupColor }
                        groupRow >= 0 -> done.filter { it / pattern.width == groupRow }
                        else -> done.filter { it / pattern.width == row }
                    }
                    if (targets.isEmpty()) {
                        scope.launch { snackbar.showSnackbar("这里还没有打勾") }
                        return
                    }
                    done.removeAll(targets.toSet())
                    doneVersion++
                    scope.launch { snackbar.showSnackbar("清掉 ${targets.size} 个勾") }
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(pattern.width, pattern.height, groupColor, groupRow) {
                            detectTapGestures(
                                onTap = { pos -> toggle(indexAt(pos)) },
                                onLongPress = { pos -> clearGroup(pos) },
                            )
                        },
                ) {
                    @Suppress("UNUSED_EXPRESSION")
                    doneVersion
                    val ox = (size.width - pattern.width * cell) / 2f
                    val oy = (size.height - pattern.height * cell) / 2f
                    for (y in 0 until pattern.height) {
                        for (x in 0 until pattern.width) {
                            val idx = y * pattern.width + x
                            val v = pattern.cells[idx]
                            val left = ox + x * cell
                            val top = oy + y * cell
                            val argb = if (v < 0) 0xFFEDEDF2.toInt() else (palette.get(v)?.argb ?: 0xFFEDEDF2.toInt())
                            drawRect(Color(argb), Offset(left, top), Size(cell, cell))
                            val inGroup = (groupColor < 0 || v == groupColor) &&
                                (groupRow < 0 || y == groupRow)
                            if (!inGroup) {
                                drawRect(
                                    Color(0xCCFFFFFF),
                                    Offset(left, top),
                                    Size(cell, cell),
                                )
                            }
                            if (idx in done) {
                                val c = Offset(left + cell / 2f, top + cell / 2f)
                                drawCircle(Color(0x33000000), radius = cell * 0.46f, center = c)
                                drawCircle(Color(0xFF6C4BF4), radius = cell * 0.26f, center = c)
                                drawCircle(Color(0xFFFFFFFF), radius = cell * 0.11f, center = c)
                            }
                        }
                    }
                    if (cell >= 5f) {
                        val gridColor = Color(0x22555566)
                        for (x in 0..pattern.width) {
                            drawLine(
                                gridColor,
                                Offset(ox + x * cell, oy),
                                Offset(ox + x * cell, oy + pattern.height * cell),
                                1f,
                            )
                        }
                        for (y in 0..pattern.height) {
                            drawLine(
                                gridColor,
                                Offset(ox, oy + y * cell),
                                Offset(ox + pattern.width * cell, oy + y * cell),
                                1f,
                            )
                        }
                        val boardColor = Color(0x774A4A55)
                        var bx = 29
                        while (bx < pattern.width) {
                            drawLine(
                                boardColor,
                                Offset(ox + bx * cell, oy),
                                Offset(ox + bx * cell, oy + pattern.height * cell),
                                (cell * 0.14f).coerceAtLeast(1.5f),
                            )
                            bx += 29
                        }
                        var by = 29
                        while (by < pattern.height) {
                            drawLine(
                                boardColor,
                                Offset(ox, oy + by * cell),
                                Offset(ox + pattern.width * cell, oy + by * cell),
                                (cell * 0.14f).coerceAtLeast(1.5f),
                            )
                            by += 29
                        }
                    }

                    if (showCodes && cell >= 9f) {
                        val paint = android.graphics.Paint().apply {
                            isAntiAlias = true
                            textAlign = android.graphics.Paint.Align.CENTER
                            textSize = (cell * 0.52f).coerceAtMost(13f)
                        }
                        drawIntoCanvas { canvas ->
                            val native = canvas.nativeCanvas
                            for (yy in 0 until pattern.height) {
                                for (xx in 0 until pattern.width) {
                                    val vv = pattern.cells[yy * pattern.width + xx]
                                    if (vv < 0) continue
                                    val col = palette.get(vv) ?: continue
                                    val codeText = col.code
                                    if (codeText.length > 3) continue
                                    val lum = (0.299f * android.graphics.Color.red(col.argb) +
                                        0.587f * android.graphics.Color.green(col.argb) +
                                        0.114f * android.graphics.Color.blue(col.argb))
                                    paint.color = if (lum > 150f) 0xCC1A1A1A.toInt() else 0xEEFFFFFF.toInt()
                                    val cx = ox + xx * cell + cell / 2f
                                    val cy = oy + yy * cell + cell * 0.70f
                                    native.drawText(codeText, cx, cy, paint)
                                }
                            }
                        }
                    }
                }
            }

            Text(
                text = "点格子打勾，长按清掉当前${if (view == BuildView.ByColor) "颜色" else "行"}的勾",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
}
