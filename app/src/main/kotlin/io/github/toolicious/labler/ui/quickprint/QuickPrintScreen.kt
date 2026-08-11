package io.github.toolicious.labler.ui.quickprint

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.width
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
import androidx.compose.material3.Slider
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
import androidx.core.content.FileProvider
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
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class SourceMode { TEXT, IMAGE, PDF, CAMERA }

private fun defaultAdjustments(mode: SourceMode) = when (mode) {
    SourceMode.IMAGE, SourceMode.CAMERA -> QuickImageAdjustments(mode = DitherMode.FLOYD_STEINBERG, threshold = 155)
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
    var textStyle by remember { mutableStateOf(QuickTextStyle()) }
    var uris by remember { mutableStateOf(externalUris(externalIntent)) }
    var adjustments by remember { mutableStateOf(defaultAdjustments(inferred)) }
    var pdfAutoCrop by remember { mutableStateOf(true) }
    var mono by remember { mutableStateOf<MonoImage?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPrint by remember { mutableStateOf(false) }
    var showAdjustments by remember { mutableStateOf(false) }
    var showTextSettings by remember { mutableStateOf(false) }
    var pickerOpened by remember { mutableStateOf(false) }
    var pendingCameraUri by remember { mutableStateOf<Uri?>(null) }
    var cameraCropDraft by remember { mutableStateOf(CropRect()) }
    var cameraCropApplied by remember { mutableStateOf(CropRect()) }
    var showCropEditor by remember { mutableStateOf(false) }
    val withBt = rememberBlePermissionRunner()

    fun switchMode(next: SourceMode) {
        sourceMode = next
        adjustments = defaultAdjustments(next)
        if (next == SourceMode.TEXT) uris = emptyList()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) {
            uris = picked
            showCropEditor = false
            switchMode(SourceMode.IMAGE)
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            uris = listOf(picked)
            showCropEditor = false
            switchMode(SourceMode.PDF)
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUri
        if (ok && uri != null) {
            sourceMode = SourceMode.CAMERA
            adjustments = defaultAdjustments(SourceMode.CAMERA)
            uris = listOf(uri)
            cameraCropDraft = CropRect()
            cameraCropApplied = CropRect()
            showCropEditor = true
        }
    }
    fun launchCamera() {
        val uri = newCameraUri(context)
        pendingCameraUri = uri
        sourceMode = SourceMode.CAMERA
        cameraLauncher.launch(uri)
    }

    LaunchedEffect(Unit) {
        if (externalIntent == null && !pickerOpened) {
            pickerOpened = true
            when (sourceMode) {
                SourceMode.IMAGE -> imagePicker.launch(arrayOf("image/*"))
                SourceMode.PDF -> pdfPicker.launch(arrayOf("application/pdf"))
                SourceMode.CAMERA -> launchCamera()
                SourceMode.TEXT -> Unit
            }
        }
    }

    LaunchedEffect(sourceMode, text, textStyle, uris, adjustments, pdfAutoCrop, cameraCropApplied, showCropEditor) {
        if (sourceMode != SourceMode.TEXT && uris.isEmpty()) {
            mono = null
            return@LaunchedEffect
        }
        if (sourceMode == SourceMode.TEXT && text.isBlank()) {
            mono = null
            return@LaunchedEffect
        }
        if (sourceMode == SourceMode.CAMERA && showCropEditor) {
            mono = null
            return@LaunchedEffect
        }
        // Slider drags / typing can update many times per second. A tiny debounce keeps the UI fluid and
        // guarantees only the latest state performs the expensive PDF/image pipeline.
        delay(70)
        rendering = true
        error = null
        mono = runCatching {
            withContext(Dispatchers.IO) {
                val bitmap = when (sourceMode) {
                    SourceMode.TEXT -> QuickPrintRenderer.text(text, textStyle)
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
                    SourceMode.CAMERA -> QuickPrintRenderer.image(
                        context,
                        uris.first(),
                        adjustments.rotationDegrees,
                        adjustments.scalePercent,
                        crop = cameraCropApplied,
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
                title = { Text(if (sourceMode == SourceMode.CAMERA) "拍照打印" else "快速打印", fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        bottomBar = {
            if (sourceMode == SourceMode.CAMERA && showCropEditor) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        OutlinedButton(onClick = { cameraCropDraft = CropRect() }, modifier = Modifier.weight(1f)) { Text("重置") }
                        Button(
                            onClick = {
                                cameraCropApplied = cameraCropDraft.normalized()
                                showCropEditor = false
                            },
                            modifier = Modifier.weight(2f),
                        ) { Text("使用此区域") }
                    }
                }
            } else {
                QuickPrintBottomBar(
                    sourceMode = sourceMode,
                    adjustments = adjustments,
                    textStyle = textStyle,
                    enabled = mono != null && !rendering,
                    onAdjust = { showAdjustments = true },
                    onTextAdjust = { showTextSettings = true },
                    onPrint = { withBt { showPrint = true } },
                )
            }
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
                onCamera = { launchCamera() },
            )
            Spacer(Modifier.height(10.dp))

            AnimatedContent(
                targetState = sourceMode,
                transitionSpec = { fadeIn(tween(180)) togetherWith fadeOut(tween(120)) },
                label = "quick-source",
            ) { activeMode ->
                Column {
                    when (activeMode) {
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
                            Spacer(Modifier.height(8.dp))
                            TextStyleSummary(textStyle, onClick = { showTextSettings = true })
                        }
                        SourceMode.IMAGE -> SelectedSourceCard(
                            title = if (uris.size > 1) "已选择 ${uris.size} 张图片" else displayName(context, uris.firstOrNull()) ?: "选择图片",
                            subtitle = if (uris.isEmpty()) "从相册或文件中选择" else "可连续打印多张图片",
                            iconRes = R.drawable.ic_quick_image,
                            action = if (uris.isEmpty()) "选择" else "更换",
                            onAction = { imagePicker.launch(arrayOf("image/*")) },
                        )
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
                        SourceMode.CAMERA -> {
                            if (uris.isEmpty()) {
                                SelectedSourceCard(
                                    title = "拍照获取内容",
                                    subtitle = "适合错题、笔记、书页和票据",
                                    iconRes = R.drawable.ic_camera,
                                    action = "拍照",
                                    onAction = { launchCamera() },
                                )
                            } else if (showCropEditor) {
                                Text("框选要打印的内容", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text("拖动四个圆点调整范围；拖动框内可整体移动。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(10.dp))
                                PhotoCropEditor(
                                    uri = uris.first(),
                                    crop = cameraCropDraft,
                                    onCropChange = { cameraCropDraft = it },
                                )
                                Spacer(Modifier.height(8.dp))
                                TextButton(onClick = { launchCamera() }) { Text("重新拍摄") }
                            } else {
                                SelectedSourceCard(
                                    title = "已截取拍摄内容",
                                    subtitle = "可继续调整黑白效果后打印",
                                    iconRes = R.drawable.ic_camera,
                                    action = "重拍",
                                    onAction = { launchCamera() },
                                )
                                Spacer(Modifier.height(6.dp))
                                TextButton(onClick = {
                                    cameraCropDraft = cameraCropApplied
                                    showCropEditor = true
                                }) { Text("重新裁剪") }
                            }
                        }
                    }
                }
            }

            if ((sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.PDF || sourceMode == SourceMode.CAMERA) && !showCropEditor && uris.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                RasterModeSelector(
                    mode = adjustments.mode,
                    onMode = { adjustments = adjustments.copy(mode = it) },
                )
            }

            if (!(sourceMode == SourceMode.CAMERA && showCropEditor)) {
                Spacer(Modifier.height(14.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text("打印预览", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (rendering) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else if (mono != null) {
                        Text("约 ${(mono!!.height + 7) / 8} mm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    rendering -> PreviewPlaceholder()
                    mono != null -> MonoPaperPreview(image = mono!!, minViewportHeight = 260.dp, maxViewportHeight = 560.dp)
                    else -> EmptyPreviewCard(sourceMode)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
            }
            Spacer(Modifier.height(20.dp))
        }
    }

    if (showAdjustments && sourceMode != SourceMode.TEXT) {
        ModalBottomSheet(onDismissRequest = { showAdjustments = false }) {
            Column(
                Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text("打印调整", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                // The preview lives inside the sheet: the controls never hide the thing being adjusted.
                mono?.let {
                    MonoPaperPreview(image = it, minViewportHeight = 130.dp, maxViewportHeight = 190.dp)
                }
                RasterModeSelector(mode = adjustments.mode, onMode = { adjustments = adjustments.copy(mode = it) })
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

    if (showTextSettings) {
        TextFormattingSheet(
            style = textStyle,
            preview = mono,
            onStyle = { textStyle = it },
            onDismiss = { showTextSettings = false },
        )
    }

    if (showPrint) mono?.let { image ->
        PrintSheet(
            image = image,
            initialMedia = MediaType.CONTINUOUS,
            onDismiss = { showPrint = false },
            onPrinted = { copies, _ ->
                vm.recordPrinted(title = quickHistoryTitle(context, sourceMode, text, uris), image = image, copies = copies)
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
    onCamera: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        FilterChip(selected = sourceMode == SourceMode.IMAGE, onClick = onImage, label = { Text("图片") }, modifier = Modifier.weight(1f))
        FilterChip(selected = sourceMode == SourceMode.PDF, onClick = onPdf, label = { Text("PDF") }, modifier = Modifier.weight(1f))
        FilterChip(selected = sourceMode == SourceMode.TEXT, onClick = onText, label = { Text("文字") }, modifier = Modifier.weight(1f))
        FilterChip(selected = sourceMode == SourceMode.CAMERA, onClick = onCamera, label = { Text("拍照") }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TextStyleSummary(style: QuickTextStyle, onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("文字排版", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "字号 ${style.fontSizePx} · ${fontLabel(style.font)} · 行距 ${style.lineSpacingPercent}% · ${alignLabel(style.align)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("设置", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TextFormattingSheet(
    style: QuickTextStyle,
    preview: MonoImage?,
    onStyle: (QuickTextStyle) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(
            Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("文字排版", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
            preview?.let { MonoPaperPreview(image = it, minViewportHeight = 120.dp, maxViewportHeight = 180.dp) }

            Text("字号 ${style.fontSizePx}", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = style.fontSizePx.toFloat(),
                onValueChange = { onStyle(style.copy(fontSizePx = it.roundToInt())) },
                valueRange = 18f..56f,
            )

            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("字体", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                QuickTextFont.entries.forEach { font ->
                    FilterChip(selected = style.font == font, onClick = { onStyle(style.copy(font = font)) }, label = { Text(fontLabel(font)) }, modifier = Modifier.weight(1f))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("对齐", style = MaterialTheme.typography.labelLarge, modifier = Modifier.width(44.dp))
                QuickTextAlign.entries.forEach { align ->
                    FilterChip(selected = style.align == align, onClick = { onStyle(style.copy(align = align)) }, label = { Text(alignLabel(align)) }, modifier = Modifier.weight(1f))
                }
            }

            Text("行距 ${style.lineSpacingPercent}%", style = MaterialTheme.typography.labelLarge)
            Slider(
                value = style.lineSpacingPercent.toFloat(),
                onValueChange = { onStyle(style.copy(lineSpacingPercent = it.roundToInt())) },
                valueRange = 90f..180f,
            )
            Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("完成") }
            Spacer(Modifier.height(12.dp))
        }
    }
}

@Composable
private fun SelectedSourceCard(title: String, subtitle: String, iconRes: Int, action: String, onAction: () -> Unit) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(color = MaterialTheme.colorScheme.primaryContainer, shape = RoundedCornerShape(12.dp), modifier = Modifier.size(42.dp)) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(painter = painterResource(iconRes), contentDescription = null, modifier = Modifier.size(22.dp), tint = MaterialTheme.colorScheme.primary)
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
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
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
                    SourceMode.CAMERA -> "拍照并裁剪后，这里会显示实际打印效果"
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
    textStyle: QuickTextStyle,
    enabled: Boolean,
    onAdjust: () -> Unit,
    onTextAdjust: () -> Unit,
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
                Column(Modifier.weight(1f)) {
                    if (sourceMode == SourceMode.TEXT) {
                        Text("${fontLabel(textStyle.font)} · 字号 ${textStyle.fontSizePx} · ${alignLabel(textStyle.align)}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Text("行距 ${textStyle.lineSpacingPercent}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    } else {
                        Text("${modeLabel(adjustments.mode)} · ${adjustmentSummary(adjustments)}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Text("需要时再打开调整", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                }
                OutlinedButton(onClick = if (sourceMode == SourceMode.TEXT) onTextAdjust else onAdjust, enabled = enabled) {
                    Text(if (sourceMode == SourceMode.TEXT) "排版" else "调整")
                }
                Button(onClick = onPrint, enabled = enabled) { Text("打印") }
            }
        }
    }
}

private fun fontLabel(font: QuickTextFont): String = when (font) {
    QuickTextFont.SANS -> "无衬线"
    QuickTextFont.SERIF -> "衬线"
    QuickTextFont.MONO -> "等宽"
}

private fun alignLabel(align: QuickTextAlign): String = when (align) {
    QuickTextAlign.LEFT -> "左对齐"
    QuickTextAlign.CENTER -> "居中"
    QuickTextAlign.RIGHT -> "右对齐"
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

private fun quickHistoryTitle(context: Context, mode: SourceMode, text: String, uris: List<Uri>): String = when (mode) {
    SourceMode.TEXT -> text.trim().lineSequence().firstOrNull()?.take(24)?.takeIf { it.isNotBlank() } ?: "快速文字"
    SourceMode.IMAGE -> if (uris.size > 1) "图片打印（${uris.size}张）" else displayName(context, uris.firstOrNull()) ?: "图片打印"
    SourceMode.PDF -> displayName(context, uris.firstOrNull()) ?: "PDF 打印"
    SourceMode.CAMERA -> "拍照打印"
}

private fun displayName(context: Context, uri: Uri?): String? {
    if (uri == null) return null
    return runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull()
}

private fun inferMode(mode: String, intent: Intent?): SourceMode {
    val mime = intent?.type.orEmpty()
    return when {
        mode.equals("camera", true) -> SourceMode.CAMERA
        mode.equals("image", true) || mime.startsWith("image/") -> SourceMode.IMAGE
        mode.equals("pdf", true) || mime == "application/pdf" -> SourceMode.PDF
        else -> SourceMode.TEXT
    }
}

private fun externalText(context: Context, intent: Intent?): String? = when (intent?.action) {
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

private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}
