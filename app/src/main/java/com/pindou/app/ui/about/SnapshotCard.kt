package com.pindou.app.ui.about

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.pindou.app.data.SnapshotPolicy
import com.pindou.app.data.SnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 本机快照列表。恢复走整库覆盖，但会先自动给当前数据留一份安全快照。
 * 快照只在本机，导出备份里不含快照。
 */
@Composable
fun SnapshotCard(
    showMessage: (String) -> Unit,
    onRestored: () -> Unit,
    refreshKey: Int = 0,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var entries by remember { mutableStateOf<List<SnapshotStore.Entry>>(emptyList()) }
    var busy by remember { mutableStateOf(false) }
    var pending by remember { mutableStateOf<SnapshotStore.Entry?>(null) }

    LaunchedEffect(refreshKey) {
        entries = withContext(Dispatchers.IO) { SnapshotStore.list(context) }
    }

    fun createNow() {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { SnapshotStore.create(context) }
            }
            busy = false
            result.onSuccess { created ->
                entries = withContext(Dispatchers.IO) { SnapshotStore.list(context) }
                showMessage("已创建快照（${created.patternCount} 张图纸）")
            }.onFailure {
                showMessage("创建快照失败：${it.message ?: "未知错误"}")
            }
        }
    }

    fun restore(entry: SnapshotStore.Entry) {
        if (busy) return
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { SnapshotStore.restore(context, entry.file) }
            }
            busy = false
            pending = null
            result.onSuccess { outcome ->
                entries = withContext(Dispatchers.IO) { SnapshotStore.list(context) }
                onRestored()
                showMessage("已恢复 ${outcome.added} 张图纸（${formatSnapshotTime(entry.createdAt)} 的快照）")
            }.onFailure {
                showMessage("恢复失败：${it.message ?: "未知错误"}")
            }
        }
    }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("本地快照", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                    Text(
                        text = "${entries.size} / ${SnapshotPolicy.MAX_SNAPSHOTS} 份 · ${formatSize(entries.sumOf { it.sizeBytes })}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                OutlinedButton(onClick = { createNow() }, enabled = !busy) { Text("立即快照") }
            }
            Text(
                text = "快照只在本机，用于误删或误恢复后的回退。打开 App 时若数据有变化且距上一份超过 6 小时，会自动留一份；删除图纸、覆盖恢复前也会先留一份。最多保留 5 份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "换机、卸载或清理数据前，仍要另外导出备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (entries.isEmpty()) {
                Text(
                    text = "还没有快照。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                entries.forEach { entry ->
                    SnapshotRow(
                        entry = entry,
                        enabled = !busy,
                        onRestore = { pending = entry },
                    )
                }
            }
            if (busy) {
                Text(
                    text = "正在处理，请勿退出…",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }

    val target = pending
    if (target != null) {
        AlertDialog(
            onDismissRequest = { if (!busy) pending = null },
            title = { Text("恢复快照") },
            text = {
                Text(
                    "将用 ${formatSnapshotTime(target.createdAt)} 的快照覆盖当前所有图纸和设置" +
                        "（${target.patternCount} 张图纸）。恢复前会自动把当前数据再留成一份快照，仍可退回。",
                )
            },
            confirmButton = {
                TextButton(enabled = !busy, onClick = { restore(target) }) { Text("恢复") }
            },
            dismissButton = {
                TextButton(enabled = !busy, onClick = { pending = null }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun SnapshotRow(
    entry: SnapshotStore.Entry,
    enabled: Boolean,
    onRestore: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                text = formatSnapshotTime(entry.createdAt),
                style = MaterialTheme.typography.labelLarge,
            )
            Text(
                text = "${entry.patternCount} 张图纸 · ${formatSize(entry.sizeBytes)} · App ${entry.appVersion}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        TextButton(enabled = enabled, onClick = onRestore) { Text("恢复") }
    }
}

private fun formatSnapshotTime(timestamp: Long): String =
    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))

private fun formatSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L -> String.format(Locale.US, "%.1f MB", bytes / 1024.0 / 1024.0)
    bytes >= 1024L -> "${bytes / 1024L} KB"
    else -> "$bytes B"
}
