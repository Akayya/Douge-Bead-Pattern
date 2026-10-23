package com.pindou.app.ui.about

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import com.pindou.app.data.BackupManager
import com.pindou.app.data.SnapshotStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 整库备份：导出走系统「保存到文件」，导入走系统文件选择器，不需要存储权限。
 * 合并导入不删任何现有数据；覆盖恢复会二次确认。
 */
@Composable
fun BackupCard(
    patternCount: Int,
    onImported: () -> Unit,
    showMessage: (String) -> Unit,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var pendingUri by remember { mutableStateOf<Uri?>(null) }
    var pendingInfo by remember { mutableStateOf<BackupManager.Info?>(null) }
    var overwriteMode by remember { mutableStateOf(false) }
    var confirmOverwrite by remember { mutableStateOf(false) }

    fun doImport(replace: Boolean) {
        val uri = pendingUri ?: return
        scope.launch {
            busy = true
            if (replace) {
                val safety = withContext(Dispatchers.IO) {
                    runCatching { SnapshotStore.create(context) }
                }
                if (safety.isFailure) {
                    busy = false
                    pendingUri = null
                    pendingInfo = null
                    confirmOverwrite = false
                    showMessage(
                        "恢复前快照失败，已取消恢复：${safety.exceptionOrNull()?.message ?: "未知错误"}",
                    )
                    return@launch
                }
            }
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupManager.import(context, uri, replace) }
            }
            busy = false
            pendingUri = null
            pendingInfo = null
            confirmOverwrite = false
            result.onSuccess { outcome ->
                onImported()
                showMessage(
                    when {
                        replace -> "已恢复 ${outcome.added} 张图纸"
                        outcome.copies > 0 -> "已合并 ${outcome.added} 张图纸，${outcome.copies} 张另存为副本"
                        else -> "已合并 ${outcome.added} 张图纸"
                    },
                )
            }.onFailure { showMessage("导入失败：${it.message ?: "未知错误"}") }
        }
    }

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip"),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupManager.export(context, uri) }
            }
            busy = false
            result.onSuccess { showMessage("已导出 ${it.patternCount} 张图纸") }
                .onFailure { showMessage("导出失败：${it.message ?: "未知错误"}") }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument(),
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            busy = true
            val result = withContext(Dispatchers.IO) {
                runCatching { BackupManager.inspect(context, uri) }
            }
            busy = false
            result.onSuccess { info ->
                pendingUri = uri
                pendingInfo = info
                overwriteMode = false
            }.onFailure { showMessage("读取失败：${it.message ?: "不是有效的备份"}") }
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
            Text("数据备份", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                text = "所有图纸和设置只存在这台手机里。换机、刷机或清理数据前，先导出一份备份。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "备份包含图纸、施工进度、色板开关与校准、豆仓库存与扣减记录、成本设置；缩略图会在恢复后自动重建。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = "当前 $patternCount 张图纸",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = { exportLauncher.launch(BackupManager.defaultFileName()) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("导出备份") }
                OutlinedButton(
                    onClick = { importLauncher.launch(arrayOf("*/*")) },
                    enabled = !busy,
                    modifier = Modifier.weight(1f),
                ) { Text("导入备份") }
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

    val info = pendingInfo
    if (info != null && pendingUri != null) {
        AlertDialog(
            onDismissRequest = {
                if (!busy) {
                    pendingInfo = null
                    pendingUri = null
                }
            },
            title = { Text("导入备份") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        text = buildString {
                            append("备份包含 ${info.patternCount} 张图纸")
                            if (info.paletteCount > 0) append("、${info.paletteCount} 个自定义色板")
                            append("，导出于 ${formatTime(info.createdAt)}，App ${info.appVersion}。")
                        },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    ModeOption(
                        title = "合并导入",
                        desc = "保留现有图纸；编号重复的备份图纸另存为副本；库存和校准只补本地没记过的项。",
                        selected = !overwriteMode,
                        onClick = { overwriteMode = false },
                    )
                    ModeOption(
                        title = "覆盖恢复",
                        desc = "删除当前全部图纸、自定义色板和设置，用备份替换；不可撤销。",
                        selected = overwriteMode,
                        onClick = { overwriteMode = true },
                    )
                }
            },
            confirmButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        if (overwriteMode) confirmOverwrite = true else doImport(false)
                    },
                ) {
                    Text(
                        text = if (overwriteMode) "覆盖恢复" else "合并导入",
                        color = if (overwriteMode) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.primary,
                    )
                }
            },
            dismissButton = {
                TextButton(
                    enabled = !busy,
                    onClick = {
                        pendingInfo = null
                        pendingUri = null
                    },
                ) { Text("取消") }
            },
        )
    }

    if (confirmOverwrite) {
        AlertDialog(
            onDismissRequest = { confirmOverwrite = false },
            title = { Text("覆盖恢复？") },
            text = {
                Text(
                    "当前 $patternCount 张图纸、色板开关与校准、库存、成本设置都会被备份替换。" +
                        "此操作不可撤销，建议先导出当前数据。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        confirmOverwrite = false
                        doImport(true)
                    },
                ) { Text("确定覆盖", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { confirmOverwrite = false }) { Text("取消") }
            },
        )
    }
}

@Composable
private fun ModeOption(
    title: String,
    desc: String,
    selected: Boolean,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(top = 4.dp),
        verticalAlignment = Alignment.Top,
    ) {
        RadioButton(selected = selected, onClick = onClick)
        Column(Modifier.padding(top = 10.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
            )
            Text(
                text = desc,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun formatTime(timestamp: Long): String =
    if (timestamp <= 0) "未知时间"
    else SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(timestamp))
