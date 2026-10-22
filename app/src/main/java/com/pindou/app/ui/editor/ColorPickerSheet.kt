package com.pindou.app.ui.editor

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items as rowItems
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.pindou.app.data.BeadColor
import com.pindou.app.data.PaletteGroups

/**
 * 选色面板：按色系分组（A/B/C…）+ 色号网格。
 * 编辑器画笔、替换色号都用它，避免把两百多个色号挤成一条长条。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ColorPickerSheet(
    title: String,
    colors: List<BeadColor>,
    onDismiss: () -> Unit,
    onPick: (BeadColor) -> Unit,
) {
    val groups = remember(colors) { listOf("全部") + colors.map { it.group }.distinct().sorted() }
    var filter by remember(colors) { mutableStateOf("全部") }
    val shown = remember(colors, filter) {
        if (filter == "全部") colors else colors.filter { it.group == filter }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(10.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                rowItems(groups, key = { it }) { g ->
                    FilterChip(
                        selected = filter == g,
                        onClick = { filter = g },
                        label = {
                            Text(if (g == "全部") "全部 ${colors.size}" else PaletteGroups.label(g))
                        },
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 54.dp),
                modifier = Modifier.fillMaxWidth().heightIn(max = 400.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                gridItems(shown, key = { it.code }) { c ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(Color(c.argb))
                                .border(1.dp, Color(0x33000000), RoundedCornerShape(8.dp))
                                .clickable { onPick(c) },
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(c.code, style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
