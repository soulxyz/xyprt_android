package io.github.soulxyz.xyprt.ui.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.ble.BlePermissions
import io.github.soulxyz.xyprt.ble.PrinterState
import io.github.soulxyz.xyprt.data.UpdateDownloadMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenCoCreator: () -> Unit = {},
    onOpenEnhanced: () -> Unit = {},
    vm: SettingsViewModel = viewModel(),
) {
    val context = LocalContext.current
    val appContainer = remember { (context.applicationContext as App).container }
    val coCreator by appContainer.coCreator.state.collectAsState()
    val modelCatalog by appContainer.enhancedModels.catalog.collectAsState()
    val updateDownloadMode by appContainer.settings.updateDownloadMode.collectAsState(initial = UpdateDownloadMode.INTERNAL)
    val scope = rememberCoroutineScope()
    val state by vm.printerState.collectAsState()
    val info by vm.printerInfo.collectAsState()
    val saved by vm.savedPrinter.collectAsState()
    val commandFeedback by vm.commandFeedback.collectAsState()
    var showScanSheet by remember { mutableStateOf(false) }
    var showForgetConfirm by remember { mutableStateOf(false) }
    var pendingAction by remember { mutableStateOf<(() -> Unit)?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { grants ->
        val action = pendingAction
        pendingAction = null
        if (grants.values.all { it }) action?.invoke()
    }

    fun withPermissions(action: () -> Unit) {
        if (BlePermissions.allGranted(context)) {
            action()
        } else {
            pendingAction = action
            permissionLauncher.launch(BlePermissions.required())
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.settings_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentAlignment = Alignment.TopCenter,
        ) {
        Column(
            Modifier
                .widthIn(max = 900.dp)
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text(stringResource(R.string.settings_printer), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))

            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp)) {
                    val statusText = when (val s = state) {
                        is PrinterState.Disconnected -> stringResource(R.string.status_disconnected)
                        is PrinterState.Connecting -> stringResource(R.string.status_connecting, s.attempt)
                        is PrinterState.Ready ->
                            if (s.batteryPercent != null)
                                stringResource(R.string.status_ready_battery, s.name, s.batteryPercent)
                            else stringResource(R.string.status_ready, s.name)
                        is PrinterState.Printing -> stringResource(R.string.status_printing, (s.progress * 100).toInt())
                        is PrinterState.Error -> stringResource(R.string.status_error, s.message)
                    }
                    Text(statusText, style = MaterialTheme.typography.bodyMedium)

                    saved?.let {
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                stringResource(R.string.settings_saved, it.name, it.address),
                                style = MaterialTheme.typography.bodySmall,
                                modifier = Modifier.weight(1f)
                            )
                            // Accent-colored X: forgets the saved printer (after confirmation).
                            IconButton(
                                onClick = { showForgetConfirm = true },
                                modifier = Modifier.size(32.dp)
                            ) {
                                Icon(
                                    Icons.Default.Close,
                                    contentDescription = stringResource(R.string.action_forget),
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }

                    if (state is PrinterState.Ready && info != null) {
                        Spacer(Modifier.height(8.dp))
                        HorizontalDivider()
                        Spacer(Modifier.height(8.dp))
                        info?.model?.let { Text(stringResource(R.string.info_model, it), style = MaterialTheme.typography.bodySmall) }
                        info?.firmware?.let { Text(stringResource(R.string.info_firmware, it), style = MaterialTheme.typography.bodySmall) }
                        info?.hardware?.let { Text(stringResource(R.string.info_hardware, it), style = MaterialTheme.typography.bodySmall) }
                        info?.serial?.let { Text(stringResource(R.string.info_serial, it), style = MaterialTheme.typography.bodySmall) }
                    }

                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val savedDisconnected = saved != null && state is PrinterState.Disconnected
                        when {
                            savedDisconnected -> {
                                // Saved printer, disconnected: connecting is the primary action.
                                Button(onClick = { withPermissions { vm.reconnectSaved() } }) {
                                    Text(stringResource(R.string.action_connect))
                                }
                                OutlinedButton(onClick = { withPermissions { showScanSheet = true } }) {
                                    Text(stringResource(R.string.scan_title))
                                }
                            }
                            state is PrinterState.Ready -> {
                                OutlinedButton(onClick = { withPermissions { showScanSheet = true } }) {
                                    Text(stringResource(R.string.scan_title))
                                }
                                OutlinedButton(onClick = { vm.disconnect() }) {
                                    Text(stringResource(R.string.action_disconnect))
                                }
                            }
                            else -> {
                                // No saved printer (or connecting): scanning is the primary action.
                                Button(
                                    onClick = { withPermissions { showScanSheet = true } },
                                    enabled = state !is PrinterState.Printing
                                ) { Text(stringResource(R.string.scan_title)) }
                            }
                        }
                    }
                    if (state is PrinterState.Ready) {
                        TextButton(onClick = { vm.printTest() }) { Text("打印测试页") }
                    }
                    commandFeedback?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            Text("版本与增强", style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Card(Modifier.fillMaxWidth(), shape = RoundedCornerShape(18.dp)) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("共创计划", style = MaterialTheme.typography.titleSmall)
                            Text(if (coCreator.active) "已加入" else "小范围开放中", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onOpenCoCreator) { Text(if (coCreator.active) "查看" else "了解") }
                    }
                    HorizontalDivider()
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("增强识别", style = MaterialTheme.typography.titleSmall)
                            val ready = modelCatalog.items.count { it.installed && !it.locked }
                            Text(if (ready > 0) "已启用" else "复杂背景和浅色纸边时识别更稳定", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        TextButton(onClick = onOpenEnhanced) { Text("管理") }
                    }
                    HorizontalDivider()
                    Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                        Text("更新下载", style = MaterialTheme.typography.titleSmall)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            FilterChip(
                                selected = updateDownloadMode == UpdateDownloadMode.INTERNAL,
                                onClick = { scope.launch { appContainer.settings.saveUpdateDownloadMode(UpdateDownloadMode.INTERNAL) } },
                                label = { Text("应用内") },
                            )
                            FilterChip(
                                selected = updateDownloadMode == UpdateDownloadMode.EXTERNAL,
                                onClick = { scope.launch { appContainer.settings.saveUpdateDownloadMode(UpdateDownloadMode.EXTERNAL) } },
                                label = { Text("浏览器") },
                            )
                        }
                        Text(
                            if (updateDownloadMode == UpdateDownloadMode.EXTERNAL) "下载完整安装包。" else "优先下载变化部分。",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(20.dp))
            Text(
                stringResource(R.string.settings_simple_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(24.dp))
        }
        }
    }

    if (showForgetConfirm) {
        AlertDialog(
            onDismissRequest = { showForgetConfirm = false },
            title = { Text(stringResource(R.string.forget_title)) },
            text = { Text(stringResource(R.string.forget_message, saved?.name ?: "")) },
            confirmButton = {
                TextButton(onClick = { showForgetConfirm = false; vm.forget() }) {
                    Text(stringResource(R.string.action_forget))
                }
            },
            dismissButton = {
                TextButton(onClick = { showForgetConfirm = false }) {
                    Text(stringResource(R.string.action_cancel))
                }
            }
        )
    }

    if (showScanSheet) {
        ScanSheet(
            vm = vm,
            onDismiss = {
                vm.stopScan()
                showScanSheet = false
            }
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ScanSheet(vm: SettingsViewModel, onDismiss: () -> Unit) {
    val scanning by vm.scanning.collectAsState()
    val results by vm.visibleResults.collectAsState()
    val showAll by vm.showAll.collectAsState()
    val scanError by vm.scanError.collectAsState()

    LaunchedEffect(Unit) { vm.startScan() }

    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = showAll, onCheckedChange = { vm.setShowAll(it) })
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.scan_show_all), style = MaterialTheme.typography.bodyMedium)
            }
            if (scanning) {
                Spacer(Modifier.height(8.dp))
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            scanError?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(8.dp))
            if (!scanning && results.isEmpty() && scanError == null) {
                Text(stringResource(R.string.scan_empty), style = MaterialTheme.typography.bodyMedium)
            }
            LazyColumn {
                items(results, key = { it.key }) { found ->
                    ListItem(
                        headlineContent = { Text(found.name) },
                        supportingContent = { Text(found.primaryAddress) },
                        modifier = Modifier.fillMaxWidth(),
                        trailingContent = {
                            TextButton(onClick = {
                                vm.connectTo(found)
                                onDismiss()
                            }) { Text(stringResource(R.string.action_connect)) }
                        }
                    )
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
