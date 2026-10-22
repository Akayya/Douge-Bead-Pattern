package com.pindou.app.ui.pattern

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlin.math.roundToInt
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.pindou.app.data.CostSettings
import com.pindou.app.data.InventoryStore
import com.pindou.app.data.Palette
import com.pindou.app.data.Pattern
import com.pindou.app.data.PatternCleaner
import com.pindou.app.data.PatternExporter
import com.pindou.app.data.PatternPdf
import com.pindou.app.data.PatternReducer
import com.pindou.app.data.PatternTransform
import com.pindou.app.data.PatternStore
import com.pindou.app.ui.PatternCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PatternDetailScreen(
    pattern: Pattern,
    palette: Palette,
    inventory: InventoryStore,
    onBack: () -> Unit,
    onEdit: () -> Unit = {},
    onBuild: () -> Unit = {},
    onPatternChanged: (Pattern) -> Unit = {},
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    var current by remember(pattern.id) { mutableStateOf(pattern) }
    var showClean by remember { mutableStateOf(false) }
    var showReduce by remember { mutableStateOf(false) }
    var showCanvas by remember { mutableStateOf(false) }
    val cost = remember { CostSettings(context) }
    var editPrice by remember { mutableStateOf(false) }
    var editMinutes by remember { mutableStateOf(false) }
    var priceText by remember { mutableStateOf("") }
    var minutesText by remember { mutableStateOf("") }
    var exportingPdf by remember { mutableStateOf(false) }
    var exporting by remember { mutableStateOf(false) }
    var reduceTo by remember { mutableFloatStateOf(6f) }
    var strength by remember { mutableStateOf(PatternCleaner.Strength.MEDIUM) }
    // 详情页的一次性操作（去杂点 / 减色 / 画布变换）都会覆盖原图，
    // 这里存一份改动前的快照，给「撤销上一步」用；只保留一步，够救命就行。
    var undoPattern by remember(pattern.id) { mutableStateOf<Pattern?>(null) }

    // 图纸库那边刷新了同一个图纸（比如改了名）就同步过来
    LaunchedEffect(pattern) {
        if (pattern !== current && pattern.id == current.id) current = pattern
    }

    val usage = current.usage(palette)
    // 只统计「记过库存」且不够的色号；没记过的不算缺口
    val shortages = usage.mapNotNull { item ->
        val have = inventory.count(current.paletteId, item.color.code) ?: return@mapNotNull null
        if (have >= item.count) null else item.color.code to (item.count - have)
    }
    val missingTotal = shortages.sumOf { it.second }

    fun commit(updated: Pattern) {
        undoPattern = current
        PatternStore.save(context, updated)
        current = updated
        onPatternChanged(updated)
    }

    fun undoLast() {
        val back = undoPattern ?: return
        PatternStore.save(context, back)
        current = back
        onPatternChanged(back)
        undoPattern = null
        scope.launch { snackbar.showSnackbar("已撤销上一步") }
    }

    fun applyTransform(res: PatternTransform.Result) {
        val label = res.label
        if (label == null) {
            scope.launch { snackbar.showSnackbar("没有可改的地方") }
            return
        }
        val updated = Pattern(
            id = current.id,
            name = current.name,
            width = res.width,
            height = res.height,
            paletteId = current.paletteId,
            cells = res.cells,
            createdAt = current.createdAt,
            updatedAt = System.currentTimeMillis(),
            done = mutableSetOf(),
        )
        commit(updated)
        scope.launch { snackbar.showSnackbar("$label，施工进度已清空") }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(current.name, style = MaterialTheme.typography.titleLarge) },
                navigationIcon = { TextButton(onClick = onBack) { Text("返回") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(inner),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
                ) {
                    Box(Modifier.fillMaxWidth().padding(12.dp)) {
                        PatternCanvas(
                            pattern = current,
                            palette = palette,
                            showGrid = true,
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(current.width.toFloat() / current.height),
                        )
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    Stat("尺寸", "${current.width}×${current.height}", Modifier.weight(1f))
                    Stat("总颗数", "${current.beadCount}", Modifier.weight(1f))
                    Stat("颜色数", "${usage.size}", Modifier.weight(1f))
                }
            }

            item {
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant,
                    ),
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Text("成本与工时", style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(8.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                priceText = cost.pricePerBead.toString()
                                editPrice = true
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("豆子单价", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "${CostSettings.yuan(cost.pricePerBead)} / 颗",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Row(
                            Modifier.fillMaxWidth().clickable {
                                minutesText = cost.minutesPer100.toString()
                                editMinutes = true
                            },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text("工时估算", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.weight(1f))
                            Text(
                                "每 100 颗 ${cost.minutesPer100.toInt()} 分钟",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "合计 ${CostSettings.yuan(cost.priceOf(current.beadCount))}" +
                                " · 预计 ${CostSettings.duration(cost.minutesOf(current.beadCount))}",
                            style = MaterialTheme.typography.titleSmall,
                        )
                        if (shortages.isNotEmpty()) {
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "豆仓缺 ${shortages.size} 色 / 共 $missingTotal 颗" +
                                    "（补货约 ${CostSettings.yuan(cost.priceOf(missingTotal))}）",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.error,
                            )
                        }
                    }
                }
            }

            if (undoPattern != null) {
                item {
                    OutlinedButton(
                        onClick = { undoLast() },
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Text("撤销上一步（回到改之前）")
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = { showClean = true }, modifier = Modifier.weight(1f)) {
                        Text("去杂点")
                    }
                    OutlinedButton(
                        onClick = {
                            reduceTo = (usage.size - 2).coerceAtLeast(2).toFloat()
                            showReduce = true
                        },
                        modifier = Modifier.weight(1f),
                        enabled = usage.size > 2,
                    ) {
                        Text("减到 N 色")
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) {
                        Text("编辑")
                    }
                    OutlinedButton(onClick = onBuild, modifier = Modifier.weight(1f)) {
                        Text("施工")
                    }
                }
            }

            item {
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    OutlinedButton(onClick = { showCanvas = true }, modifier = Modifier.weight(1f)) {
                        Text("画布")
                    }
                    OutlinedButton(
                        onClick = {
                            if (exporting) return@OutlinedButton
                            exporting = true
                            scope.launch {
                                val res = withContext(Dispatchers.Default) {
                                    PatternExporter.exportPng(context, current, palette)
                                }
                                exporting = false
                                snackbar.showSnackbar("已导出：${res.savedAs}")
                            }
                        },
                        modifier = Modifier.weight(1f),
                        enabled = !exporting,
                    ) {
                        Text(if (exporting) "导出中…" else "导出 PNG")
                    }
                }
            }

            item {
                OutlinedButton(
                    onClick = {
                        if (exportingPdf) return@OutlinedButton
                        exportingPdf = true
                        scope.launch {
                            val res = withContext(Dispatchers.Default) {
                                PatternPdf.export(context, current, palette, cost)
                            }
                            exportingPdf = false
                            if (res == null) {
                                snackbar.showSnackbar("导出失败，检查存储空间")
                            } else {
                                snackbar.showSnackbar("已导出 ${res.pages} 页：${res.savedAs}")
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !exportingPdf,
                ) {
                    Text(
                        if (exportingPdf) {
                            "生成 PDF 中…"
                        } else {
                            "导出 A4 PDF（共 ${PatternPdf.pageCount(current)} 页）"
                        },
                    )
                }
            }

            item {
                Text(
                    "色号明细（按用量排序）",
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            items(usage) { item ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier
                            .size(28.dp)
                            .clip(RoundedCornerShape(6.dp))
                            .background(Color(item.color.argb)),
                    )
                    Spacer(Modifier.width(12.dp))
                    Text(item.color.code, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.width(64.dp))
                    Text(
                        item.color.hex,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.weight(1f),
                    )
                    Column(horizontalAlignment = Alignment.End) {
                        Text("${item.count} 颗", style = MaterialTheme.typography.bodyLarge)
                        if (item.count > 100) {
                            val bags = item.count / 100
                            val rest = item.count % 100
                            Text(
                                text = if (rest == 0) "分 $bags 袋" else "$bags×100 + $rest",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Text(
                            text = CostSettings.yuan(cost.priceOf(item.count)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        val have = inventory.count(current.paletteId, item.color.code)
                        if (have != null) {
                            val short = item.count - have
                            Text(
                                text = if (short > 0) "库存 $have · 缺 $short" else "库存 $have · 够",
                                style = MaterialTheme.typography.labelSmall,
                                color = if (short > 0) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                            )
                        }
                    }
                }
            }

            item {
                HorizontalDivider(Modifier.padding(vertical = 8.dp))
                Text(
                    "合计 ${current.beadCount} 颗 / ${usage.size} 色。编辑、施工模式在 M3 接上，导出在 M4。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(24.dp))
            }
        }
    }

    if (showClean) {
        AlertDialog(
            onDismissRequest = { showClean = false },
            title = { Text("去杂点") },
            text = {
                Column {
                    Text(
                        "把孤立的单格杂色并到周围主色。线条、描边不会被吃掉。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(10.dp))
                    PatternCleaner.Strength.entries.forEach { s ->
                        Row(
                            Modifier.fillMaxWidth().clickable { strength = s }.padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            RadioButton(selected = strength == s, onClick = { strength = s })
                            Spacer(Modifier.width(4.dp))
                            Column {
                                Text(strongLabel(s), style = MaterialTheme.typography.bodyLarge)
                                Text(
                                    strongHint(s),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showClean = false
                    val res = PatternCleaner.despeckle(current.cells, current.width, current.height, palette, strength)
                    val updated = Pattern(
                        id = current.id,
                        name = current.name,
                        width = current.width,
                        height = current.height,
                        paletteId = current.paletteId,
                        cells = res.cells,
                        createdAt = current.createdAt,
                        updatedAt = System.currentTimeMillis(),
                        done = current.done,
                    )
                    commit(updated)
                    scope.launch {
                        snackbar.showSnackbar(
                            if (res.changed == 0) "没有找到杂点" else "清掉 ${res.changed} 个杂点",
                        )
                    }
                }) { Text("执行") }
            },
            dismissButton = { TextButton(onClick = { showClean = false }) { Text("取消") } },
        )
    }

    if (showReduce) {
        val target = reduceTo.roundToInt().coerceIn(2, usage.size.coerceAtLeast(2))
        val kept = usage.take(target).sumOf { it.count }
        val affected = current.beadCount - kept
        AlertDialog(
            onDismissRequest = { showReduce = false },
            title = { Text("减到 $target 色") },
            text = {
                Column {
                    Text(
                        "现在 ${usage.size} 色。保留用量最多的 $target 个色号，其余并到最接近的保留色。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Slider(
                        value = reduceTo,
                        onValueChange = { reduceTo = it },
                        valueRange = 2f..usage.size.toFloat().coerceAtLeast(2f),
                    )
                    Text(
                        if (affected == 0) "不会改动任何格子" else "会有 $affected 颗豆子换色号",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showReduce = false
                    val res = PatternReducer.reduce(
                        current.cells,
                        current.width,
                        current.height,
                        palette,
                        target,
                    )
                    val updated = Pattern(
                        id = current.id,
                        name = current.name,
                        width = current.width,
                        height = current.height,
                        paletteId = current.paletteId,
                        cells = res.cells,
                        createdAt = current.createdAt,
                        updatedAt = System.currentTimeMillis(),
                        done = current.done,
                    )
                    commit(updated)
                    scope.launch { snackbar.showSnackbar("并掉 ${res.mergedColors} 个色号，改动 ${res.changed} 格") }
                }) { Text("执行") }
            },
            dismissButton = { TextButton(onClick = { showReduce = false }) { Text("取消") } },
        )
    }

    if (showCanvas) {
        AlertDialog(
            onDismissRequest = { showCanvas = false },
            title = { Text("画布") },
            text = {
                Column {
                    Text(
                        "会改变格子坐标，施工进度会清空。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    CanvasAction("裁掉空白边") {
                        showCanvas = false
                        applyTransform(PatternTransform.trimBlank(current.cells, current.width, current.height))
                    }
                    CanvasAction("四周各加 1 格") {
                        showCanvas = false
                        applyTransform(PatternTransform.pad(current.cells, current.width, current.height))
                    }
                    CanvasAction("顺时针旋转 90°") {
                        showCanvas = false
                        applyTransform(PatternTransform.rotateCw(current.cells, current.width, current.height))
                    }
                    CanvasAction("水平翻转") {
                        showCanvas = false
                        applyTransform(PatternTransform.flipHorizontal(current.cells, current.width, current.height))
                    }
                }
            },
            confirmButton = { TextButton(onClick = { showCanvas = false }) { Text("关闭") } },
        )
    }

    if (editPrice) {
        AlertDialog(
            onDismissRequest = { editPrice = false },
            title = { Text("豆子单价") },
            text = {
                Column {
                    Text(
                        "每颗豆子多少钱，用来算总成本。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = priceText,
                        onValueChange = { priceText = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        suffix = { Text("元 / 颗") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = priceText.trim().toFloatOrNull()
                    if (v != null && v >= 0f) {
                        cost.pricePerBead = v
                        cost.save()
                    }
                    editPrice = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editPrice = false }) { Text("取消") } },
        )
    }

    if (editMinutes) {
        AlertDialog(
            onDismissRequest = { editMinutes = false },
            title = { Text("工时估算") },
            text = {
                Column {
                    Text(
                        "平均每 100 颗要花多少分钟。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = minutesText,
                        onValueChange = { minutesText = it },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        suffix = { Text("分钟 / 100 颗") },
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    val v = minutesText.trim().toFloatOrNull()
                    if (v != null && v >= 0f) {
                        cost.minutesPer100 = v
                        cost.save()
                    }
                    editMinutes = false
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { editMinutes = false }) { Text("取消") } },
        )
    }
}

@Composable
private fun CanvasAction(label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
    ) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

private fun strongLabel(s: PatternCleaner.Strength): String = when (s) {
    PatternCleaner.Strength.LIGHT -> "轻（只处理完全孤立的单格）"
    PatternCleaner.Strength.MEDIUM -> "中（同色邻居 ≤1 就并，推荐）"
    PatternCleaner.Strength.STRONG -> "强（同中，重复擦 3 遍）"
}

private fun strongHint(s: PatternCleaner.Strength): String = when (s) {
    PatternCleaner.Strength.LIGHT -> "改动最小，适合本来就干净的图纸"
    PatternCleaner.Strength.MEDIUM -> "适合截图转出来的图纸"
    PatternCleaner.Strength.STRONG -> "会轻微吃掉细碎纹理，可撤销"
}

@Composable
private fun Stat(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(vertical = 10.dp, horizontal = 12.dp)) {
            Text(
                label,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
            Text(
                value,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
            )
        }
    }
}
