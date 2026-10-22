package com.pindou.app.ui.importsheet

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pindou.app.data.ImageToPattern
import com.pindou.app.data.Palette
import com.pindou.app.data.PaletteStore
import com.pindou.app.data.Pattern
import com.pindou.app.data.PatternStore
import com.pindou.app.data.PatternCleaner
import com.pindou.app.data.PixelGridDetector
import com.pindou.app.data.ShapeBoundary
import com.pindou.app.ui.PatternCanvas
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * 图片转图纸。一页之内完成：选图 → 拖四角裁剪 → 定尺寸/色数 → 实时预览 → 保存。
 *
 * 布局是「上方固定裁剪区 + 下方可滚动参数区」：裁剪框必须独占手势，
 * 否则拖角会被外层 ScrollView 当成翻页抢走（踩过）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportScreen(
    store: PaletteStore,
    onBack: () -> Unit,
    onSaved: (Pattern) -> Unit,
) {
    val context = LocalContext.current
    val palette = store.current

    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var crop by remember { mutableStateOf(ImageToPattern.fullRect()) }
    var longSide by remember { mutableIntStateOf(29) }
    var maxColors by remember { mutableIntStateOf(12) }
    var onlyOwned by remember { mutableStateOf(true) }
    var autoClean by remember { mutableStateOf(true) }
    var trimBg by remember { mutableStateOf(true) }

    var preview by remember { mutableStateOf<Pattern?>(null) }
    var previewStats by remember { mutableStateOf("") }
    var generating by remember { mutableStateOf(false) }

    // 原图网格识别结果；alignToSource = 按原图逐格 1:1 取色，不缩放
    var grid by remember { mutableStateOf<PixelGridDetector.Grid?>(null) }
    var alignToSource by remember { mutableStateOf(true) }
    var detecting by remember { mutableStateOf(false) }
    // grid 是针对哪个裁剪区算出来的：换了裁剪区就不能拿旧相位去吸附
    var gridCrop by remember { mutableStateOf<android.graphics.RectF?>(null) }

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri == null) return@rememberLauncherForActivityResult
        loading = true
        Thread {
            val bmp = loadBitmap(context, uri, 4096)
            bitmap = bmp
            crop = ImageToPattern.fullRect()
            loading = false
        }.start()
    }

    // 识别原图网格：像素画每个格子是多少 px。裁剪区一变就重新识别。
    LaunchedEffect(bitmap, crop) {
        val src = bitmap ?: return@LaunchedEffect
        detecting = true
        val found = withContext(Dispatchers.Default) {
            PixelGridDetector.detect(src, crop)
        }
        grid = found
        gridCrop = android.graphics.RectF(crop)
        // 识别得出来就默认按原图 1:1，识别不出来（照片）就退回长边格数
        alignToSource = found != null
        detecting = false
    }

    // 识别到网格后，先把裁剪框吸附到格子边界：这样输出一定是整格，
    // 不会出现"半格"导致描边被切、颜色被平均成灰。
    LaunchedEffect(grid, crop, alignToSource, gridCrop) {
        val g = grid ?: return@LaunchedEffect
        val src = bitmap ?: return@LaunchedEffect
        if (!alignToSource) return@LaunchedEffect
        // 只有"网格是针对当前这个裁剪区算出来的"才吸附，否则相位是错的
        val gc = gridCrop ?: return@LaunchedEffect
        if (kotlin.math.abs(gc.left - crop.left) > 1e-4f || kotlin.math.abs(gc.top - crop.top) > 1e-4f ||
            kotlin.math.abs(gc.right - crop.right) > 1e-4f || kotlin.math.abs(gc.bottom - crop.bottom) > 1e-4f
        ) return@LaunchedEffect
        val bw = src.width.toFloat()
        val bh = src.height.toFloat()
        if (bw < 1f || bh < 1f || g.cellX < 1f || g.cellY < 1f) return@LaunchedEffect

        val leftPx = crop.left * bw
        val topPx = crop.top * bh
        val rightPx = crop.right * bw
        val bottomPx = crop.bottom * bh
        // 网格相位是相对裁剪区的，换算回原图坐标里的第一条边界
        val originX = leftPx + g.offsetX
        val originY = topPx + g.offsetY

        fun snap(v: Float, origin: Float, cell: Float): Float =
            origin + Math.round((v - origin) / cell) * cell

        val nl = snap(leftPx, originX, g.cellX).coerceIn(0f, bw)
        val nr = snap(rightPx, originX, g.cellX).coerceIn(0f, bw)
        val nt = snap(topPx, originY, g.cellY).coerceIn(0f, bh)
        val nb = snap(bottomPx, originY, g.cellY).coerceIn(0f, bh)
        if (nr - nl < g.cellX * 1.5f || nb - nt < g.cellY * 1.5f) return@LaunchedEffect
        // 吸附不该把用户框的区域明显改大改小（>2 格说明网格相位不可信）
        if (kotlin.math.abs((nr - nl) - (rightPx - leftPx)) > g.cellX * 2f) return@LaunchedEffect
        if (kotlin.math.abs((nb - nt) - (bottomPx - topPx)) > g.cellY * 2f) return@LaunchedEffect

        val moved = kotlin.math.abs(nl - leftPx) + kotlin.math.abs(nr - rightPx) +
            kotlin.math.abs(nt - topPx) + kotlin.math.abs(nb - bottomPx)
        // 已经对齐了就不要动，避免来回抖
        if (moved < max(g.cellX, g.cellY) * 0.15f) return@LaunchedEffect

        crop = ImageToPattern.clampRect(
            android.graphics.RectF(nl / bw, nt / bh, nr / bw, nb / bh),
            0.02f,
        )
    }

    // 参数一变就重新生成预览；150ms 防抖，拖滑块时不会每帧都算
    LaunchedEffect(bitmap, crop, longSide, maxColors, onlyOwned, palette.id, alignToSource, grid, autoClean, trimBg) {
        val src = bitmap ?: return@LaunchedEffect
        generating = true
        delay(150)
        val allowed = if (onlyOwned) allowedIndices(store) else IntArray(palette.count) { it }
        val useGrid = if (alignToSource) grid else null
        val result = withContext(Dispatchers.Default) {
            ImageToPattern.build(src, crop, palette, allowed, longSide, maxColors, useGrid)
        }
        if (result == null) {
            preview = null
            previewStats = "当前色板可用色号不足，先到色板页打开几个色号"
        } else {
            val cleaned = if (autoClean) {
                withContext(Dispatchers.Default) {
                    PatternCleaner.despeckle(result.cells, result.width, result.height, palette)
                }
            } else null
            val baseCells = cleaned?.cells ?: result.cells
            val trimmed = if (trimBg) {
                withContext(Dispatchers.Default) {
                    ShapeBoundary.trim(baseCells, result.width, result.height, palette)
                }
            } else null
            val cells = trimmed?.cells ?: baseCells
            val now = System.currentTimeMillis()
            preview = Pattern(
                id = Pattern.newId(),
                name = "预览",
                width = result.width,
                height = result.height,
                paletteId = palette.id,
                cells = cells,
                createdAt = now,
                updatedAt = now,
            )
            val beads = cells.count { it >= 0 }
            val colors = cells.filter { it >= 0 }.distinct().size
            val suffix = if (result.alignedToSource) "（按原图 1:1）" else ""
            val cleanNote = if (cleaned != null && cleaned.changed > 0) " · 清掉 ${cleaned.changed} 点" else ""
            val trimNote = if (trimmed != null && trimmed.removed > 0) " · 去掉背景 ${trimmed.removed} 格" else ""
            previewStats =
                "${result.width} × ${result.height} 格$suffix · $beads 颗 · $colors 色$cleanNote$trimNote"
        }
        generating = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("图片转图纸", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    Text(
                        text = "返回",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable(onClick = onBack).padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                },
                actions = {
                    TextButton(onClick = {
                        picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    }) { Text("选图") }
                },
            )
        },
    ) { inner ->
        Column(modifier = Modifier.fillMaxSize().padding(inner)) {
            val src = bitmap

            // ——— 固定区：图片 / 裁剪 ———
            Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                if (src == null) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(300.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(16.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f))
                            .clickable {
                                picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            },
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = if (loading) "读取图片…" else "点击选择图片",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "照片、网图、别人的图纸截图都行。\n在图上拖一个框，或者拖四角微调。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                } else {
                    CropView(
                        bitmap = src,
                        rect = crop,
                        onRectChange = { crop = it },
                        height = 300.dp,
                        grid = if (alignToSource) grid else null,
                    )
                }
            }

            if (src != null) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = if (grid != null) {
                            "已定位网格 ${grid!!.summary}；只框图案，边会吸附到格子线"
                        } else {
                            "原图 ${src.width} × ${src.height} · 在图上拖一个框，或拖四角微调"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    TextButton(onClick = { crop = ImageToPattern.fullRect() }) { Text("重置") }
                }
            }

            // ——— 可滚动的参数区 ———
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SectionCard(title = "成品尺寸") {
                    val g = grid
                    if (g != null) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            PresetChip("按原图 ${g.cols}×${g.rows}", alignToSource) { alignToSource = true }
                            PresetChip("自由缩放", !alignToSource) { alignToSource = false }
                        }
                    }
                    if (!alignToSource || g == null) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            PresetChip("29 格", longSide == 29) { longSide = 29 }
                            PresetChip("57 格", longSide == 57) { longSide = 57 }
                            val custom = longSide != 29 && longSide != 57
                            PresetChip("自定义", custom) { if (!custom) longSide = 40 }
                            Spacer(Modifier.weight(1f))
                            Text("$longSide 格", style = MaterialTheme.typography.labelLarge)
                        }
                        Slider(
                            value = longSide.toFloat(),
                            onValueChange = { longSide = it.toInt().coerceIn(8, 100) },
                            valueRange = 8f..100f,
                        )
                    }
                    Text(
                        text = when {
                            g != null && alignToSource ->
                                "原图 ${g.summary}，逐格 1:1 取色，不合并像素"
                            g != null ->
                                "原图 ${g.summary}；现在按长边 $longSide 格缩放"
                            detecting -> "正在识别原图格子大小…"
                            else -> "没认出原图格子（大概是照片），按长边格数缩放：一块板 29，两块板 57"
                        },
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                SectionCard(title = "颜色数量") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("最多 $maxColors 色", style = MaterialTheme.typography.labelLarge)
                        Spacer(Modifier.weight(1f))
                        Text(
                            text = "色越少越好拼",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Slider(
                        value = maxColors.toFloat(),
                        onValueChange = { maxColors = it.toInt().coerceIn(2, 40) },
                        valueRange = 2f..40f,
                    )
                }

                SectionCard(title = "只用我有的色号") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = "已启用 ${store.enabledCount} / ${palette.count} 色",
                                style = MaterialTheme.typography.labelLarge,
                            )
                            Text(
                                text = "关掉就往全色板里映射，可能出图给出买不到的色号",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = onlyOwned, onCheckedChange = { onlyOwned = it })
                    }
                }

                SectionCard(title = "细节清理") {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动识别图形边界", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "按边的背景色找出图形轮廓：外面的空白不生成豆子，被围住的内部一格不少；边界一圈的混色会并回主体色，少掉一圈脏描边",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = trimBg, onCheckedChange = { trimBg = it })
                    }
                    Spacer(Modifier.height(12.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("自动去杂点", style = MaterialTheme.typography.labelLarge)
                            Text(
                                text = "把孤立的混色并到周围主色；黑眼珠、线条端点这类对比色孤点会保留",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = autoClean, onCheckedChange = { autoClean = it })
                    }
                }

                SectionCard(title = "预览") {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(280.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surface),
                        contentAlignment = Alignment.Center,
                    ) {
                        val p = preview
                        if (p == null) {
                            Text(
                                text = if (generating) "生成中…" else "选一张图看看效果",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            PatternCanvas(
                                pattern = p,
                                palette = palette,
                                modifier = Modifier.fillMaxSize().padding(10.dp),
                                showGrid = p.width <= 40,
                            )
                        }
                    }
                    Text(
                        text = previewStats,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                Button(
                    onClick = {
                        val p = preview ?: return@Button
                        val stamp = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date())
                        val now = System.currentTimeMillis()
                        val saved = Pattern(
                            id = Pattern.newId(),
                            name = "图片 · $stamp",
                            width = p.width,
                            height = p.height,
                            paletteId = palette.id,
                            cells = p.cells.copyOf(),
                            createdAt = now,
                            updatedAt = now,
                        )
                        PatternStore.save(context, saved)
                        onSaved(saved)
                    },
                    enabled = preview != null && !generating,
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                ) {
                    Text("保存到图纸库", style = MaterialTheme.typography.titleSmall)
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            content()
        }
    }
}

@Composable
private fun PresetChip(label: String, active: Boolean, onClick: () -> Unit) {
    Surface(
        shape = RoundedCornerShape(50),
        color = if (active) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
        modifier = Modifier.clickable(onClick = onClick),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            color = if (active) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 18.dp, vertical = 8.dp),
        )
    }
}

/** 启用中的色号在色板里的下标；映射时只用这些。 */
private fun allowedIndices(store: PaletteStore): IntArray {
    val palette: Palette = store.current
    val out = ArrayList<Int>(palette.count)
    for (i in 0 until palette.count) {
        val c = palette.get(i) ?: continue
        if (store.isEnabled(c.code)) out.add(i)
    }
    return out.toIntArray()
}

/** 按长边上限抽样解码，避免手机大图 OOM。 */
private fun loadBitmap(context: Context, uri: Uri, maxDim: Int): Bitmap? = runCatching {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
    var sample = 1
    while (max(bounds.outWidth, bounds.outHeight) / sample > maxDim) sample *= 2
    val opts = BitmapFactory.Options().apply { inJustDecodeBounds = false; inSampleSize = sample }
    context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
}.getOrNull()
