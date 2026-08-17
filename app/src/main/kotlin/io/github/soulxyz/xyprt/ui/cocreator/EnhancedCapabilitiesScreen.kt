package io.github.soulxyz.xyprt.ui.cocreator

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
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
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.BuildConfig
import io.github.soulxyz.xyprt.data.remote.EnhancedCapability
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EnhancedCapabilitiesScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val container = remember { (context.applicationContext as App).container }
    val repo = container.enhancedModels
    val coCreatorRepo = container.coCreator
    val state by repo.catalog.collectAsState()
    val coCreator by coCreatorRepo.state.collectAsState()
    val scope = rememberCoroutineScope()
    var busyId by remember { mutableStateOf<String?>(null) }
    val runtimeAvailable = BuildConfig.ENHANCED_SCANNER_AVAILABLE

    LaunchedEffect(runtimeAvailable) {
        coCreatorRepo.refresh(silent = true)
        if (runtimeAvailable) repo.refreshCatalog(silent = false)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("增强识别") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    if (runtimeAvailable) {
                        TextButton(onClick = { scope.launch { repo.refreshCatalog(false) } }) { Text("刷新") }
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text("日常扫描", fontWeight = FontWeight.SemiBold)
                        Text("自动找边、透视校正和边缘清理，适合日常纸张扫描。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            if (!runtimeAvailable) {
                item {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                        Column(Modifier.padding(17.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("增强识别", fontWeight = FontWeight.SemiBold)
                            Text(
                                "复杂背景、浅色纸边或拍摄角度较大时，识别会更稳定。",
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            Text(
                                if (coCreator.active) "需要更新应用后使用。" else "目前在共创计划中小范围开放。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            if (runtimeAvailable && state.refreshing) {
                item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) { CircularProgressIndicator() } }
            }
            if (runtimeAvailable) {
                if (state.lastError != null) {
                    item { Text("暂时无法获取增强能力，请稍后重试。", color = MaterialTheme.colorScheme.error) }
                }
                items(state.items, key = { it.id }) { item ->
                    CapabilityCard(
                        item = item,
                        busy = busyId == item.id,
                        onDownload = {
                            scope.launch {
                                busyId = item.id
                                val result = repo.download(item)
                                busyId = null
                                Toast.makeText(
                                    context,
                                    if (result.isSuccess) "增强识别已启用" else result.exceptionOrNull()?.message ?: "下载失败",
                                    Toast.LENGTH_LONG,
                                ).show()
                            }
                        },
                        onRemove = {
                            scope.launch {
                                repo.remove(item)
                                Toast.makeText(context, "已移除", Toast.LENGTH_SHORT).show()
                            }
                        },
                    )
                }
                if (!state.refreshing && state.items.isEmpty() && state.lastError == null) {
                    item { Text("当前没有可下载的增强能力。", style = MaterialTheme.typography.bodyMedium) }
                }
            }
            item { Spacer(Modifier.size(20.dp)) }
        }
    }
}

@Composable
private fun CapabilityCard(
    item: EnhancedCapability,
    busy: Boolean,
    onDownload: () -> Unit,
    onRemove: () -> Unit,
) {
    Card {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(item.name, fontWeight = FontWeight.SemiBold)
                    Text(
                        "v${item.version}${item.releaseLabel?.let { " · $it" }.orEmpty()}",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                }
                if (busy) CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            }
            if (item.description.isNotBlank()) Text(item.description, style = MaterialTheme.typography.bodyMedium)
            item.publishedAt?.let {
                Text(
                    "发布：${DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(it * 1000))}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item.fileSize?.let {
                Text("下载约 ${formatBytes(it)}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            when {
                item.locked -> Text("当前设备暂未开放。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                item.installed -> Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("已启用", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onRemove) { Text("移除") }
                }
                item.downloadable -> Button(onClick = onDownload, enabled = !busy) { Text(if (busy) "准备中…" else "启用") }
                else -> OutlinedButton(onClick = {}, enabled = false) { Text("暂不可下载") }
            }
        }
    }
}

private fun formatBytes(value: Long): String = when {
    value >= 1024 * 1024 -> "%.1f MB".format(value / 1024.0 / 1024)
    value >= 1024 -> "%.0f KB".format(value / 1024.0)
    else -> "$value B"
}
