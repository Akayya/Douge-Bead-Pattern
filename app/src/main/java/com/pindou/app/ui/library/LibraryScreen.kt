package com.pindou.app.ui.library

import android.content.Context
import androidx.compose.foundation.ExperimentalFoundationApi
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.pindou.app.data.Palette
import com.pindou.app.data.Pattern
import com.pindou.app.ui.PatternThumb
import java.text.Collator
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class LibrarySort(val label: String) {
    Updated("最近更新"),
    Created("最近创建"),
    Name("名称"),
    Beads("豆数"),
}

private const val PREFS_NAME = "library_prefs"
private const val KEY_SORT = "sort"

private fun readSort(context: Context): LibrarySort {
    val saved = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .getString(KEY_SORT, null)
    return LibrarySort.entries.firstOrNull { it.name == saved } ?: LibrarySort.Updated
}

private fun saveSort(context: Context, sort: LibrarySort) {
    context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        .edit().putString(KEY_SORT, sort.name).apply()
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    patterns: List<Pattern>,
    palette: Palette,
    paletteTitle: String,
    onOpenPattern: (Pattern) -> Unit,
    onOpenPalette: () -> Unit,
    onOpenAbout: () -> Unit,
    onNewPattern: () -> Unit,
    onNewBlank: (Int, Int) -> Unit = { _, _ -> },
    onRename: (Pattern, String) -> Unit = { _, _ -> },
    onDuplicate: (Pattern) -> Unit = {},
    onDelete: (Pattern) -> Unit = {},
    onRestore: (Pattern) -> Unit = {},
) {
    var renameTarget by remember { mutableStateOf<Pattern?>(null) }
    var deleteTarget by remember { mutableStateOf<Pattern?>(null) }
    var showBlank by remember { mutableStateOf(false) }
    var blankW by remember { mutableStateOf("29") }
    var blankH by remember { mutableStateOf("29") }
    var query by remember { mutableStateOf("") }
    val context = LocalContext.current
    var sort by remember { mutableStateOf(readSort(context)) }
    var showSort by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val collator = remember { Collator.getInstance(Locale.SIMPLIFIED_CHINESE) }
    val listState = rememberLazyListState()

    LaunchedEffect(sort, query) {
        listState.scrollToItem(0)
    }

    // patterns 是 SnapshotStateList，直接在组合里算，增删改名后才会跟着刷新
    val trimmedQuery = query.trim()
    val visible = patterns
        .filter { trimmedQuery.isEmpty() || it.name.contains(trimmedQuery, ignoreCase = true) }
        .let { list ->
            when (sort) {
                LibrarySort.Updated -> list.sortedByDescending { it.updatedAt }
                LibrarySort.Created -> list.sortedByDescending { it.createdAt }
                LibrarySort.Name -> list.sortedWith(compareBy(collator) { it.name })
                LibrarySort.Beads -> list.sortedByDescending { it.beadCount }
            }
        }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("豆格", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = if (trimmedQuery.isEmpty()) {
                                "色板 $paletteTitle · ${patterns.size} 张图纸"
                            } else {
                                "色板 $paletteTitle · 找到 ${visible.size} / ${patterns.size} 张"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    Text(
                        text = "关于",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier
                            .clickable(onClick = onOpenAbout)
                            .padding(horizontal = 8.dp, vertical = 8.dp),
                    )
                    Text(
                        text = "色板",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onOpenPalette)
                            .padding(start = 8.dp, end = 16.dp, top = 8.dp, bottom = 8.dp),
                    )
                },
            )
        },
        floatingActionButton = {
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                ExtendedFloatingActionButton(
                    onClick = { showBlank = true },
                    containerColor = MaterialTheme.colorScheme.secondaryContainer,
                    text = { Text("空白画布") },
                    icon = {},
                )
                ExtendedFloatingActionButton(
                    onClick = onNewPattern,
                    text = { Text("图片转图纸") },
                    icon = {},
                )
            }
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            if (patterns.isNotEmpty()) {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, top = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        modifier = Modifier.weight(1f),
                        placeholder = { Text("搜索名称") },
                        singleLine = true,
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                Box(
                                    Modifier.size(36.dp).clickable { query = "" },
                                    contentAlignment = Alignment.Center,
                                ) {
                                    Text(
                                        text = "×",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        },
                    )
                    Box {
                        OutlinedButton(
                            onClick = { showSort = true },
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text("${sort.label} ▼", style = MaterialTheme.typography.labelLarge)
                        }
                        DropdownMenu(
                            expanded = showSort,
                            onDismissRequest = { showSort = false },
                        ) {
                            LibrarySort.entries.forEach { option ->
                                DropdownMenuItem(
                                    text = {
                                        Text(
                                            text = if (option == sort) "✓ ${option.label}" else option.label,
                                            color = if (option == sort) {
                                                MaterialTheme.colorScheme.primary
                                            } else {
                                                MaterialTheme.colorScheme.onSurface
                                            },
                                        )
                                    },
                                    onClick = {
                                        sort = option
                                        saveSort(context, option)
                                        showSort = false
                                    },
                                )
                            }
                        }
                    }
                }
            }

            when {
                patterns.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text("还没有图纸", style = MaterialTheme.typography.titleMedium)
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "点右下角「图片转图纸」把照片转成图纸，或者用「空白画布」从零开始画。\n也可以先到「色板」里勾掉你手上没有的色号，出图就只用你有的豆子。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }
                visible.isEmpty() -> {
                    Box(
                        modifier = Modifier.fillMaxWidth().weight(1f).padding(32.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Text(
                                text = "没有找到「$trimmedQuery」",
                                style = MaterialTheme.typography.titleMedium,
                            )
                            Spacer(Modifier.height(8.dp))
                            Text(
                                text = "换个关键词试试，或者清空搜索。",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(4.dp))
                            TextButton(onClick = { query = "" }) { Text("清空搜索") }
                        }
                    }
                }
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxWidth().weight(1f),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        items(visible, key = { it.id }) { pattern ->
                            PatternRow(
                                pattern = pattern,
                                palette = palette,
                                onClick = { onOpenPattern(pattern) },
                                onRename = { renameTarget = pattern },
                                onDuplicate = { onDuplicate(pattern) },
                                onDelete = { deleteTarget = pattern },
                            )
                        }
                    }
                }
            }
        }
    }

    renameTarget?.let { target ->
        var text by remember(target.id) { mutableStateOf(target.name) }
        AlertDialog(
            onDismissRequest = { renameTarget = null },
            title = { Text("重命名") },
            text = {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    label = { Text("图纸名称") },
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    val name = text.trim()
                    if (name.isNotEmpty()) onRename(target, name)
                    renameTarget = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renameTarget = null }) { Text("取消") } },
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("删除图纸") },
            text = { Text("「${target.name}」会从这座手机里删掉。删错了可以点下面的「撤销」。") },
            confirmButton = {
                TextButton(onClick = {
                    val deleted = target
                    onDelete(deleted)
                    deleteTarget = null
                    scope.launch {
                        val result = snackbar.showSnackbar(
                            message = "已删除「${deleted.name}」",
                            actionLabel = "撤销",
                            withDismissAction = true,
                            duration = SnackbarDuration.Long,
                        )
                        if (result == SnackbarResult.ActionPerformed) onRestore(deleted)
                    }
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
        )
    }

    if (showBlank) {
        val w = blankW.toIntOrNull() ?: 0
        val h = blankH.toIntOrNull() ?: 0
        val valid = w in 4..200 && h in 4..200
        AlertDialog(
            onDismissRequest = { showBlank = false },
            title = { Text("新建空白画布") },
            text = {
                Column {
                    Text(
                        text = "按格子数填。29 是一块标准拼豆板，57 是两块拼起来。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(12.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(
                            value = blankW,
                            onValueChange = { blankW = it.filter { c -> c.isDigit() }.take(3) },
                            label = { Text("宽（格）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                        OutlinedTextField(
                            value = blankH,
                            onValueChange = { blankH = it.filter { c -> c.isDigit() }.take(3) },
                            label = { Text("高（格）") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(29 to 29, 57 to 57, 29 to 57).forEach { (pw, ph) ->
                            OutlinedButton(
                                onClick = { blankW = "$pw"; blankH = "$ph" },
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                            ) {
                                Text("$pw×$ph", maxLines = 1)
                            }
                        }
                    }
                    if (!valid) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "宽高要在 4 到 200 之间",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        if (valid) {
                            showBlank = false
                            onNewBlank(w, h)
                        }
                    },
                    enabled = valid,
                ) { Text("创建") }
            },
            dismissButton = { TextButton(onClick = { showBlank = false }) { Text("取消") } },
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun PatternRow(
    pattern: Pattern,
    palette: Palette,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
) {
    val stamp = remember(pattern.updatedAt) {
        SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()).format(Date(pattern.updatedAt))
    }
    var menu by remember { mutableStateOf(false) }
    Box {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .combinedClickable(onClick = onClick, onLongClick = { menu = true }),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                PatternThumb(pattern = pattern, palette = palette, size = 64.dp)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(pattern.name, style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = "${pattern.width} × ${pattern.height} · ${pattern.beadCount} 颗 · ${pattern.usage(palette).size} 色",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = "更新 $stamp",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (pattern.doneCount > 0) {
                        Spacer(Modifier.height(8.dp))
                        val pct = (pattern.progress * 100).toInt()
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            LinearProgressIndicator(
                                progress = { pattern.progress },
                                modifier = Modifier.weight(1f).height(6.dp),
                                strokeCap = androidx.compose.ui.graphics.StrokeCap.Round,
                                color = if (pct >= 100) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.primary
                                },
                                trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                text = when {
                                    pct >= 100 -> "已拼完 ${pattern.doneCount}/${pattern.beadCount}"
                                    pct < 1 -> "施工 ${pattern.doneCount}/${pattern.beadCount}"
                                    else -> "施工 $pct%  ${pattern.doneCount}/${pattern.beadCount}"
                                },
                                style = MaterialTheme.typography.labelSmall,
                                color = if (pct >= 100) {
                                    MaterialTheme.colorScheme.tertiary
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                            )
                        }
                    }
                }
            }
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            DropdownMenuItem(text = { Text("重命名") }, onClick = { menu = false; onRename() })
            DropdownMenuItem(text = { Text("复制一份") }, onClick = { menu = false; onDuplicate() })
            DropdownMenuItem(
                text = { Text("删除", color = MaterialTheme.colorScheme.error) },
                onClick = { menu = false; onDelete() },
            )
        }
    }
}
