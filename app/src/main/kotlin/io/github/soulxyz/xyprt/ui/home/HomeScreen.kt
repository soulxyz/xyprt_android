package io.github.soulxyz.xyprt.ui.home

import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
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
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
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
import androidx.compose.ui.platform.LocalConfiguration
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
import io.github.soulxyz.xyprt.data.PrintStatsSnapshot
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.model.LabelTemplate
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.render.LabelRenderer
import io.github.soulxyz.xyprt.ui.components.ClearButton
import io.github.soulxyz.xyprt.ui.info.InfoDialog
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class HomeTab { PRINT, DOCUMENTS, ME }

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
    onOpenCoCreator: () -> Unit,
    vm: HomeViewModel = viewModel(),
) {
    val context = LocalContext.current
    val templates by vm.templates.collectAsState()
    val recentHistory by vm.recentHistory.collectAsState()
    val query by vm.query.collectAsState()
    val printerState by vm.printerState.collectAsState()
    val savedPrinter by vm.savedPrinter.collectAsState()
    val updateUnseen by vm.updateUnseen.collectAsState()
    val printStats by vm.printStats.collectAsState()
    val coCreatorState by vm.coCreatorState.collectAsState()
    var selectedTab by rememberSaveable { mutableStateOf(HomeTab.PRINT) }
    var showNewDialog by rememberSaveable { mutableStateOf(false) }
    var editTarget by remember { mutableStateOf<LabelTemplate?>(null) }
    var exportTarget by remember { mutableStateOf<LabelTemplate?>(null) }
    var deleteTarget by remember { mutableStateOf<LabelTemplate?>(null) }
    var showInfoDialog by rememberSaveable { mutableStateOf(false) }
    val defaultName = stringResource(R.string.default_label_name)

    BackHandler(enabled = selectedTab != HomeTab.PRINT) { selectedTab = HomeTab.PRINT }

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
                    when (selectedTab) {
                        HomeTab.PRINT -> Row(verticalAlignment = Alignment.CenterVertically) {
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(12.dp),
                                modifier = Modifier.size(34.dp),
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
                        HomeTab.DOCUMENTS -> Text("文档", fontWeight = FontWeight.SemiBold)
                        HomeTab.ME -> Text("我的", fontWeight = FontWeight.SemiBold)
                    }
                },
                actions = {
                    when (selectedTab) {
                        HomeTab.PRINT -> PrinterStatusAction(
                            state = printerState,
                            savedName = savedPrinter?.name,
                            onClick = onOpenSettings,
                        )
                        HomeTab.DOCUMENTS -> IconButton(onClick = { showNewDialog = true }) {
                            Icon(Icons.Default.Add, contentDescription = "新建文档")
                        }
                        HomeTab.ME -> Unit
                    }
                },
            )
        },
        bottomBar = {
            NavigationBar(
                containerColor = MaterialTheme.colorScheme.surface,
                tonalElevation = 0.dp,
            ) {
                NavigationBarItem(
                    selected = selectedTab == HomeTab.PRINT,
                    onClick = { selectedTab = HomeTab.PRINT },
                    icon = { Icon(painterResource(R.drawable.ic_print), contentDescription = null) },
                    label = { Text("打印") },
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.DOCUMENTS,
                    onClick = { selectedTab = HomeTab.DOCUMENTS },
                    icon = { Icon(painterResource(R.drawable.ic_documents), contentDescription = null) },
                    label = { Text("文档") },
                )
                NavigationBarItem(
                    selected = selectedTab == HomeTab.ME,
                    onClick = { selectedTab = HomeTab.ME },
                    icon = {
                        Box {
                            Icon(painterResource(R.drawable.ic_person), contentDescription = null)
                            if (updateUnseen) {
                                Surface(
                                    color = MaterialTheme.colorScheme.error,
                                    shape = RoundedCornerShape(50),
                                    modifier = Modifier.size(7.dp).align(Alignment.TopEnd),
                                ) {}
                            }
                        }
                    },
                    label = { Text("我的") },
                )
            }
        },
    ) { padding ->
        BoxWithConstraints(Modifier.padding(padding).fillMaxSize()) {
            val compactPhone = maxWidth < 360.dp
            val gridColumns = when (selectedTab) {
                HomeTab.PRINT -> GridCells.Fixed(if (compactPhone) 1 else 2)
                HomeTab.DOCUMENTS -> GridCells.Adaptive(minSize = if (maxWidth >= 720.dp) 220.dp else 156.dp)
                HomeTab.ME -> GridCells.Fixed(1)
            }
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                LazyVerticalGrid(
                    columns = gridColumns,
                    modifier = Modifier.widthIn(max = 980.dp).fillMaxSize(),
                    contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    when (selectedTab) {
                HomeTab.PRINT -> {
                    item {
                        QuickActionCard(
                            title = "图片", subtitle = "从相册选择",
                            iconRes = R.drawable.ic_quick_image, featured = true, onClick = onQuickImage,
                        )
                    }
                    item {
                        QuickActionCard(
                            title = "扫描", subtitle = "拍一张纸",
                            iconRes = R.drawable.ic_camera, featured = true, onClick = onQuickCamera,
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        CompactPrintActions(
                            onText = onQuickText,
                            onTodo = onQuickTodo,
                            onPdf = onQuickDocument,
                        )
                    }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        QuickActionWideCard(
                            title = "自由排版", subtitle = "图文、表格、二维码",
                            iconRes = R.drawable.ic_quick_layout,
                            onClick = { vm.create("", LabelSpec(autoLength = true), defaultName, onOpenTemplate) },
                        )
                    }
                    if (recentHistory.isNotEmpty()) {
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            SectionHeader(title = "最近打印", action = "全部", onAction = onOpenHistory)
                        }
                        item(span = { GridItemSpan(maxLineSpan) }) {
                            RecentPrintPanel(entries = recentHistory.take(2), onClick = onOpenHistory)
                        }
                    }
                }

                HomeTab.DOCUMENTS -> {
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
                            EmptyDocumentsCard(searching = query.isNotBlank(), onCreate = { showNewDialog = true })
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
                }

                HomeTab.ME -> {
                    item(span = { GridItemSpan(maxLineSpan) }) { PrintStatsCard(printStats) }
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        ProfileActionGroup(
                            recentCount = recentHistory.size.coerceAtMost(50),
                            savedPrinterName = savedPrinter?.name,
                            updateUnseen = updateUnseen,
                            coCreatorActive = coCreatorState.active,
                            onOpenHistory = onOpenHistory,
                            onOpenCoCreator = onOpenCoCreator,
                            onOpenInfo = { vm.markCurrentUpdateSeen(); showInfoDialog = true },
                            onOpenSettings = onOpenSettings,
                        )
                    }
                }
            }
                    item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(8.dp)) }
                }
            }
        }
    }

    if (showInfoDialog) InfoDialog(
        onDismiss = { showInfoDialog = false },
        onOpenCoCreator = onOpenCoCreator,
    )

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
private fun PrintStatsCard(stats: PrintStatsSnapshot) {
    val distance = formatPrintedDistance(stats.printedLengthMm, stats.mileageComplete)
    val analogy = printedDistanceAnalogy(stats.printedLengthMm)
    val shape = RoundedCornerShape(26.dp)
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = .7f), shape),
        color = MaterialTheme.colorScheme.surfaceContainerLowest,
        shape = shape,
    ) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 22.dp, vertical = 20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("打印足迹", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.Bottom) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(distance, style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.SemiBold)
                    Text("累计打印长度", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text("${stats.printCount}", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Medium)
                    Text("次打印", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (analogy != null || !stats.mileageComplete) {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = .55f),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(Modifier.padding(horizontal = 14.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                        analogy?.let { Text(it, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium) }
                        if (!stats.mileageComplete) {
                            Text(
                                "部分早期记录没有保存完整长度，实际会更多。",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileActionGroup(
    recentCount: Int,
    savedPrinterName: String?,
    updateUnseen: Boolean,
    coCreatorActive: Boolean,
    onOpenHistory: () -> Unit,
    onOpenCoCreator: () -> Unit,
    onOpenInfo: () -> Unit,
    onOpenSettings: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
        shape = RoundedCornerShape(20.dp),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column {
            ProfileActionRow(
                iconRes = R.drawable.ic_history,
                title = "打印记录",
                subtitle = if (recentCount == 0) "还没有打印记录" else "最近 $recentCount 条",
                onClick = onOpenHistory,
            )
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(start = 58.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ProfileActionRow(
                iconRes = R.drawable.ic_logo_color,
                title = "共创计划",
                subtitle = if (coCreatorActive) "已加入" else "小范围开放中",
                onClick = onOpenCoCreator,
            )
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(start = 58.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ProfileActionRow(
                iconRes = R.drawable.ic_info,
                title = "关于与更新",
                subtitle = if (updateUnseen) "有新版本可用" else "版本与更新",
                indicator = updateUnseen,
                onClick = onOpenInfo,
            )
            androidx.compose.material3.HorizontalDivider(
                modifier = Modifier.padding(start = 58.dp),
                color = MaterialTheme.colorScheme.outlineVariant,
            )
            ProfileActionRow(
                iconRes = R.drawable.ic_settings_simple,
                title = "设置",
                subtitle = savedPrinterName ?: "打印机、备份与其他设置",
                onClick = onOpenSettings,
            )
        }
    }
}

@Composable
private fun ProfileActionRow(
    iconRes: Int,
    title: String,
    subtitle: String,
    indicator: Boolean = false,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Icon(
            painterResource(iconRes),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(26.dp),
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(1.dp)) {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(
                subtitle,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (indicator) {
            Surface(
                color = MaterialTheme.colorScheme.error,
                shape = RoundedCornerShape(999.dp),
                modifier = Modifier.size(8.dp),
            ) {}
        }
        Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.outline)
    }
}

@Composable
private fun PrinterStatusAction(
    state: PrinterState,
    savedName: String?,
    onClick: () -> Unit,
) {
    val compact = LocalConfiguration.current.screenWidthDp < 360
    val connected = state is PrinterState.Ready
    val busy = state is PrinterState.Printing || state is PrinterState.Connecting
    val dot = when {
        connected -> MaterialTheme.colorScheme.primary
        busy -> MaterialTheme.colorScheme.tertiary
        state is PrinterState.Error -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.outline
    }
    val primary = when (state) {
        is PrinterState.Ready -> if (compact) "已连接" else state.name.removeSuffix("_BLE").ifBlank { "已连接" }
        is PrinterState.Printing -> "打印中"
        is PrinterState.Connecting -> "连接中"
        is PrinterState.Error -> "需处理"
        is PrinterState.Disconnected -> if (compact) "未连接" else savedName?.removeSuffix("_BLE")?.takeIf { it.isNotBlank() } ?: "未连接"
    }
    val secondary = if (compact) null else when (state) {
        is PrinterState.Ready -> state.batteryPercent?.let { "$it%" }
        is PrinterState.Disconnected -> if (savedName.isNullOrBlank()) null else "未连接"
        else -> null
    }
    val status = when (state) {
        is PrinterState.Ready -> listOfNotNull(primary, secondary).joinToString("，")
        is PrinterState.Printing -> "正在打印"
        is PrinterState.Connecting -> "正在连接打印机"
        is PrinterState.Error -> "打印机需要处理"
        is PrinterState.Disconnected -> listOfNotNull(savedName, "未连接").joinToString("，")
    }
    Surface(
        modifier = Modifier.clip(RoundedCornerShape(18.dp)).clickable(onClick = onClick),
        color = MaterialTheme.colorScheme.surfaceContainerLow,
        shape = RoundedCornerShape(18.dp),
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 9.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Box {
                Icon(
                    painterResource(R.drawable.ic_print),
                    contentDescription = status,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(22.dp),
                )
                Surface(
                    color = dot,
                    shape = RoundedCornerShape(999.dp),
                    modifier = Modifier.size(7.dp).align(Alignment.BottomEnd),
                ) {}
            }
            Column(Modifier.widthIn(max = if (compact) 72.dp else 128.dp)) {
                Text(
                    primary,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                if (secondary != null) {
                    Text(
                        secondary,
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
        }
    }
}

@Composable
private fun CompactPrintActions(
    onText: () -> Unit,
    onTodo: () -> Unit,
    onPdf: () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        if (maxWidth < 330.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                CompactPrintAction("文字", R.drawable.ic_quick_text, onText, Modifier.fillMaxWidth())
                CompactPrintAction("待办", R.drawable.ic_quick_todo, onTodo, Modifier.fillMaxWidth())
                CompactPrintAction("PDF", R.drawable.ic_quick_pdf, onPdf, Modifier.fillMaxWidth())
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CompactPrintAction("文字", R.drawable.ic_quick_text, onText, Modifier.weight(1f))
                CompactPrintAction("待办", R.drawable.ic_quick_todo, onTodo, Modifier.weight(1f))
                CompactPrintAction("PDF", R.drawable.ic_quick_pdf, onPdf, Modifier.weight(1f))
            }
        }
    }
}

@Composable
private fun CompactPrintAction(
    title: String,
    iconRes: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    OutlinedCard(onClick = onClick, modifier = modifier) {
        Column(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                painterResource(iconRes),
                contentDescription = null,
                modifier = Modifier.size(21.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Text(title, style = MaterialTheme.typography.labelLarge)
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
        modifier = Modifier.fillMaxWidth().heightIn(min = 88.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (featured) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainerLow,
        ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (featured) 1.dp else 0.dp),
    ) {
        Column(
            modifier = Modifier.padding(12.dp),
            verticalArrangement = Arrangement.spacedBy(5.dp),
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
                        modifier = Modifier.size(19.dp),
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
            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Surface(
                color = MaterialTheme.colorScheme.secondaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(36.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painterResource(iconRes), contentDescription = null, modifier = Modifier.size(19.dp), tint = MaterialTheme.colorScheme.onSecondaryContainer)
                }
            }
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.outline)
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
                        modifier = Modifier.size(36.dp),
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
                    Text("›", style = MaterialTheme.typography.headlineSmall, color = MaterialTheme.colorScheme.outline)
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
                    "自由排版文档会保存在这里。",
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
