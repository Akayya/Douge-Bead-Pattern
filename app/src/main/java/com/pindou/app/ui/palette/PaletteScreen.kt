package com.pindou.app.ui.palette

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.pindou.app.data.BeadColor
import com.pindou.app.data.PaletteId
import com.pindou.app.data.PaletteGroups
import com.pindou.app.data.PaletteStore

/**
 * 色板页：切换 MARD 221 / 291，按色系筛，点色块切换「我有 / 没有」。
 * 没有的色号会在转图纸时被排除，避免出图给一堆买不到的色号。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PaletteScreen(store: PaletteStore, onBack: () -> Unit) {
    val palette = store.current
    var group by remember(palette.id) { mutableStateOf<String?>(null) }
    val shown = if (group == null) palette.colors else palette.colors.filter { it.group == group }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("色板", style = MaterialTheme.typography.titleLarge)
                        Text(
                            "${palette.title} · 已有 ${store.enabledCount} / ${palette.count} 色",
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
    ) { inner ->
        Column(Modifier.fillMaxSize().padding(inner)) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                FilterChip(
                    selected = palette.id == PaletteId.MARD_221,
                    onClick = { store.switchPalette(PaletteId.MARD_221) },
                    label = { Text("MARD 221") },
                )
                FilterChip(
                    selected = palette.id == PaletteId.MARD_291,
                    onClick = { store.switchPalette(PaletteId.MARD_291) },
                    label = { Text("MARD 291") },
                )
            }

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
                        onToggle = { store.toggle(color.code) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ColorCell(color: BeadColor, enabled: Boolean, onToggle: () -> Unit) {
    val outline = MaterialTheme.colorScheme.outline
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable(onClick = onToggle),
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
        )
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
    }
}
