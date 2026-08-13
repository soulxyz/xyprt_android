package io.github.soulxyz.xyprt.ui.home

import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.ble.PrinterState
import io.github.soulxyz.xyprt.data.PrintHistoryEntry
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.model.LabelTemplate
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.render.LabelRenderer
import io.github.soulxyz.xyprt.ui.components.ClearButton
import io.github.soulxyz.xyprt.ui.components.rememberBlePermissionState
import io.github.soulxyz.xyprt.ui.info.InfoDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onOpenSettings: () -> Unit,
    onOpenTemplate: (String) -> Unit,
    onOpenHistory: () -> Unit,
    onQuickText: () -> Unit,
    onQuickImage: () -> Unit,
    onQuickDocument: () -> Unit,
    onQuickCamera: () -> Unit,
    onQuickTodo: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val templates by vm.templates.collectAsState()
    val recentHistory by vm.recentHistory.collectAsState()
    val query by vm.query.collectAsState()
    val printerState by vm.printerState.collectAsState()
    val savedPrinter by vm.savedPrinter.collectAsState()
    val updateUnseen by vm.updateUnseen.collectAsState()
    val blePermission = rememberBlePermissionState()
    var showNewDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<LabelTemplate?>(null) }
    var exportTarget by remember { mutableStateOf<LabelTemplate?>(null) }
    var deleteTarget by remember { mutableStateOf<LabelTemplate?>(null) }
    var showInfoDialog by rememberSaveable { mutableStateOf(false) }
    val defaultName = stringResource(R.string.default_label_name)

    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/zip")
    ) { uri ->
        val target = exportTarget
        exportTarget = null
        if (uri != null && target != null) {
            vm.exportTo(uri, target) { error ->
                val msg = error?.let { context.getString(R.string.toast_export_failed, it) }
                    ?: context.getString(R.string.toast_export_ok)
                Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) vm.importFrom(uri, defaultName) { error, newId ->
            if (error != null) Toast.makeText(context, context.getString(R.string.toast_import_failed, error), Toast.LENGTH_LONG).show()
            else if (newId != null) onOpenTemplate(newId)
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.size(38.dp),
                        ) {
                            Box(contentAlignment = Alignment.Center) {
                                Image(
                                    painterResource(R.drawable.ic_logo_color),
                                    contentDescription = null,
                                    modifier = Modifier.width(20.dp).height(26.dp),
                                )
                            }
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(stringResource(R.string.app_name), fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    IconButton(onClick = onOpenHistory) {
                        Icon(painterResource(R.drawable.ic_history), contentDescription = stringResource(R.string.cd_history))
                    }
                    Box {
                        IconButton(onClick = { showInfoDialog = true }) {
                            Icon(painterResource(R.drawable.ic_info), contentDescription = stringResource(R.string.cd_info))
                        }
                        if (updateUnseen) {
                            Surface(
                                color = MaterialTheme.colorScheme.error,
                                shape = RoundedCornerShape(50),
                                modifier = Modifier.size(8.dp).align(Alignment.TopEnd).offset(x = (-7).dp, y = 7.dp),
                            ) {}
                        }
                    }
                },
            )
        },
    ) { padding ->
        LazyVerticalGrid(
            columns = GridCells.Adaptive(minSize = 156.dp),
            modifier = Modifier.padding(padding).fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item(span = { GridItemSpan(maxLineSpan) }) {
                PrinterOverviewCard(
                    state = printerState,
                    savedName = savedPrinter?.name,
                    permissionMissing = !blePermission.granted && savedPrinter != null,
                    onOpenSettings = onOpenSettings,
                    onPrimaryAction = {
                        when {
                            !blePermission.granted && savedPrinter != null -> blePermission.request()
                            savedPrinter != null && printerState is PrinterState.Disconnected -> vm.connectSaved()
                            else -> onOpenSettings()
                        }
                    },
                )
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(title = "开始打印")
            }
            item {
                QuickActionCard(
                    title = "图片",
                    subtitle = "照片、截图、题目",
                    iconRes = R.drawable.ic_quick_image,
                    featured = true,
                    onClick = onQuickImage,
                )
            }
            item {
                QuickActionCard(
                    title = "PDF",
                    subtitle = "试卷、讲义、文档",
                    iconRes = R.drawable.ic_quick_pdf,
                    featured = true,
                    onClick = onQuickDocument,
                )
            }
            item {
                QuickActionCard(
                    title = "拍照",
                    subtitle = "拍题后裁剪打印",
                    iconRes = R.drawable.ic_camera,
                    onClick = onQuickCamera,
                )
            }
            item {
                QuickActionCard(
                    title = "文字",
                    subtitle = "字号、字体、行距",
                    iconRes = R.drawable.ic_quick_text,
                    onClick = onQuickText,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                QuickActionWideCard(
                    title = "待办清单",
                    subtitle = "今天要做什么，打印后直接勾选",
                    iconRes = R.drawable.ic_quick_todo,
                    onClick = onQuickTodo,
                )
            }
            item(span = { GridItemSpan(maxLineSpan) }) {
                QuickActionWideCard(
                    title = "自由排版",
                    subtitle = "图文、表格、二维码",
                    iconRes = R.drawable.ic_quick_layout,
                    onClick = { showNewDialog = true },
                )
            }

            if (recentHistory.isNotEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    SectionHeader(
                        title = "最近打印",
                        action = "全部",
                        onAction = onOpenHistory,
                    )
                }
                item(span = { GridItemSpan(maxLineSpan) }) {
                    RecentPrintPanel(
                        entries = recentHistory.take(2),
                        onClick = onOpenHistory,
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) {
                SectionHeader(
                    title = "我的文档",
                    subtitle = null,
                    action = "新建",
                    onAction = { showNewDialog = true },
                )
            }

            if (templates.isNotEmpty() || query.isNotBlank()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    OutlinedTextField(
                        value = query,
                        onValueChange = vm::setQuery,
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("搜索文档") },
                        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                        trailingIcon = { if (query.isNotEmpty()) ClearButton { vm.setQuery("") } },
                        singleLine = true,
                        shape = MaterialTheme.shapes.large,
                    )
                }
            }

            if (templates.isEmpty()) {
                item(span = { GridItemSpan(maxLineSpan) }) {
                    EmptyDocumentsCard(
                        searching = query.isNotBlank(),
                        onCreate = { showNewDialog = true },
                    )
                }
            } else {
                items(templates, key = { it.id }) { template ->
                    val copyName = context.getString(R.string.duplicate_name, template.name)
                    TemplateCard(
                        template = template,
                        modifier = Modifier.animateItem(),
                        onClick = { onOpenTemplate(template.id) },
                        onToggleFavorite = { vm.toggleFavorite(template) },
                        onEdit = { editTarget = template },
                        onDuplicate = { vm.duplicate(template.id, copyName) },
                        onDelete = { deleteTarget = template },
                        onExport = {
                            exportTarget = template
                            exportLauncher.launch("${template.name}.xyprt")
                        },
                    )
                }
            }

            item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
        }
    }

    if (showInfoDialog) InfoDialog(onDismiss = { vm.markCurrentUpdateSeen(); showInfoDialog = false })

    if (showNewDialog) {
        LabelDialog(
            title = stringResource(R.string.dialog_new_title),
            initialName = "",
            initialSpec = LabelSpec(),
            onDismiss = { showNewDialog = false },
            onConfirm = { name, spec ->
                showNewDialog = false
                vm.create(name, spec, defaultName, onOpenTemplate)
            },
            onImport = {
                showNewDialog = false
                importLauncher.launch(arrayOf("application/zip", "application/octet-stream", "application/json"))
            },
            autofocusName = true,
        )
    }

    editTarget?.let { target ->
        LabelDialog(
            title = stringResource(R.string.dialog_edit_title),
            initialName = target.name,
            initialSpec = target.spec,
            onDismiss = { editTarget = null },
            onConfirm = { name, spec -> vm.updateMeta(target.id, name, spec); editTarget = null },
            onImport = null,
        )
    }

    deleteTarget?.let { target ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text(stringResource(R.string.delete_title)) },
            text = { Text(stringResource(R.string.delete_message, target.name)) },
            confirmButton = {
                Button(
                    onClick = { vm.delete(target.id); deleteTarget = null },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.menu_delete)) }
            },
            dismissButton = { TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) } },
        )
    }
}

@Composable
private fun PrinterOverviewCard(
    state: PrinterState,
    savedName: String?,
    permissionMissing: Boolean,
    onOpenSettings: () -> Unit,
    onPrimaryAction: () -> Unit,
) {
    val ready = state is PrinterState.Ready
    val title = when (state) {
        is PrinterState.Ready -> state.name.removeSuffix("_BLE")
        is PrinterState.Printing -> "正在打印"
        is PrinterState.Connecting -> savedName ?: "正在连接打印机"
        is PrinterState.Error -> savedName ?: "打印机需要处理"
        is PrinterState.Disconnected -> savedName ?: "连接打印机"
    }
    val subtitle = when (state) {
        is PrinterState.Ready -> buildString {
            append("已连接")
            state.batteryPercent?.let { append(" · 电量 $it%") }
        }
        is PrinterState.Printing -> "任务进行中，请保持打印机开机"
        is PrinterState.Connecting -> "正在连接…"
        is PrinterState.Error -> state.message
        is PrinterState.Disconnected -> if (savedName != null) "未连接 · 点此重新连接" else "首次使用连接一次，之后会自动记住"
    }

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (ready) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer,
        ),
        onClick = onOpenSettings,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = if (ready) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHigh,
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.size(46.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painterResource(R.drawable.ic_print),
                        contentDescription = null,
                        modifier = Modifier.size(23.dp),
                        tint = if (ready) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
            }
            if (!ready && state !is PrinterState.Printing) {
                Button(onClick = onPrimaryAction) {
                    Text(when { permissionMissing -> "允许蓝牙"; savedName != null -> "连接"; else -> "查找" })
                }
            } else {
                Text("管理", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable
private fun SectionHeader(
    title: String,
    subtitle: String? = null,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (action != null && onAction != null) {
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun QuickActionCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    onClick: () -> Unit,
    featured: Boolean = false,
) {
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth().heightIn(min = 106.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (featured) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (featured) 1.dp else 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Surface(
                color = if (featured) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(38.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(21.dp),
                        tint = if (featured) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSecondaryContainer,
                    )
                }
            }
            Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun QuickActionWideCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    onClick: () -> Unit,
) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text("打开", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun RecentPrintPanel(entries: List<PrintHistoryEntry>, onClick: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth(), onClick = onClick) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            entries.forEachIndexed { index, entry ->
                if (index > 0) {
                    androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
                Row(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Surface(
                        color = MaterialTheme.colorScheme.surfaceContainerHigh,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier.size(42.dp),
                    ) {
                        Box(contentAlignment = Alignment.Center) {
                            Icon(
                                painterResource(R.drawable.ic_print),
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                        }
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(entry.templateName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        val whenText = remember(entry.id) {
                            SimpleDateFormat("MM-dd HH:mm", Locale.CHINA).format(Date(entry.printedAt))
                        }
                        Text(
                            "$whenText · ${entry.copies} 份",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text("查看", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

@Composable
private fun EmptyDocumentsCard(searching: Boolean, onCreate: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                if (searching) "没有找到匹配的文档" else "还没有保存的排版文档",
                style = MaterialTheme.typography.bodyLarge,
            )
            if (!searching) {
                Text(
                    "日常打印直接用上面的图片、PDF 或文字即可。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedButton(onClick = onCreate) { Text("新建排版文档") }
            }
        }
    }
}

@Composable
private fun TemplateCard(
    template: LabelTemplate,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
    onToggleFavorite: () -> Unit,
    onEdit: () -> Unit,
    onDuplicate: () -> Unit,
    onDelete: () -> Unit,
    onExport: () -> Unit,
) {
    var menuOpen by remember { mutableStateOf(false) }
    ElevatedCard(
        onClick = onClick,
        modifier = modifier,
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Column(Modifier.padding(10.dp)) {
            val bitmap = remember(template.id, template.updatedAt) {
                LabelRenderer.render(template.spec, template.elements).asImageBitmap()
            }
            Image(
                bitmap = bitmap,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(144.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(10.dp)),
                contentScale = ContentScale.Fit,
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(template.name, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f), maxLines = 1)
                Box {
                    IconButton(onClick = { menuOpen = true }, modifier = Modifier.size(40.dp)) {
                        Icon(Icons.Default.MoreVert, contentDescription = stringResource(R.string.cd_menu))
                    }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_edit)) }, onClick = { menuOpen = false; onEdit() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_duplicate)) }, onClick = { menuOpen = false; onDuplicate() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_export)) }, onClick = { menuOpen = false; onExport() })
                        DropdownMenuItem(text = { Text(stringResource(R.string.menu_delete)) }, onClick = { menuOpen = false; onDelete() })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    if (template.spec.autoLength) stringResource(R.string.template_auto_length)
                    else stringResource(R.string.template_fixed_length, template.spec.lengthMm),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onToggleFavorite, modifier = Modifier.size(40.dp)) {
                    Icon(
                        Icons.Default.Star,
                        contentDescription = stringResource(R.string.cd_favorite),
                        tint = if (template.favorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LabelDialog(
    title: String,
    initialName: String,
    initialSpec: LabelSpec,
    onDismiss: () -> Unit,
    onConfirm: (String, LabelSpec) -> Unit,
    onImport: (() -> Unit)?,
    autofocusName: Boolean = false,
) {
    var name by rememberSaveable(initialName) { mutableStateOf(initialName) }
    val nameFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) { if (autofocusName) nameFocus.requestFocus() }
    var autoLength by rememberSaveable(initialSpec) { mutableStateOf(initialSpec.autoLength) }
    var lengthText by rememberSaveable(initialSpec) { mutableStateOf(initialSpec.lengthMm.toString()) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text(stringResource(R.string.field_name)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().focusRequester(nameFocus),
                    trailingIcon = { if (name.isNotEmpty()) ClearButton { name = "" } },
                )
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.length_mode), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = autoLength,
                        onClick = { autoLength = true },
                        label = { Text(stringResource(R.string.length_auto)) },
                    )
                    FilterChip(
                        selected = !autoLength,
                        onClick = { autoLength = false },
                        label = { Text(stringResource(R.string.length_fixed)) },
                    )
                }
                if (!autoLength) {
                    Spacer(Modifier.height(8.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        LabelSpec.LENGTH_PRESETS.forEach { l ->
                            FilterChip(
                                selected = lengthText == "$l",
                                onClick = { lengthText = "$l" },
                                label = { Text("${l}mm") },
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = lengthText,
                        onValueChange = { lengthText = it.filter(Char::isDigit).take(3) },
                        label = { Text(stringResource(R.string.field_length_mm)) },
                        supportingText = { Text(stringResource(R.string.length_fixed_hint)) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                    )
                } else {
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.length_auto_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        },
        confirmButton = {
            val submit = {
                val length = lengthText.toIntOrNull()?.coerceIn(LabelSpec.MIN_LENGTH_MM, LabelSpec.MAX_LENGTH_MM) ?: 80
                onConfirm(name, LabelSpec(tapeWidthMm = 48, lengthMm = length, media = MediaType.CONTINUOUS, autoLength = autoLength))
            }
            if (onImport != null) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    OutlinedButton(onClick = onImport) {
                        Icon(painterResource(R.drawable.ic_import), contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.action_import))
                    }
                    Button(onClick = submit) { Text(stringResource(R.string.action_create)) }
                }
            } else Button(onClick = submit) { Text(stringResource(R.string.action_save)) }
        },
    )
}
