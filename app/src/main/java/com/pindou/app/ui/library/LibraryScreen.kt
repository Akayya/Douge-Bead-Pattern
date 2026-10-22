package com.pindou.app.ui.library

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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.pindou.app.data.Palette
import com.pindou.app.data.Pattern
import com.pindou.app.ui.PatternThumb
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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
    onRename: (Pattern, String) -> Unit = { _, _ -> },
    onDuplicate: (Pattern) -> Unit = {},
    onDelete: (Pattern) -> Unit = {},
) {
    var renameTarget by remember { mutableStateOf<Pattern?>(null) }
    var deleteTarget by remember { mutableStateOf<Pattern?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("豆格", style = MaterialTheme.typography.titleLarge)
                        Text(
                            text = "色板 $paletteTitle · ${patterns.size} 张图纸",
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
            FloatingActionButton(onClick = onNewPattern) {
                Text("＋", style = MaterialTheme.typography.headlineSmall)
            }
        },
    ) { inner ->
        if (patterns.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(inner).padding(32.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("还没有图纸", style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "点右下角「＋」，把图片转成图纸。\n也可以先到「色板」里勾掉你手上没有的色号，出图就只用你有的豆子。",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(inner),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(patterns, key = { it.id }) { pattern ->
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
            text = { Text("「${target.name}」会从这座手机里删掉，不能恢复。") },
            confirmButton = {
                TextButton(onClick = {
                    onDelete(target)
                    deleteTarget = null
                }) { Text("删除") }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text("取消") } },
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
