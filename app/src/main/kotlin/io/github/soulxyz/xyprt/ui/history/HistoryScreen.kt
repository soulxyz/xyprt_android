package io.github.soulxyz.xyprt.ui.history

import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.data.HistoryRepository
import io.github.soulxyz.xyprt.data.PrintHistoryEntry
import io.github.soulxyz.xyprt.printer.MonoImage
import io.github.soulxyz.xyprt.render.LabelRenderer
import io.github.soulxyz.xyprt.render.MonoConverter
import io.github.soulxyz.xyprt.ui.components.rememberBlePermissionRunner
import io.github.soulxyz.xyprt.ui.print.PrintSheet
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onEditQuick: (Long) -> Unit = {},
    onOpenTemplate: (String) -> Unit = {},
    onOpenPrinterSettings: () -> Unit = {},
    vm: HistoryViewModel = viewModel(),
) {
    val entries by vm.entries.collectAsState()
    var reprint by remember { mutableStateOf<Pair<MonoImage, PrintHistoryEntry>?>(null) }
    val withBlePermissions = rememberBlePermissionRunner()
    var showClearAllConfirm by remember { mutableStateOf(false) }
    var pendingDeleteId by remember { mutableStateOf<Long?>(null) }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = { Text("打印历史", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
                actions = {
                    if (entries.isNotEmpty()) {
                        TextButton(onClick = { showClearAllConfirm = true }) { Text("清空") }
                    }
                },
            )
        },
    ) { padding ->
        if (entries.isEmpty()) {
            Box(
                Modifier.padding(padding).fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("还没有打印记录", style = MaterialTheme.typography.titleMedium)
                    Text("打印过的内容都会保存在这里。", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            return@Scaffold
        }

        Box(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        LazyColumn(
            Modifier.widthIn(max = 900.dp).fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            items(entries, key = { it.id }) { entry ->
                HistoryCard(
                    entry = entry,
                    onReprint = {
                        withBlePermissions {
                            val exact = HistoryRepository.decodeRaster(entry)
                            reprint = (exact ?: LabelRenderer.renderMono(entry.spec, entry.elements)) to entry
                        }
                    },
                    onDelete = { pendingDeleteId = entry.id },
                    onEditQuick = if (entry.rasterBase64 != null) ({ onEditQuick(entry.id) }) else null,
                    onConvertLayout = if (entry.rasterBase64 != null) ({ vm.convertQuickToTemplate(entry.id) { id -> if (id != null) onOpenTemplate(id) } }) else null,
                )
            }
        }
        }
    }

    // 清空所有历史记录确认对话框
    if (showClearAllConfirm) {
        AlertDialog(
            onDismissRequest = { showClearAllConfirm = false },
            title = { Text("清空打印历史") },
            text = { Text("确定要删除所有 ${entries.size} 条打印记录吗？此操作不可撤销。") },
            confirmButton = {
                TextButton(onClick = {
                    showClearAllConfirm = false
                    vm.clear()
                }) { Text("确认清空") }
            },
            dismissButton = {
                TextButton(onClick = { showClearAllConfirm = false }) { Text("取消") }
            },
        )
    }

    // 删除单条记录确认对话框
    pendingDeleteId?.let { id ->
        AlertDialog(
            onDismissRequest = { pendingDeleteId = null },
            title = { Text("删除打印记录") },
            text = { Text("确定要删除这条打印记录吗？") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDeleteId = null
                    vm.delete(id)
                }) { Text("删除") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteId = null }) { Text("取消") }
            },
        )
    }

    reprint?.let { (image, entry) ->
        PrintSheet(
            image = image,
            initialMedia = entry.spec.media,
            onDismiss = { reprint = null },
            onPrinted = { copies, media, feedBeforeDots, feedAfterDots ->
                vm.recordReprint(entry, image, copies, media, feedBeforeDots, feedAfterDots)
            },
            onOpenPrinterSettings = {
                reprint = null
                onOpenPrinterSettings()
            },
        )
    }
}

@Composable
private fun HistoryCard(
    entry: PrintHistoryEntry,
    onReprint: () -> Unit,
    onDelete: () -> Unit,
    onEditQuick: (() -> Unit)? = null,
    onConvertLayout: (() -> Unit)? = null,
) {
    ElevatedCard(
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val bitmap = remember(entry.id) {
                val exact = HistoryRepository.decodeRaster(entry)
                if (exact != null) MonoConverter.toBitmap(exact).asImageBitmap()
                else LabelRenderer.render(entry.spec, entry.elements).asImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .width(82.dp)
                    .height(96.dp)
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp)),
                contentScale = if (entry.rasterBase64 != null) ContentScale.FillWidth else ContentScale.Fit,
                alignment = if (entry.rasterBase64 != null) Alignment.TopCenter else Alignment.Center,
            )
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    entry.templateName,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                val dateText = remember(entry.id) {
                    SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.CHINA).format(Date(entry.printedAt))
                }
                Text(dateText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                val perCopyLength = entry.printedLengthMm?.let { total ->
                    (total / entry.copies.coerceAtLeast(1)).toInt().coerceAtLeast(1)
                }
                Text(
                    "${entry.copies} 份 · ${perCopyLength?.let { "$it mm" } ?: if (entry.spec.autoLength) "自动长度" else "${entry.spec.lengthMm} mm"}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onReprint, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), modifier = Modifier.height(36.dp)) { Text("重打") }
                    if (onEditQuick != null) TextButton(onClick = onEditQuick, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), modifier = Modifier.height(36.dp)) { Text("重新编辑") }
                    if (onConvertLayout != null) TextButton(onClick = onConvertLayout, contentPadding = PaddingValues(horizontal = 5.dp, vertical = 0.dp), modifier = Modifier.height(36.dp)) { Text("自由排版") }
                    IconButton(onClick = onDelete, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Default.Delete, contentDescription = stringResource(R.string.cd_delete), tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
