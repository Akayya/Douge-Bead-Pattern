package com.pindou.app.ui.editor

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateZoom
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.pindou.app.data.BeadColor
import com.pindou.app.data.Palette
import com.pindou.app.data.Pattern
import com.pindou.app.data.PatternStore
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.min

private enum class EditorTool(val label: String) {
    Brush("画笔"),
    Eraser("橡皮"),
    Bucket("油漆桶"),
    Picker("吸管"),
}

private const val EMPTY_COLOR = 0xFFEDEDF2.toInt()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatternEditorScreen(
    pattern: Pattern,
    palette: Palette,
    enabledColors: List<BeadColor>,
    onBack: () -> Unit,
    onSaved: (Pattern) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val cells = remember(pattern.id) { pattern.cells.copyOf() }

    var version by remember { mutableIntStateOf(0) }
    val usedColors = remember(version, pattern.id) {
        cells.filter { it >= 0 }
            .groupingBy { it }
            .eachCount()
            .entries
            .sortedByDescending { it.value }
            .mapNotNull { palette.get(it.key) }
    }
    var tool by remember { mutableStateOf(EditorTool.Brush) }
    var brush by remember {
        val init = usedColors.firstOrNull() ?: enabledColors.firstOrNull()
        mutableIntStateOf(init?.let { palette.indexOf(it.code) } ?: 0)
    }
    val undoStack = remember { ArrayDeque<IntArray>() }
    var scale by remember { mutableFloatStateOf(1f) }
    var offset by remember { mutableStateOf(Offset.Zero) }
    var dirty by remember { mutableStateOf(false) }
    var showReplace by remember { mutableStateOf(false) }
    var showBrushPicker by remember { mutableStateOf(false) }
    var showTargetPicker by remember { mutableStateOf(false) }

    // 色条只放本图用到的色，另外保证当前画笔色一定在里面
    val stripColors = remember(usedColors, brush) {
        val cur = palette.get(brush)
        if (cur != null && usedColors.none { it.code == cur.code }) listOf(cur) + usedColors else usedColors
    }

    fun pushUndo(snapshot: IntArray) {
        if (undoStack.size >= 30) undoStack.removeFirst()
        undoStack.addLast(snapshot)
    }

    fun paintLine(from: Int, to: Int, value: Int) {
        if (from < 0 || to < 0) return
        var x0 = from % pattern.width
        var y0 = from / pattern.width
        val x1 = to % pattern.width
        val y1 = to / pattern.width
        val dx = abs(x1 - x0)
        val dy = abs(y1 - y0)
        val sx = if (x0 < x1) 1 else -1
        val sy = if (y0 < y1) 1 else -1
        var err = dx - dy
        while (true) {
            if (x0 in 0 until pattern.width && y0 in 0 until pattern.height) {
                cells[y0 * pattern.width + x0] = value
            }
            if (x0 == x1 && y0 == y1) break
            val e2 = 2 * err
            if (e2 > -dy) {
                err -= dy
                x0 += sx
            }
            if (e2 < dx) {
                err += dx
                y0 += sy
            }
        }
        dirty = true
        version++
    }

    fun floodFill(start: Int, value: Int) {
        val target = cells[start]
        if (target == value) return
        val stack = ArrayDeque<Int>()
        stack.addLast(start)
        val seen = BooleanArray(cells.size)
        while (stack.isNotEmpty()) {
            val idx = stack.removeLast()
            if (idx < 0 || idx >= cells.size || seen[idx] || cells[idx] != target) continue
            seen[idx] = true
            cells[idx] = value
            val x = idx % pattern.width
            val y = idx / pattern.width
            if (x > 0) stack.addLast(idx - 1)
            if (x < pattern.width - 1) stack.addLast(idx + 1)
            if (y > 0) stack.addLast(idx - pattern.width)
            if (y < pattern.height - 1) stack.addLast(idx + pattern.width)
        }
        dirty = true
        version++
    }

    fun replaceAll(source: Int, target: Int) {
        if (source < 0) return
        val snapshot = cells.copyOf()
        var hit = 0
        for (i in cells.indices) {
            if (cells[i] == source) {
                cells[i] = target
                hit++
            }
        }
        if (hit > 0) {
            pushUndo(snapshot)
            version++
            dirty = true
        }
        showReplace = false
        scope.launch { snackbar.showSnackbar(if (target < 0) "清空 $hit 格" else "换掉 $hit 格") }
    }

    fun save(exit: Boolean) {
        val updated = Pattern(
            id = pattern.id,
            name = pattern.name,
            width = pattern.width,
            height = pattern.height,
            paletteId = pattern.paletteId,
            cells = cells.copyOf(),
            createdAt = pattern.createdAt,
            updatedAt = System.currentTimeMillis(),
            done = pattern.done,
        )
        PatternStore.save(context, updated)
        onSaved(updated)
        dirty = false
        scope.launch { snackbar.showSnackbar("已保存") }
        if (exit) onBack()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(pattern.name, style = MaterialTheme.typography.titleMedium) },
                navigationIcon = {
                    TextButton(onClick = { if (dirty) save(true) else onBack() }) { Text("返回") }
                },
                actions = { TextButton(onClick = { save(true) }) { Text("保存") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF7F7FA)),
            ) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val base = min(boxW / pattern.width, boxH / pattern.height)

                fun indexAt(pos: Offset): Int {
                    val cell = base * scale
                    if (cell <= 0f) return -1
                    val ox = (boxW - pattern.width * cell) / 2f + offset.x
                    val oy = (boxH - pattern.height * cell) / 2f + offset.y
                    val cx = ((pos.x - ox) / cell).toInt()
                    val cy = ((pos.y - oy) / cell).toInt()
                    return if (cx in 0 until pattern.width && cy in 0 until pattern.height) {
                        cy * pattern.width + cx
                    } else {
                        -1
                    }
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        .pointerInput(pattern.width, pattern.height, tool) {
                            awaitEachGesture {
                                val down = awaitFirstDown(requireUnconsumed = false)
                                val snapshot = cells.copyOf()
                                var painted = false
                                var multi = false
                                var lastIdx = indexAt(down.position)

                                fun stamp(idx: Int) {
                                    if (idx < 0) return
                                    when (tool) {
                                        EditorTool.Picker -> {
                                            if (cells[idx] >= 0) brush = cells[idx]
                                        }
                                        EditorTool.Bucket -> {
                                            floodFill(idx, -1)
                                            painted = true
                                        }
                                        EditorTool.Eraser -> {
                                            if (cells[idx] >= 0) {
                                                cells[idx] = -1
                                                painted = true
                                            }
                                        }
                                        EditorTool.Brush -> {
                                            if (cells[idx] != brush) {
                                                cells[idx] = brush
                                                painted = true
                                            }
                                        }
                                    }
                                }

                                when (tool) {
                                    EditorTool.Bucket -> {
                                        if (lastIdx >= 0) {
                                            pushUndo(snapshot)
                                            floodFill(lastIdx, brush)
                                        }
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            e.changes.forEach { it.consume() }
                                            if (!e.changes.any { it.pressed }) break
                                        }
                                        return@awaitEachGesture
                                    }
                                    EditorTool.Picker -> {
                                        stamp(lastIdx)
                                        while (true) {
                                            val e = awaitPointerEvent()
                                            e.changes.forEach { it.consume() }
                                            if (!e.changes.any { it.pressed }) break
                                        }
                                        return@awaitEachGesture
                                    }
                                    else -> Unit
                                }

                                stamp(lastIdx)
                                if (painted) version++

                                while (true) {
                                    val event = awaitPointerEvent()
                                    if (event.changes.count { it.pressed } > 1) multi = true
                                    if (multi) {
                                        val zoom = event.calculateZoom()
                                        val pan = event.calculatePan()
                                        if (zoom != 1f) scale = (scale * zoom).coerceIn(1f, 8f)
                                        offset += pan
                                    } else {
                                        val idx = indexAt(event.changes.first().position)
                                        if (idx >= 0 && idx != lastIdx) {
                                            if (tool == EditorTool.Brush) {
                                                paintLine(lastIdx, idx, brush)
                                            } else if (tool == EditorTool.Eraser) {
                                                paintLine(lastIdx, idx, -1)
                                            }
                                            lastIdx = idx
                                        }
                                    }
                                    val pressed = event.changes.any { it.pressed }
                                    event.changes.forEach { if (it.positionChanged()) it.consume() }
                                    if (!pressed) break
                                }
                                if (painted) pushUndo(snapshot)
                            }
                        },
                ) {
                    // 读一次 version，让改动后能重绘
                    @Suppress("UNUSED_EXPRESSION")
                    version

                    val cell = base * scale
                    val ox = (size.width - pattern.width * cell) / 2f + offset.x
                    val oy = (size.height - pattern.height * cell) / 2f + offset.y
                    for (y in 0 until pattern.height) {
                        for (x in 0 until pattern.width) {
                            val v = cells[y * pattern.width + x]
                            val left = ox + x * cell
                            val top = oy + y * cell
                            val argb = if (v < 0) EMPTY_COLOR else (palette.get(v)?.argb ?: EMPTY_COLOR)
                            drawRect(Color(argb), Offset(left, top), Size(cell, cell))
                        }
                    }
                    if (cell >= 5f) {
                        val gridColor = Color(0x33555566)
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
                    }
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                EditorTool.entries.forEach { t ->
                    FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(t.label) })
                }
            }

            LazyRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                item {
                    val selected = palette.get(brush) == null
                    FilterChip(
                        selected = showBrushPicker,
                        onClick = { showBrushPicker = true },
                        label = { Text("更多色") },
                    )
                }
                items(stripColors, key = { it.code }) { c ->
                    val idx = palette.indexOf(c.code)
                    val selected = brush == idx
                    Box(
                        Modifier
                            .size(34.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color(c.argb))
                            .border(
                                width = if (selected) 3.dp else 1.dp,
                                color = if (selected) {
                                    MaterialTheme.colorScheme.primary
                                } else {
                                    Color(0x33000000)
                                },
                                shape = RoundedCornerShape(8.dp),
                            )
                            .clickable { brush = idx },
                    )
                }
            }

            Row(
                Modifier.fillMaxWidth().padding(12.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedButton(
                    onClick = {
                        val last = undoStack.removeLastOrNull()
                        if (last != null) {
                            last.copyInto(cells)
                            version++
                            dirty = true
                        } else {
                            scope.launch { snackbar.showSnackbar("没有可撤销的操作") }
                        }
                    },
                    modifier = Modifier.weight(1f),
                ) { Text("撤销") }
                OutlinedButton(
                    onClick = { showReplace = true },
                    modifier = Modifier.weight(1f),
                    enabled = brush >= 0,
                ) { Text("替换此色") }
            }
        }
    }

    if (showReplace) {
        val src = palette.get(brush)
        AlertDialog(
            onDismissRequest = { showReplace = false },
            title = { Text("替换色号") },
            text = {
                Column {
                    Text(
                        if (src == null) {
                            "先在色板里选一个颜色"
                        } else {
                            "把全图的 ${src.code} ${src.hex} 换成："
                        },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { replaceAll(brush, -1) }
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            Modifier
                                .size(30.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(EMPTY_COLOR))
                                .border(1.dp, Color(0x33000000), RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(10.dp))
                        Text("变成空位（不放豆）", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(10.dp))
                    OutlinedButton(
                        onClick = {
                            showReplace = false
                            showTargetPicker = true
                        },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = brush >= 0,
                    ) { Text("选择目标色（按色系挑）") }
                }
            },
            confirmButton = { TextButton(onClick = { showReplace = false }) { Text("关闭") } },
        )
    }

    if (showBrushPicker) {
        ColorPickerSheet(
            title = "选画笔颜色",
            colors = enabledColors,
            onDismiss = { showBrushPicker = false },
            onPick = { c ->
                brush = palette.indexOf(c.code)
                showBrushPicker = false
            },
        )
    }

    if (showTargetPicker) {
        ColorPickerSheet(
            title = "把 ${palette.get(brush)?.code ?: ""} 换成",
            colors = enabledColors,
            onDismiss = { showTargetPicker = false },
            onPick = { c ->
                replaceAll(brush, palette.indexOf(c.code))
                showTargetPicker = false
            },
        )
    }
}
