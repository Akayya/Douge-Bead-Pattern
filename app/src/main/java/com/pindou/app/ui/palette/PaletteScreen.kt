package com.pindou.app.ui.palette

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pindou.app.data.BeadColor
import com.pindou.app.data.HexColor
import com.pindou.app.data.InventoryCsv
import com.pindou.app.data.Palette
import com.pindou.app.data.PaletteCsv
import com.pindou.app.data.PaletteId
import com.pindou.app.data.PaletteGroups
import com.pindou.app.data.InventoryStore
import com.pindou.app.data.PaletteStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

/**
 * 色板页：切换内置 / 自定义色板，按色系筛，点色块切换「我有 / 没有」。
 * 没有的色号会在转图纸时被排除，避免出图给一堆买不到的色号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteScreen(
    store: PaletteStore,
    inventory: InventoryStore,
    onBack: () -> Unit,
    paletteUsage: (String) -> Int = { 0 },
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val palette = store.current
    val palettes = remember(store.revision) { store.availablePalettes() }
    var group by remember(palette.id) { mutableStateOf<String?>(null) }
    var editColor by remember { mutableStateOf<BeadColor?>(null) }
    var editCount by remember { mutableStateOf("") }
    var calibrateColor by remember { mutableStateOf<BeadColor?>(null) }
    var calibrateText by remember { mutableStateOf("") }
    var readingCsv by remember { mutableStateOf(false) }
    var importPreview by remember { mutableStateOf<InventoryCsv.Parsed?>(null) }
    var readingPaletteCsv by remember { mutableStateOf(false) }
    var paletteImport by remember { mutableStateOf<PaletteCsv.Parsed?>(null) }
    var paletteImportTitle by remember { mutableStateOf("") }
    var deletePalette by remember { mutableStateOf<Palette?>(null) }
    val shown = if (group == null) palette.colors else palette.colors.filter { it.group == group }

    val csvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        readingCsv = true
        scope.launch {
            val targetPalette = palette
            val parsed = withContext(Dispatchers.IO) {
                try {
                    InventoryCsv.parse(readCsvText(context, uri), targetPalette)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    InventoryCsv.Parsed(error = "读取失败：${e.message ?: "格式不支持"}")
                }
            }
            readingCsv = false
            when {
                parsed.error != null -> snackbar.showSnackbar(parsed.error)
                parsed.counts.isEmpty() -> snackbar.showSnackbar(
                    "没有可导入的库存：检查色号是否属于 ${targetPalette.title}、库存列是否为空",
                )
                else -> importPreview = parsed
            }
        }
    }

    val paletteCsvPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        readingPaletteCsv = true
        scope.launch {
            val parsed = withContext(Dispatchers.IO) {
                try {
                    val fallback = queryDisplayName(context, uri)
                        ?.substringBeforeLast('.')
                        ?.trim()
                        ?.take(30)
                        ?.ifBlank { "自定义色板" }
                        ?: "自定义色板"
                    PaletteCsv.parse(readCsvText(context, uri), fallback)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    PaletteCsv.Parsed("自定义色板", error = "读取失败：${e.message ?: "格式不支持"}")
                }
            }
            readingPaletteCsv = false
            if (parsed.error != null) {
                snackbar.showSnackbar(parsed.error)
            } else {
                paletteImportTitle = parsed.title
                paletteImport = parsed
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("色板", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = buildString {
                                if (store.isCustom(palette.id)) append("自定义 · ")
                                append("${palette.title} · 已有 ${store.enabledCount} / ${palette.count} 色")
                                val calibrated = store.calibratedCount
                                if (calibrated > 0) append(" · 校准 $calibrated 色")
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                navigationIcon = {
                    TextButton(onClick = onBack) { Text("返回") }
                },
                actions = {
                    TextButton(
                        onClick = {
                            if (group != null) store.setGroupEnabled(group!!, true)
                            else palette.groups.forEach { store.setGroupEnabled(it, true) }
                        },
                    ) { Text("全选") }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            LazyRow(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(palettes, key = { it.id }) { item ->
                    FilterChip(
                        selected = palette.id == item.id,
                        onClick = { store.switchPalette(item.id) },
                        label = {
                            Text(
                                text = when (item.id) {
                                    PaletteId.MARD_221 -> "MARD 221"
                                    PaletteId.MARD_291 -> "MARD 291"
                                    else -> item.title
                                },
                                maxLines = 1,
                            )
                        },
                    )
                }
                item {
                    AssistChip(
                        onClick = {
                            paletteCsvPicker.launch(
                                arrayOf("text/*", "application/csv", "application/octet-stream"),
                            )
                        },
                        enabled = !readingPaletteCsv,
                        label = { Text(if (readingPaletteCsv) "读取中…" else "导入色板") },
                    )
                }
                if (store.isCustom(palette.id)) {
                    item {
                        AssistChip(
                            onClick = { deletePalette = palette },
                            label = { Text("删除此色板") },
                        )
                    }
                }
            }

            Text(
                "点色块切换「我有 / 没有」；长按记库存或校准颜色（屏幕色和实物豆子对不上时用）",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp),
            )

            LazyRow(
                contentPadding = PaddingValues(horizontal = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item {
                    FilterChip(
                        selected = group == null,
                        onClick = { group = null },
                        label = { Text("全部 ${palette.count}") },
                    )
                }
                items(palette.groups) { g ->
                    FilterChip(
                        selected = group == g,
                        onClick = { group = if (group == g) null else g },
                        label = { Text("${PaletteGroups.name(g)} ${palette.groupSizes[g] ?: 0}") },
                    )
                }
            }

            Spacer(Modifier.height(4.dp))

            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "没有逐色记库存？",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                TextButton(
                    onClick = {
                        csvPicker.launch(
                            arrayOf("text/*", "application/csv", "application/octet-stream"),
                        )
                    },
                    enabled = !readingCsv,
                ) {
                    Text(if (readingCsv) "读取中…" else "批量导入 CSV")
                }
            }

            LazyVerticalGrid(
                columns = GridCells.Adaptive(64.dp),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
                modifier = Modifier.fillMaxSize(),
            ) {
                items(shown) { color ->
                    ColorCell(
                        color = color,
                        enabled = store.isEnabled(color.code),
                        stock = inventory.count(palette.id, color.code),
                        calibrated = store.calibratedHex(color.code) != null,
                        onToggle = { store.toggle(color.code) },
                        onLongPress = {
                            editColor = color
                            editCount = inventory.count(palette.id, color.code)?.toString() ?: ""
                        },
                    )
                }
            }
        }
    }

    val target = editColor
    if (target != null) {
        StockDialog(
            color = target,
            calibrated = store.calibratedHex(target.code) != null,
            originalHex = store.originalHex(target.code),
            text = editCount,
            onTextChange = { editCount = it.filter(Char::isDigit).take(6) },
            onDismiss = { editColor = null },
            onClear = {
                inventory.set(palette.id, target.code, null)
                editColor = null
            },
            onSave = {
                val n = editCount.toIntOrNull()
                inventory.set(palette.id, target.code, n)
                editColor = null
            },
            onCalibrate = {
                editColor = null
                calibrateColor = target
                calibrateText = target.hex.removePrefix("#")
            },
        )
    }

    val preview = importPreview
    if (preview != null) {
        AlertDialog(
            onDismissRequest = { importPreview = null },
            title = { Text("导入库存") },
            text = {
                Column {
                    Text(
                        "识别 ${preview.totalRows} 行，可更新 ${preview.counts.size} 个色号。",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    if (preview.unknown.isNotEmpty()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "跳过 ${preview.unknown.size} 个不属于当前色板的色号：" +
                                preview.unknown.take(6).joinToString("、") +
                                if (preview.unknown.size > 6) " 等" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    if (preview.invalidRows > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "跳过 ${preview.invalidRows} 行数量无效的数据",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "只更新文件里出现且数量非空的色号；未提到的色号保持原样。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        inventory.setAll(palette.id, preview.counts)
                        val count = preview.counts.size
                        importPreview = null
                        scope.launch { snackbar.showSnackbar("已导入 $count 个色号的库存") }
                    },
                ) { Text("导入 ${preview.counts.size} 个") }
            },
            dismissButton = {
                TextButton(onClick = { importPreview = null }) { Text("取消") }
            },
        )
    }

    val paletteDraft = paletteImport
    if (paletteDraft != null) {
        AlertDialog(
            onDismissRequest = { paletteImport = null },
            title = { Text("导入色板") },
            text = {
                Column {
                    OutlinedTextField(
                        value = paletteImportTitle,
                        onValueChange = { paletteImportTitle = it.take(30) },
                        singleLine = true,
                        label = { Text("色板名称") },
                    )
                    Spacer(Modifier.height(10.dp))
                    Text(
                        "识别 ${paletteDraft.colors.size} 色" +
                            if (paletteDraft.invalidRows > 0) {
                                "，跳过 ${paletteDraft.invalidRows} 行无效数据（重复色号、颜色格式不对或列数不足）"
                            } else {
                                ""
                            },
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        for (color in paletteDraft.colors.take(8)) {
                            Box(
                                Modifier
                                    .size(22.dp)
                                    .background(Color(color.argb), RoundedCornerShape(5.dp))
                                    .border(
                                        1.dp,
                                        MaterialTheme.colorScheme.outline.copy(alpha = 0.4f),
                                        RoundedCornerShape(5.dp),
                                    ),
                            )
                        }
                        if (paletteDraft.colors.size > 8) {
                            Text(
                                "+${paletteDraft.colors.size - 8}",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "导入后会自动切换到新色板。图纸会记住自己使用的色板，切换不影响已有图纸。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = paletteImportTitle.trim().isNotEmpty(),
                    onClick = {
                        val imported = store.importCustomPalette(paletteImportTitle, paletteDraft.colors)
                        paletteImport = null
                        store.switchPalette(imported.id)
                        scope.launch {
                            snackbar.showSnackbar("已导入色板「${imported.title}」，共 ${imported.count} 色")
                        }
                    },
                ) { Text("导入") }
            },
            dismissButton = {
                TextButton(onClick = { paletteImport = null }) { Text("取消") }
            },
        )
    }

    val deleting = deletePalette
    if (deleting != null) {
        val usage = paletteUsage(deleting.id)
        AlertDialog(
            onDismissRequest = { deletePalette = null },
            title = { Text("删除色板") },
            text = {
                Column {
                    Text("「${deleting.title}」将从本机删除，颜色校准、库存和「我有 / 没有」设置会一并清除。")
                    if (usage > 0) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "有 $usage 张图纸正在使用这个色板，先把这些图纸删掉或换成别的色板，再回来删除。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    enabled = usage == 0,
                    onClick = {
                        val deleted = store.deleteCustomPalette(deleting.id)
                        if (deleted) inventory.clearPalette(deleting.id)
                        deletePalette = null
                        scope.launch {
                            snackbar.showSnackbar(
                                if (deleted) "已删除色板「${deleting.title}」" else "删除失败，请稍后重试",
                            )
                        }
                    },
                ) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { deletePalette = null }) { Text("取消") }
            },
        )
    }

    val calibrating = calibrateColor
    if (calibrating != null) {
        val normalized = HexColor.normalize(calibrateText)
        val previewArgb = normalized?.let {
            runCatching { android.graphics.Color.parseColor(it) }.getOrNull()
        }
        AlertDialog(
            onDismissRequest = { calibrateColor = null },
            title = { Text("校准 ${calibrating.code}") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .size(44.dp)
                                .background(
                                    Color(previewArgb ?: calibrating.argb),
                                    RoundedCornerShape(8.dp),
                                )
                                .border(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.4f), RoundedCornerShape(8.dp)),
                        )
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(
                                "原始 ${store.originalHex(calibrating.code) ?: calibrating.hex}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Text(
                                if (normalized != null) "新色 $normalized" else "输入 6 位 HEX",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = calibrateText,
                        onValueChange = { input ->
                            calibrateText = input
                                .filter { it.isDigit() || it in 'a'..'f' || it in 'A'..'F' }
                                .take(6)
                        },
                        singleLine = true,
                        label = { Text("屏幕 HEX") },
                        prefix = { Text("#") },
                        isError = normalized == null,
                        supportingText = {
                            if (normalized == null) Text("格式：6 位十六进制，例如 F73D48")
                        },
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "把屏幕颜色调成你手上豆子的颜色。图纸、缩略图、导出和分享的 PNG 都会跟着变，图纸格子数据不动。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = normalized != null,
                    onClick = {
                        store.calibrate(calibrating.code, normalized)
                        calibrateColor = null
                    },
                ) { Text("保存") }
            },
            dismissButton = {
                Row {
                    if (store.calibratedHex(calibrating.code) != null) {
                        TextButton(onClick = {
                            store.calibrate(calibrating.code, null)
                            calibrateColor = null
                        }) { Text("恢复默认") }
                    }
                    TextButton(onClick = { calibrateColor = null }) { Text("取消") }
                }
            },
        )
    }
}

@Composable
private fun StockDialog(
    color: BeadColor,
    calibrated: Boolean,
    originalHex: String?,
    text: String,
    onTextChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onClear: () -> Unit,
    onSave: () -> Unit,
    onCalibrate: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${color.code} 库存") },
        text = {
            Column {
                Text(
                    "${color.hex} · 记一下你手上有多少颗，图纸页会算还差多少",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (calibrated) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        "已校准（原始 ${originalHex ?: "-"}）",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                Spacer(Modifier.height(10.dp))
                OutlinedTextField(
                    value = text,
                    onValueChange = onTextChange,
                    singleLine = true,
                    label = { Text("颗数") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                TextButton(
                    onClick = onCalibrate,
                    contentPadding = PaddingValues(0.dp),
                ) {
                    Text(if (calibrated) "已校准 · 重新校准…" else "屏幕颜色和实物对不上？校准…")
                }
            }
        },
        confirmButton = { TextButton(onClick = onSave) { Text("保存") } },
        dismissButton = {
            Row {
                TextButton(onClick = onClear) { Text("不追踪") }
                TextButton(onClick = onDismiss) { Text("取消") }
            }
        },
    )
}

@Composable
private fun ColorCell(
    color: BeadColor,
    enabled: Boolean,
    stock: Int?,
    calibrated: Boolean,
    onToggle: () -> Unit,
    onLongPress: () -> Unit,
) {
    val outline = MaterialTheme.colorScheme.outline
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.combinedClickable(onClick = onToggle, onLongClick = onLongPress),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .height(48.dp)
                .background(Color(color.argb), RoundedCornerShape(10.dp))
                .border(
                    width = if (enabled) 1.dp else 2.dp,
                    color = if (enabled) outline.copy(alpha = 0.25f) else MaterialTheme.colorScheme.error,
                    shape = RoundedCornerShape(10.dp),
                ),
        ) {
            if (calibrated) {
                Text(
                    text = "校",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                    color = Color.White,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(3.dp)
                        .background(Color.Black.copy(alpha = 0.6f), RoundedCornerShape(4.dp))
                        .padding(horizontal = 3.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = color.code,
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
            color = if (enabled) {
                MaterialTheme.colorScheme.onSurfaceVariant
            } else {
                MaterialTheme.colorScheme.error
            },
            textDecoration = if (enabled) TextDecoration.None else TextDecoration.LineThrough,
        )
        // 固定占一行高度：不追踪时也留空，免得记了库存以后整格网格跳动
        Text(
            text = when {
                stock == null -> ""
                stock == 0 -> "缺"
                else -> "$stock"
            },
            style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
            color = if (stock == 0) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.primary
            },
            modifier = Modifier.height(13.dp),
        )
    }
}

private const val MAX_CSV_BYTES = 2 * 1024 * 1024

/** 拿 SAF 文件名，给色板起个默认名字；读不到就用兜底名。 */
private fun queryDisplayName(context: Context, uri: Uri): String? = runCatching {
    context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
        ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
}.getOrNull()

/** 读库存 CSV：限 2 MB；先按 UTF-8 严格解，失败再按 GB18030（中文 Excel 常见）。 */
private fun readCsvText(context: Context, uri: Uri): String {
    val bytes = context.contentResolver.openInputStream(uri)?.use { input ->
        val out = ByteArrayOutputStream()
        val chunk = ByteArray(8192)
        var total = 0
        while (true) {
            val n = input.read(chunk)
            if (n < 0) break
            total += n
            if (total > MAX_CSV_BYTES) throw IllegalArgumentException("文件超过 2 MB")
            out.write(chunk, 0, n)
        }
        out.toByteArray()
    } ?: throw IllegalArgumentException("打不开文件")
    return runCatching {
        StandardCharsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .toString()
    }.getOrElse {
        String(bytes, Charset.forName("GB18030"))
    }
}
