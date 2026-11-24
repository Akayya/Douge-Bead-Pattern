package com.pindou.app.ui.about

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 关于页。里面的"赞助支持"是纯自愿的：不放广告、不锁功能、不弹窗催促，
 * 只有本页一个入口，二维码是写死在 assets/support/ 下的静态图，不联网、不申请权限。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AboutScreen(
    onBack: () -> Unit,
    patternCount: Int = 0,
    onBackupImported: () -> Unit = {},
) {
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val version = remember {
        runCatching {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName
        }.getOrNull() ?: "0.1.0"
    }
    var dataVersion by remember { mutableIntStateOf(0) }
    val onDataRestored = {
        dataVersion += 1
        onBackupImported()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("关于", style = MaterialTheme.typography.titleLarge) },
                navigationIcon = {
                    Text(
                        text = "返回",
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier
                            .clickable(onClick = onBack)
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Spacer(Modifier.height(4.dp))
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("豆格", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                Text(
                    text = "版本 $version · 本地图纸，全离线",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            BackupCard(
                patternCount = patternCount,
                onImported = onDataRestored,
                showMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
            )

            SnapshotCard(
                refreshKey = dataVersion,
                showMessage = { message -> scope.launch { snackbar.showSnackbar(message) } },
                onRestored = onDataRestored,
            )

            SupportCard()

            InfoCard(
                title = "色板数据来源",
                lines = listOf(
                    "MARD 221 / 291 色号与色值来自开源数据集 HansBug/pindou-color-data（MIT 许可）。",
                    "屏幕色值仅是参考，和实物豆子有材质、批次差异；对不上时可在色板页长按色块校准。",
                ),
            )

            InfoCard(
                title = "开源许可",
                lines = listOf(
                    "界面：Jetpack Compose（Apache-2.0）",
                    "色板数据：pindou-color-data（MIT，Copyright (c) 2026 HansBug）",
                ),
            )

            Text(
                text = "本 App 没有账号、没有社区、不申请网络权限，所有图纸只存在你自己的手机里。",
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SupportCard() {
    val context = LocalContext.current
    val channels = remember {
        listOf(
            SupportChannel("支付宝", "support/alipay.png"),
            SupportChannel("微信", "support/wechat.png"),
        )
    }
    // 默认选中第一个真的放了码的渠道；都没放就回到第一个占位
    val initial = remember {
        channels.indexOfFirst { loadSupportImage(context, it.assetPath) != null }.coerceAtLeast(0)
    }
    var selected by remember { mutableIntStateOf(initial) }
    val channel = channels[selected]
    val qr = remember(selected) { loadSupportImage(context, channel.assetPath) }

    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("赞助支持", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = "这个 App 是我自己写的小工具，一直免费、无广告、功能不锁。\n如果它帮你省了时间，可以扫下面的码请我喝杯奶茶——完全自愿，不赞助也一点不影响使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                channels.forEachIndexed { index, item ->
                    val active = index == selected
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = if (active) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.surface,
                        modifier = Modifier.clickable { selected = index },
                    ) {
                        Text(
                            text = item.label,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (active) MaterialTheme.colorScheme.onPrimary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                        )
                    }
                }
            }

            Box(
                modifier = Modifier
                    .size(220.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface),
                contentAlignment = Alignment.Center,
            ) {
                if (qr != null) {
                    Image(
                        bitmap = qr,
                        contentDescription = "${channel.label}赞助码",
                        modifier = Modifier.fillMaxSize().padding(8.dp),
                    )
                } else {
                    Text(
                        text = "作者还没放收款码\n放好后这里会显示",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp),
                    )
                }
            }
            Text(
                text = "扫码即可，金额随意",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private data class SupportChannel(val label: String, val assetPath: String)

/** 读取 assets 下的赞助码；没有就返回 null，由界面显示占位说明。 */
private fun loadSupportImage(context: android.content.Context, path: String) =
    runCatching {
        context.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() }
    }.getOrNull()

@Composable
private fun InfoCard(title: String, lines: List<String>) {
    Surface(
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            lines.forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
