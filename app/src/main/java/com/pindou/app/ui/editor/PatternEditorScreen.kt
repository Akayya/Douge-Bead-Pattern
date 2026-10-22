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
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import kotlin.math.max
import kotlin.math.min

private enum class EditorTool(val label: String) {
    Brush("画笔"),
    Eraser("橡皮"),
    Bucket("油漆桶"),
    Picker("吸管"),
    Line("直线"),
    Rect("矩形"),
}

private const val EMPTY_COLOR = 0xFFEDEDF2.toInt()

private data class ShapePreview(val start: Int, val end: Int, val tool: EditorTool)

private fun lineCells(width: Int, from: Int, to: Int): List<Int> {
    val out = ArrayList<Int>()
    var x0 = from % width
    var y0 = from / width
    val x1 = to % width
    val y1 = to / width
    val dx = abs(x1 - x0)
    val dy = abs(y1 - y0)
    val sx = if (x0 < x1) 1 else -1
    val sy = if (y0 < y1) 1 else -1
    var err = dx - dy
    while (true) {
        out.add(y0 * width + x0)
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
    return out
}

private fun rectOutlineCells(width: Int, from: Int, to: Int): List<Int> {
    val ax = from % width
    val ay = from / width
    val bx = to % width
    val by = to / width
    val left = min(ax, bx)
    val right = max(ax, bx)
    val top = min(ay, by)
    val bottom = max(ay, by)
    val out = LinkedHashSet<Int>()
    for (x in left..right) {
        out.add(top * width + x)
        out.add(bottom * width + x)
    }
    for (y in top..bottom) {
        out.add(y * width + left)
        out.add(y * width + right)
    }
    return out.toList()
}

private fun shapeCells(width: Int, preview: ShapePreview): List<Int> =
    if (preview.tool == EditorTool.Rect) {
        rectOutlineCells(width, preview.start, preview.end)
    } else {
        lineCells(width, preview.start, preview.end)
    }

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
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
    var mirror by remember { mutableStateOf(false) }
    var shapePreview by remember { mutableStateOf<ShapePreview?>(null) }

    // 色条只放本图用到的色，另外保证当前画笔色一定在里面
    val stripColors = remember(usedColors, brush) {
        val cur = palette.get(brush)
        if (cur != null && usedColors.none { it.code == cur.code }) listOf(cur) + usedColors else usedColors
    }

    fun pushUndo(snapshot: IntArray) {
        if (undoStack.size >= 30) undoStack.removeFirst()
        undoStack.addLast(snapshot)
    }

    // 镜像只作用于逐格写入；油漆桶按区域填色，不跟着翻
    fun applyCells(indices: Collection<Int>, value: Int): Boolean {
        var changed = false
        val targets = LinkedHashSet<Int>(indices.size * 2)
        for (idx in indices) {
            if (idx !in cells.indices) continue
            targets.add(idx)
            if (mirror) {
                val x = idx % pattern.width
                val y = idx / pattern.width
                targets.add(y * pattern.width + (pattern.width - 1 - x))
            }
        }
        for (idx in targets) {
            if (cells[idx] != value) {
                cells[idx] = value
                changed = true
            }
        }
        return changed
    }

    fun paintLine(from: Int, to: Int, value: Int) {
        if (from < 0 || to < 0) return
        applyCells(lineCells(pattern.width, from, to), value)
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
                        .pointerInput(pattern.width, pattern.height, tool, mirror) {
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
                                            if (applyCells(listOf(idx), -1)) painted = true
                                        }
                                        EditorTool.Brush -> {
                                            if (applyCells(listOf(idx), brush)) painted = true
                                        }
                                        EditorTool.Line, EditorTool.Rect -> Unit
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
                                    EditorTool.Line, EditorTool.Rect -> {
                                        if (lastIdx >= 0) {
                                            shapePreview = ShapePreview(lastIdx, lastIdx, tool)
                                        }
                                        while (true) {
                                            val event = awaitPointerEvent()
                                            if (event.changes.count { it.pressed } > 1) {
                                                multi = true
                                                shapePreview = null
                                            }
                                            if (multi) {
                                                val zoom = event.calculateZoom()
                                                val pan = event.calculatePan()
                                                if (zoom != 1f) scale = (scale * zoom).coerceIn(1f, 8f)
                                                offset += pan
                                            } else {
                                                val idx = indexAt(event.changes.first().position)
                                                val p = shapePreview
                                                if (idx >= 0 && p != null && idx != p.end) {
                                                    shapePreview = p.copy(end = idx)
                                                }
                                            }
                                            val pressed = event.changes.any { it.pressed }
                                            event.changes.forEach { if (it.positionChanged()) it.consume() }
                                            if (!pressed) break
                                        }
                                        val p = shapePreview
                                        shapePreview = null
                                        if (!multi && p != null) {
                                            if (applyCells(shapeCells(pattern.width, p), brush)) {
                                                painted = true
                                            }
                                        }
                                        if (painted) {
                                            version++
                                            pushUndo(snapshot)
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
                    val preview = shapePreview
                    if (preview != null) {
                        val previewColor = Color(palette.get(brush)?.argb ?: EMPTY_COLOR)
                        for (idx in shapeCells(pattern.width, preview)) {
                            val x = idx % pattern.width
                            val y = idx / pattern.width
                            drawRect(
                                previewColor,
                                Offset(ox + x * cell, oy + y * cell),
                                Size(cell, cell),
                            )
                            if (mirror) {
                                val mx = pattern.width - 1 - x
                                drawRect(
                                    previewColor,
                                    Offset(ox + mx * cell, oy + y * cell),
                                    Size(cell, cell),
                                )
                            }
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
                    if (mirror) {
                        val axisX = ox + pattern.width * cell / 2f
                        drawLine(
                            Color(0x666C4BF4),
                            Offset(axisX, oy),
                            Offset(axisX, oy + pattern.height * cell),
                            1.5f,
                        )
                    }
                }
            }

            FlowRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                EditorTool.entries.forEach { t ->
                    FilterChip(selected = tool == t, onClick = { tool = t }, label = { Text(t.label) })
                }
                FilterChip(
                    selected = mirror,
                    onClick = { mirror = !mirror },
                    label = { Text("左右镜像") },
                )
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
