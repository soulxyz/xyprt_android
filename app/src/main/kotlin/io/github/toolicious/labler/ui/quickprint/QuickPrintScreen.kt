package io.github.toolicious.labler.ui.quickprint

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import io.github.toolicious.labler.R
import io.github.toolicious.labler.printer.MediaType
import io.github.toolicious.labler.printer.MonoImage
import io.github.toolicious.labler.printer.dither.DitherMode
import io.github.toolicious.labler.ui.components.MonoPaperPreview
import io.github.toolicious.labler.ui.components.RasterAdjustmentDetails
import io.github.toolicious.labler.ui.components.RasterModeSelector
import io.github.toolicious.labler.ui.components.rememberBlePermissionRunner
import io.github.toolicious.labler.ui.print.PrintSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SourceMode { TEXT, IMAGE, PDF }

private fun defaultAdjustments(mode: SourceMode) = when (mode) {
    SourceMode.IMAGE -> QuickImageAdjustments(mode = DitherMode.FLOYD_STEINBERG, threshold = 155)
    SourceMode.PDF -> QuickImageAdjustments(mode = DitherMode.THRESHOLD, threshold = 190, contrast = 10)
    SourceMode.TEXT -> QuickImageAdjustments(mode = DitherMode.THRESHOLD, threshold = 170)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPrintScreen(
    mode: String,
    onBack: () -> Unit,
    externalIntent: Intent? = null,
    vm: QuickPrintViewModel = viewModel(),
) {
    val context = LocalContext.current
    val inferred = remember(mode, externalIntent) { inferMode(mode, externalIntent) }
    var sourceMode by remember { mutableStateOf(inferred) }
    var text by remember { mutableStateOf(externalText(context, externalIntent).orEmpty()) }
    var uris by remember { mutableStateOf(externalUris(externalIntent)) }
    var adjustments by remember { mutableStateOf(defaultAdjustments(inferred)) }
    var pdfAutoCrop by remember { mutableStateOf(true) }
    var mono by remember { mutableStateOf<MonoImage?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPrint by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var pickerOpened by remember { mutableStateOf(false) }
    val withBt = rememberBlePermissionRunner()

    fun switchMode(next: SourceMode) {
        sourceMode = next
        adjustments = defaultAdjustments(next)
        if (next == SourceMode.TEXT) uris = emptyList()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) {
            uris = picked
            switchMode(SourceMode.IMAGE)
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uris = listOf(picked)
            switchMode(SourceMode.PDF)
        }
    }

    LaunchedEffect(Unit) {
        if (externalIntent == null && !pickerOpened) {
            pickerOpened = true
            when (sourceMode) {
                SourceMode.IMAGE -> imagePicker.launch(arrayOf("image/*"))
                SourceMode.PDF -> pdfPicker.launch(arrayOf("application/pdf"))
                SourceMode.TEXT -> Unit
            }
        }
    }

    LaunchedEffect(sourceMode, text, uris, adjustments, pdfAutoCrop) {
        if (sourceMode != SourceMode.TEXT && uris.isEmpty()) {
            mono = null
            return@LaunchedEffect
        }
        if (sourceMode == SourceMode.TEXT && text.isBlank()) {
            mono = null
            return@LaunchedEffect
        }
        rendering = true
        error = null
        mono = runCatching {
            withContext(Dispatchers.IO) {
                val bitmap = when (sourceMode) {
                    SourceMode.TEXT -> QuickPrintRenderer.text(text)
                    SourceMode.IMAGE -> if (uris.size == 1) {
                        QuickPrintRenderer.image(context, uris.first(), adjustments.rotationDegrees, adjustments.scalePercent)
                    } else {
                        QuickPrintRenderer.images(context, uris, adjustments.rotationDegrees, adjustments.scalePercent)
                    }
                    SourceMode.PDF -> QuickPrintRenderer.pdf(
                        context,
                        uris.first(),
                        autoCropWhiteMargins = pdfAutoCrop,
                        rotationDegrees = adjustments.rotationDegrees,
                        scalePercent = adjustments.scalePercent,
                    )
                }
                QuickPrintRenderer.toMono(
                    bitmap,
                    if (sourceMode == SourceMode.TEXT) defaultAdjustments(SourceMode.TEXT) else adjustments,
                ).also { bitmap.recycle() }
            }
        }.onFailure { error = it.message ?: "内容处理失败" }.getOrNull()
        rendering = false
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = { Text("快速打印", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        bottomBar = {
            QuickPrintBottomBar(
                sourceMode = sourceMode,
                adjustments = adjustments,
                enabled = mono != null && !rendering,
                onAdjust = { showAdjustments = true },
                onPrint = { withBt { showPrint = true } },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .padding(padding)
                .padding(horizontal = 16.dp)
                .fillMaxSize()
                .imePadding()
                .verticalScroll(rememberScrollState()),
        ) {
            Spacer(Modifier.height(4.dp))
            SourceSelector(
                sourceMode = sourceMode,
                onText = { switchMode(SourceMode.TEXT) },
                onImage = {
                    if (sourceMode == SourceMode.IMAGE && uris.isNotEmpty()) switchMode(SourceMode.IMAGE)
                    else imagePicker.launch(arrayOf("image/*"))
                },
                onPdf = {
                    if (sourceMode == SourceMode.PDF && uris.isNotEmpty()) switchMode(SourceMode.PDF)
                    else pdfPicker.launch(arrayOf("application/pdf"))
                },
            )
            Spacer(Modifier.height(10.dp))

            when (sourceMode) {
                SourceMode.TEXT -> {
                    OutlinedTextField(
                        value = text,
                        onValueChange = { text = it },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 4,
                        maxLines = 9,
                        placeholder = { Text("输入要打印的文字") },
                        shape = MaterialTheme.shapes.large,
                    )
                }
                SourceMode.IMAGE -> {
                    SelectedSourceCard(
                        title = if (uris.size > 1) "已选择 ${uris.size} 张图片" else displayName(context, uris.firstOrNull()) ?: "选择图片",
                        subtitle = if (uris.isEmpty()) "从相册或文件中选择" else "可连续打印多张图片",
                        iconRes = R.drawable.ic_quick_image,
                        action = if (uris.isEmpty()) "选择" else "更换",
                        onAction = { imagePicker.launch(arrayOf("image/*")) },
                    )
                }
                SourceMode.PDF -> {
                    SelectedSourceCard(
                        title = displayName(context, uris.firstOrNull()) ?: "选择 PDF",
                        subtitle = if (uris.isEmpty()) "选择试卷、讲义或文档" else "已载入 PDF 文档",
                        iconRes = R.drawable.ic_quick_pdf,
                        action = if (uris.isEmpty()) "选择" else "更换",
                        onAction = { pdfPicker.launch(arrayOf("application/pdf")) },
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text("页面", style = MaterialTheme.typography.labelLarge)
                        FilterChip(selected = pdfAutoCrop, onClick = { pdfAutoCrop = true }, label = { Text("去白边") })
                        FilterChip(selected = !pdfAutoCrop, onClick = { pdfAutoCrop = false }, label = { Text("保留整页") })
                    }
                }
            }

            if (sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.PDF) {
                Spacer(Modifier.height(8.dp))
                RasterModeSelector(
                    mode = adjustments.mode,
                    onMode = { adjustments = adjustments.copy(mode = it) },
                )
            }

            Spacer(Modifier.height(14.dp))
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Text("打印预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.weight(1f))
                if (rendering) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                } else if (mono != null) {
                    Text(
                        "约 ${(mono!!.height + 7) / 8} mm",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            Spacer(Modifier.height(8.dp))

            when {
                rendering -> PreviewPlaceholder()
                mono != null -> MonoPaperPreview(
                    image = mono!!,
                    minViewportHeight = 260.dp,
                    maxViewportHeight = 560.dp,
                )
                else -> EmptyPreviewCard(sourceMode)
            }

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showAdjustments && sourceMode != SourceMode.TEXT) {
        ModalBottomSheet(onDismissRequest = { showAdjustments = false }) {
            Column(
                Modifier.padding(horizontal = 20.dp).navigationBarsPadding(),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("打印调整", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "边调边看预览，关闭后设置会保留。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                RasterModeSelector(
                    mode = adjustments.mode,
                    onMode = { adjustments = adjustments.copy(mode = it) },
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                RasterAdjustmentDetails(
                    mode = adjustments.mode,
                    threshold = adjustments.threshold,
                    contrast = adjustments.contrast,
                    invert = adjustments.invert,
                    outlineSensitivity = adjustments.outlineSensitivity,
                    outlineThickness = adjustments.outlineThickness,
                    outlineMethod = adjustments.outlineMethod,
                    outlineSmooth = adjustments.outlineSmooth,
                    onThreshold = { adjustments = adjustments.copy(threshold = it) },
                    onContrast = { adjustments = adjustments.copy(contrast = it) },
                    onInvert = { adjustments = adjustments.copy(invert = it) },
                    onOutlineSensitivity = { adjustments = adjustments.copy(outlineSensitivity = it) },
                    onOutlineThickness = { adjustments = adjustments.copy(outlineThickness = it) },
                    onOutlineMethod = { adjustments = adjustments.copy(outlineMethod = it) },
                    onOutlineSmooth = { adjustments = adjustments.copy(outlineSmooth = it) },
                    rotationDegrees = adjustments.rotationDegrees,
                    onRotationDegrees = { adjustments = adjustments.copy(rotationDegrees = it) },
                    scalePercent = adjustments.scalePercent,
                    onScalePercent = { adjustments = adjustments.copy(scalePercent = it) },
                )
                Button(onClick = { showAdjustments = false }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showPrint) mono?.let { image ->
        PrintSheet(
            image = image,
            initialMedia = MediaType.CONTINUOUS,
            onDismiss = { showPrint = false },
            onPrinted = { copies, _ ->
                vm.recordPrinted(
                    title = quickHistoryTitle(context, sourceMode, text, uris),
                    image = image,
                    copies = copies,
                )
            },
        )
    }
}

@Composable
private fun SourceSelector(
    sourceMode: SourceMode,
    onText: () -> Unit,
    onImage: () -> Unit,
    onPdf: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        FilterChip(selected = sourceMode == SourceMode.IMAGE, onClick = onImage, label = { Text("图片") }, modifier = Modifier.weight(1f))
        FilterChip(selected = sourceMode == SourceMode.PDF, onClick = onPdf, label = { Text("PDF") }, modifier = Modifier.weight(1f))
        FilterChip(selected = sourceMode == SourceMode.TEXT, onClick = onText, label = { Text("文字") }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SelectedSourceCard(
    title: String,
    subtitle: String,
    iconRes: Int,
    action: String,
    onAction: () -> Unit,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                color = MaterialTheme.colorScheme.primaryContainer,
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier.size(42.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(
                        painter = painterResource(iconRes),
                        contentDescription = null,
                        modifier = Modifier.size(22.dp),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
            Spacer(Modifier.size(10.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            }
            TextButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun PreviewPlaceholder() {
    ElevatedCard(
        modifier = Modifier.fillMaxWidth().height(260.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 0.dp),
    ) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
    }
}

@Composable
private fun EmptyPreviewCard(sourceMode: SourceMode) {
    OutlinedCard(modifier = Modifier.fillMaxWidth().height(260.dp)) {
        Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
            Text(
                when (sourceMode) {
                    SourceMode.TEXT -> "输入文字后，这里会显示实际打印效果"
                    SourceMode.IMAGE -> "选择图片后，这里会显示实际打印效果"
                    SourceMode.PDF -> "选择 PDF 后，这里会显示实际打印效果"
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun QuickPrintBottomBar(
    sourceMode: SourceMode,
    adjustments: QuickImageAdjustments,
    enabled: Boolean,
    onAdjust: () -> Unit,
    onPrint: () -> Unit,
) {
    Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (sourceMode != SourceMode.TEXT) {
                    Column(Modifier.weight(1f)) {
                        Text("${modeLabel(adjustments.mode)} · ${adjustmentSummary(adjustments)}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Text("点“调整”可旋转、缩放和微调", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    OutlinedButton(onClick = onAdjust, enabled = enabled) { Text("调整") }
                    Button(onClick = onPrint, enabled = enabled) { Text("打印") }
                } else {
                    Spacer(Modifier.weight(1f))
                    Button(onClick = onPrint, enabled = enabled, modifier = Modifier.fillMaxWidth()) { Text("打印") }
                }
            }
        }
    }
}

private fun modeLabel(mode: DitherMode): String = when (mode) {
    DitherMode.OUTLINE -> "线稿"
    DitherMode.THRESHOLD -> "黑白"
    DitherMode.FLOYD_STEINBERG -> "细腻"
    DitherMode.ATKINSON -> "清晰"
}

private fun adjustmentSummary(a: QuickImageAdjustments): String = when (a.mode) {
    DitherMode.OUTLINE -> "细节 ${a.outlineSensitivity} · ${a.scalePercent}%"
    DitherMode.THRESHOLD -> "阈值 ${a.threshold} · ${a.scalePercent}%"
    DitherMode.FLOYD_STEINBERG, DitherMode.ATKINSON -> "对比 ${a.contrast} · ${a.scalePercent}%"
}

private fun quickHistoryTitle(context: android.content.Context, mode: SourceMode, text: String, uris: List<Uri>): String = when (mode) {
    SourceMode.TEXT -> text.trim().lineSequence().firstOrNull()?.take(24)?.takeIf { it.isNotBlank() } ?: "快速文字"
    SourceMode.IMAGE -> if (uris.size > 1) "图片打印（${uris.size}张）" else displayName(context, uris.firstOrNull()) ?: "图片打印"
    SourceMode.PDF -> displayName(context, uris.firstOrNull()) ?: "PDF 打印"
}

private fun displayName(context: android.content.Context, uri: Uri?): String? {
    if (uri == null) return null
    return runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

private fun inferMode(mode: String, intent: Intent?): SourceMode {
    val mime = intent?.type.orEmpty()
    return when {
        mode.equals("image", true) || mime.startsWith("image/") -> SourceMode.IMAGE
        mode.equals("pdf", true) || mime == "application/pdf" -> SourceMode.PDF
        else -> SourceMode.TEXT
    }
}

private fun externalText(context: android.content.Context, intent: Intent?): String? = when (intent?.action) {
    Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)
    Intent.ACTION_VIEW -> if (intent.type?.startsWith("text/") == true) {
        intent.data?.let { uri -> runCatching { context.contentResolver.openInputStream(uri)?.use { it.readBytes().decodeToString() } }.getOrNull() }
    } else null
    else -> null
}

@Suppress("DEPRECATION")
private fun externalUris(intent: Intent?): List<Uri> {
    if (intent == null) return emptyList()
    return when (intent.action) {
        Intent.ACTION_VIEW -> listOfNotNull(intent.data)
        Intent.ACTION_SEND -> listOfNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
        Intent.ACTION_SEND_MULTIPLE -> (intent.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM) ?: arrayListOf()).toList()
        else -> emptyList()
    }
}
