package com.pindou.app.ui.build

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.pindou.app.data.Palette
import com.pindou.app.data.Pattern
import com.pindou.app.data.PatternStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.min
import kotlin.math.roundToInt

private enum class BuildView(val label: String) {
    All("全部"),
    ByColor("逐色"),
    ByRow("逐行"),
}

private const val MAX_ZOOM = 6f

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BuildModeScreen(
    pattern: Pattern,
    palette: Palette,
    onBack: () -> Unit,
    onPatternChanged: (Pattern) -> Unit,
    minutesPer100: Float = 5f,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }

    val done = remember(pattern.id) { pattern.done.toMutableSet() }
    var doneVersion by remember { mutableIntStateOf(0) }
    var view by remember { mutableStateOf(BuildView.All) }
    var showCodes by remember { mutableStateOf(false) }
    var onlyUndone by remember { mutableStateOf(false) }
    // 防误触：默认开着，已打勾的格子再点不会掉勾（要取消请长按清行/清色）
    var lockDone by remember { mutableStateOf(true) }
    var highlight by remember { mutableIntStateOf(-1) }
    var row by remember { mutableIntStateOf(0) }
    var cursor by remember { mutableIntStateOf(-1) }
    var status by remember { mutableStateOf("") }

    var scale by remember { mutableFloatStateOf(1f) }
    var pan by remember { mutableStateOf(Offset.Zero) }
    var jumpTick by remember { mutableIntStateOf(0) }

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
    val remainMinutes = ((total - finished) / 100f * minutesPer100).roundToInt()

    // 注意：这里必须每次现算，不能提前存成 val——手势 lambda 只在首帧创建一次，
    // 存 val 会让「逐色/逐行」的筛选条件永远停在初始值（切视图后照样能点别的格子）。
    fun groupColor(): Int = if (view == BuildView.ByColor) highlight else -1
    fun groupRow(): Int = if (view == BuildView.ByRow) highlight else -1

    fun inGroup(idx: Int): Boolean {
        val v = pattern.cells.getOrNull(idx) ?: -1
        val gc = groupColor()
        val gr = groupRow()
        return (gc < 0 || v == gc) && (gr < 0 || idx / pattern.width == gr)
    }

    fun toggle(idx: Int) {
        if (idx < 0) return
        if (pattern.cells[idx] < 0) return
        if (!inGroup(idx)) {
            val gc = groupColor()
            val gr = groupRow()
            status = when {
                gc >= 0 -> "逐色视图：只能勾 ${palette.get(gc)?.code ?: "这个色"} 的格子"
                gr >= 0 -> "逐行视图：只能勾第 ${gr + 1} 行的格子"
                else -> "这个格子不在当前视图里"
            }
            return
        }
        if (idx in done) {
            if (lockDone) {
                status = "防误触：已打勾的格子再点不会掉，要改请长按那一行"
                return
            }
            done.remove(idx)
        } else {
            done.add(idx)
            status = ""
        }
        doneVersion++
    }

    // 逐色视图：把当前颜色剩下的格子一次勾完，适合大面积纯色
    fun markColorAll() {
        val gc = groupColor()
        if (gc < 0) return
        val targets = pattern.cells.indices.filter { pattern.cells[it] == gc && it !in done }
        if (targets.isEmpty()) {
            status = "这一色已经全勾完了"
            return
        }
        done.addAll(targets)
        doneVersion++
        status = "全勾 ${palette.get(gc)?.code ?: "这一色"}：补上 ${targets.size} 颗"
    }

    fun clearGroup(idx: Int) {
        if (idx < 0) {
            status = "这里还没有打勾"
            return
        }
        val rowOf = idx / pattern.width
        val gc = groupColor()
        val gr = groupRow()
        val targets = when {
            gc >= 0 -> done.filter { pattern.cells[it] == gc }
            gr >= 0 -> done.filter { it / pattern.width == gr }
            else -> done.filter { it / pattern.width == rowOf }
        }
        if (targets.isEmpty()) {
            status = "这里还没有打勾"
            return
        }
        done.removeAll(targets.toSet())
        doneVersion++
        status = "清掉 ${targets.size} 个勾"
    }

    fun jumpToNextUndone() {
        val n = pattern.width * pattern.height
        if (n <= 0) return
        val start = if (cursor in 0 until n) cursor + 1 else 0
        var found = -1
        for (k in 0 until n) {
            val idx = (start + k) % n
            if (pattern.cells[idx] >= 0 && idx !in done) {
                found = idx
                break
            }
        }
        if (found < 0) {
            status = "全部拼完了"
            return
        }
        cursor = found
        val r = found / pattern.width
        val c = found % pattern.width
        when (view) {
            BuildView.ByRow -> {
                row = r
                highlight = r
            }
            BuildView.ByColor -> highlight = pattern.cells[found]
            BuildView.All -> Unit
        }
        val code = palette.get(pattern.cells[found])?.code ?: ""
        status = "下一处：第 ${r + 1} 行 · 第 ${c + 1} 列 · $code"
        jumpTick++
    }

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
                Modifier
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, top = 6.dp),
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
                            status = ""
                        },
                        label = { Text(v.label) },
                    )
                }
            }

            // 第二行：三个开关 + 复位（缩放过才出现）
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(
                    selected = onlyUndone,
                    onClick = { onlyUndone = !onlyUndone },
                    label = { Text("未完成") },
                )
                FilterChip(
                    selected = showCodes,
                    onClick = { showCodes = !showCodes },
                    label = { Text("色号") },
                )
                FilterChip(
                    selected = lockDone,
                    onClick = { lockDone = !lockDone },
                    label = { Text("防误触") },
                )
                Spacer(Modifier.weight(1f))
                if (scale > 1.01f) {
                    TextButton(onClick = { scale = 1f; pan = Offset.Zero }) {
                        Text("复位", style = MaterialTheme.typography.labelMedium)
                    }
                }
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
                                .clickable { highlight = idx; status = "" },
                        )
                    }
                }
            }

            if (view == BuildView.ByColor) {
                val active = highlight
                val colorTotal = if (active >= 0) pattern.cells.count { it == active } else 0
                val colorDone = if (active >= 0) {
                    done.count { it in pattern.cells.indices && pattern.cells[it] == active }
                } else {
                    0
                }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        "已勾 $colorDone / $colorTotal",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.weight(1f))
                    TextButton(
                        onClick = { markColorAll() },
                        enabled = active >= 0 && colorDone < colorTotal,
                    ) {
                        Text("这一色全勾上")
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

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (total - finished > 0) {
                        "还剩 ${total - finished} 颗 · 约 $remainMinutes 分钟"
                    } else {
                        "全部拼完了"
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            // 状态行：跳转提示 / 视图限制提示，放这里就不会挡住按钮
            Box(Modifier.fillMaxWidth().height(22.dp).padding(horizontal = 12.dp)) {
                if (status.isNotEmpty()) {
                    Text(
                        text = status,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            BoxWithConstraints(
                Modifier.fillMaxWidth().weight(1f).background(Color(0xFFF7F7FA)),
            ) {
                val boxW = constraints.maxWidth.toFloat()
                val boxH = constraints.maxHeight.toFloat()
                val cell = min(boxW / pattern.width, boxH / pattern.height)
                val ox = (boxW - pattern.width * cell) / 2f
                val oy = (boxH - pattern.height * cell) / 2f
                val pivot = Offset(boxW / 2f, boxH / 2f)

                fun clampPan(p: Offset): Offset {
                    val maxX = (boxW * (scale - 1f)) / 2f
                    val maxY = (boxH * (scale - 1f)) / 2f
                    return Offset(p.x.coerceIn(-maxX, maxX), p.y.coerceIn(-maxY, maxY))
                }

                fun indexAt(pos: Offset): Int {
                    val bx = (pos.x - pan.x - pivot.x) / scale + pivot.x
                    val by = (pos.y - pan.y - pivot.y) / scale + pivot.y
                    val cx = ((bx - ox) / cell).toInt()
                    val cy = ((by - oy) / cell).toInt()
                    return if (cx in 0 until pattern.width && cy in 0 until pattern.height) {
                        cy * pattern.width + cx
                    } else {
                        -1
                    }
                }

                // 「下一处未完成」：放大状态下把目标挪到画面中间
                LaunchedEffect(jumpTick) {
                    if (jumpTick == 0 || cursor !in 0 until pattern.width * pattern.height) return@LaunchedEffect
                    if (scale <= 1.05f) return@LaunchedEffect
                    val c = cursor % pattern.width
                    val r = cursor / pattern.width
                    val bcx = ox + c * cell + cell / 2f
                    val bcy = oy + r * cell + cell / 2f
                    pan = clampPan(
                        Offset(
                            pivot.x - (bcx - pivot.x) * scale,
                            pivot.y - (bcy - pivot.y) * scale,
                        ),
                    )
                }

                Canvas(
                    Modifier
                        .fillMaxSize()
                        // 放大后内容会画到框外，必须裁掉，否则会盖住上面的按钮
                        .clipToBounds()
                        .pointerInput(Unit) {
                            detectTransformGestures { centroid, panChange, zoomChange, _ ->
                                val old = scale
                                val ns = (old * zoomChange).coerceIn(1f, MAX_ZOOM)
                                // 保持捏合中心不动
                                val baseX = (centroid.x - pan.x - pivot.x) / old + pivot.x
                                val baseY = (centroid.y - pan.y - pivot.y) / old + pivot.y
                                val np = Offset(
                                    centroid.x - pivot.x - (baseX - pivot.x) * ns,
                                    centroid.y - pivot.y - (baseY - pivot.y) * ns,
                                )
                                scale = ns
                                pan = clampPan(np + panChange)
                            }
                        }
                        .pointerInput(Unit) {
                            detectTapGestures(
                                onTap = { pos -> toggle(indexAt(pos)) },
                                onLongPress = { pos -> clearGroup(indexAt(pos)) },
                                onDoubleTap = { pos ->
                                    // 双击放大 / 回到 1x，放大时以双击点为中心
                                    if (scale > 1.01f) {
                                        scale = 1f
                                        pan = Offset.Zero
                                    } else {
                                        val ns = 2.2f
                                        val bx = (pos.x - pan.x - pivot.x) / scale + pivot.x
                                        val by = (pos.y - pan.y - pivot.y) / scale + pivot.y
                                        val maxX = (boxW * (ns - 1f)) / 2f
                                        val maxY = (boxH * (ns - 1f)) / 2f
                                        pan = Offset(
                                            (pos.x - pivot.x - (bx - pivot.x) * ns).coerceIn(-maxX, maxX),
                                            (pos.y - pivot.y - (by - pivot.y) * ns).coerceIn(-maxY, maxY),
                                        )
                                        scale = ns
                                    }
                                },
                            )
                        },
                ) {
                    @Suppress("UNUSED_EXPRESSION")
                    doneVersion
                    withTransform({
                        translate(pan.x, pan.y)
                        scale(scale, scale, pivot)
                    }) {
                        for (y in 0 until pattern.height) {
                            for (x in 0 until pattern.width) {
                                val idx = y * pattern.width + x
                                val v = pattern.cells[idx]
                                val left = ox + x * cell
                                val top = oy + y * cell
                                val hidden = onlyUndone && idx in done
                                val argb = if (v < 0 || hidden) {
                                    0xFFEDEDF2.toInt()
                                } else {
                                    palette.get(v)?.argb ?: 0xFFEDEDF2.toInt()
                                }
                                drawRect(Color(argb), Offset(left, top), Size(cell, cell))
                                if (!inGroup(idx) && !hidden) {
                                    drawRect(
                                        Color(0xCCFFFFFF),
                                        Offset(left, top),
                                        Size(cell, cell),
                                    )
                                }
                                if (idx in done && !onlyUndone) {
                                    val c = Offset(left + cell / 2f, top + cell / 2f)
                                    drawCircle(Color(0x33000000), radius = cell * 0.46f, center = c)
                                    drawCircle(Color(0xFF6C4BF4), radius = cell * 0.26f, center = c)
                                    drawCircle(Color(0xFFFFFFFF), radius = cell * 0.11f, center = c)
                                }
                            }
                        }
                        if (cell * scale >= 5f) {
                            val gridColor = Color(0x22555566)
                            val hair = 1f / scale
                            for (x in 0..pattern.width) {
                                drawLine(
                                    gridColor,
                                    Offset(ox + x * cell, oy),
                                    Offset(ox + x * cell, oy + pattern.height * cell),
                                    hair,
                                )
                            }
                            for (y in 0..pattern.height) {
                                drawLine(
                                    gridColor,
                                    Offset(ox, oy + y * cell),
                                    Offset(ox + pattern.width * cell, oy + y * cell),
                                    hair,
                                )
                            }
                            val boardColor = Color(0x774A4A55)
                            var bx = 29
                            while (bx < pattern.width) {
                                drawLine(
                                    boardColor,
                                    Offset(ox + bx * cell, oy),
                                    Offset(ox + bx * cell, oy + pattern.height * cell),
                                    (cell * 0.14f).coerceAtLeast(1.5f) / scale,
                                )
                                bx += 29
                            }
                            var by = 29
                            while (by < pattern.height) {
                                drawLine(
                                    boardColor,
                                    Offset(ox, oy + by * cell),
                                    Offset(ox + pattern.width * cell, oy + by * cell),
                                    (cell * 0.14f).coerceAtLeast(1.5f) / scale,
                                )
                                by += 29
                            }
                        }

                        if (cursor in 0 until pattern.width * pattern.height) {
                            val cxx = cursor % pattern.width
                            val cyy = cursor / pattern.width
                            val ccx = ox + cxx * cell + cell / 2f
                            val ccy = oy + cyy * cell + cell / 2f
                            drawCircle(
                                color = Color(0xFF6C4BF4),
                                radius = cell * 0.46f,
                                center = Offset(ccx, ccy),
                                style = Stroke(width = (cell * 0.12f).coerceAtLeast(2f) / scale),
                            )
                        }

                        if (showCodes && cell * scale >= 9f) {
                            val paint = android.graphics.Paint().apply {
                                isAntiAlias = true
                                textAlign = android.graphics.Paint.Align.CENTER
                                textSize = (cell * 0.52f).coerceAtMost(13f)
                            }
                            drawIntoCanvas { canvas ->
                                val native = canvas.nativeCanvas
                                for (yy in 0 until pattern.height) {
                                    for (xx in 0 until pattern.width) {
                                        val ii = yy * pattern.width + xx
                                        val vv = pattern.cells[ii]
                                        if (vv < 0) continue
                                        if (onlyUndone && ii in done) continue
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
            }

            Row(
                Modifier.fillMaxWidth().padding(start = 12.dp, end = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (view == BuildView.All) {
                        "点格子打勾，长按清这一行的勾 · 双指缩放"
                    } else if (view == BuildView.ByColor) {
                        "只勾当前颜色，长按清这个颜色的勾 · 双指缩放"
                    } else {
                        "只勾当前行，长按清这一行的勾 · 双指缩放"
                    },
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = { jumpToNextUndone() }) { Text("下一处未完成 ›") }
            }
        }
    }
}
