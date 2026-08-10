package io.github.toolicious.labler.ui.quickprint

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.github.toolicious.labler.R
import io.github.toolicious.labler.printer.MediaType
import io.github.toolicious.labler.printer.MonoImage
import io.github.toolicious.labler.printer.dither.DitherMode
import io.github.toolicious.labler.printer.dither.OutlineMethod
import io.github.toolicious.labler.ui.components.MonoPaperPreview
import io.github.toolicious.labler.ui.components.rememberBlePermissionRunner
import io.github.toolicious.labler.ui.print.PrintSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class SourceMode { TEXT, IMAGE, PDF }

private fun defaultAdjustments(mode: SourceMode) = when (mode) {
    SourceMode.IMAGE -> QuickImageAdjustments(mode = DitherMode.FLOYD_STEINBERG, threshold = 155)
    SourceMode.PDF -> QuickImageAdjustments(mode = DitherMode.THRESHOLD, threshold = 190, contrast = 10)
    SourceMode.TEXT -> QuickImageAdjustments(mode = DitherMode.THRESHOLD, threshold = 170)
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun QuickPrintScreen(
    mode: String,
    onBack: () -> Unit,
    externalIntent: Intent? = null,
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
    var pickerOpened by remember { mutableStateOf(false) }
    val withBt = rememberBlePermissionRunner()

    fun switchMode(next: SourceMode) {
        sourceMode = next
        adjustments = defaultAdjustments(next)
        if (next == SourceMode.TEXT) uris = emptyList()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) { uris = picked; switchMode(SourceMode.IMAGE) }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) { uris = listOf(picked); switchMode(SourceMode.PDF) }
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
        if (sourceMode != SourceMode.TEXT && uris.isEmpty()) { mono = null; return@LaunchedEffect }
        if (sourceMode == SourceMode.TEXT && text.isBlank()) { mono = null; return@LaunchedEffect }
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
                        context, uris.first(), autoCropWhiteMargins = pdfAutoCrop,
                        rotationDegrees = adjustments.rotationDegrees, scalePercent = adjustments.scalePercent
                    )
                }
                QuickPrintRenderer.toMono(bitmap, if (sourceMode == SourceMode.TEXT) defaultAdjustments(SourceMode.TEXT) else adjustments)
                    .also { bitmap.recycle() }
            }
        }.onFailure { error = it.message ?: "内容处理失败" }.getOrNull()
        rendering = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.quick_print_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                }
            )
        }
    ) { padding ->
        Column(
            Modifier.padding(padding).padding(horizontal = 16.dp).fillMaxSize().imePadding().verticalScroll(rememberScrollState())
        ) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                FilterChip(selected = sourceMode == SourceMode.TEXT, onClick = { switchMode(SourceMode.TEXT) }, label = { Text("文字") })
                FilterChip(selected = sourceMode == SourceMode.IMAGE, onClick = { imagePicker.launch(arrayOf("image/*")) }, label = { Text("图片") })
                FilterChip(selected = sourceMode == SourceMode.PDF, onClick = { pdfPicker.launch(arrayOf("application/pdf")) }, label = { Text("PDF") })
            }
            Spacer(Modifier.height(10.dp))

            when (sourceMode) {
                SourceMode.TEXT -> OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    label = { Text(stringResource(R.string.quick_text_hint)) }
                )
                SourceMode.IMAGE -> OutlinedButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                    Text(if (uris.isEmpty()) stringResource(R.string.quick_choose_image) else "重新选择图片（${uris.size}）")
                }
                SourceMode.PDF -> OutlinedButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) {
                    Text(if (uris.isEmpty()) stringResource(R.string.quick_choose_pdf) else "重新选择 PDF")
                }
            }

            if (sourceMode == SourceMode.PDF) {
                Spacer(Modifier.height(12.dp))
                Text(stringResource(R.string.quick_pdf_layout), style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(
                        selected = pdfAutoCrop,
                        onClick = { pdfAutoCrop = true },
                        label = { Text(stringResource(R.string.quick_pdf_crop)) }
                    )
                    FilterChip(
                        selected = !pdfAutoCrop,
                        onClick = { pdfAutoCrop = false },
                        label = { Text(stringResource(R.string.quick_pdf_full)) }
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    stringResource(R.string.quick_pdf_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.PDF) {
                Spacer(Modifier.height(12.dp))
                QuickAdjustmentPanel(
                    value = adjustments,
                    onChange = { adjustments = it }
                )
            }

            Spacer(Modifier.height(14.dp))
            Text(stringResource(R.string.quick_preview), style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.height(6.dp))
            when {
                rendering -> Box(Modifier.fillMaxWidth().height(220.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
                mono != null -> {
                    MonoPaperPreview(
                        image = mono!!,
                        minViewportHeight = 220.dp,
                        maxViewportHeight = 520.dp,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        stringResource(R.string.quick_preview_info, (mono!!.height + 7) / 8),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                else -> Text(stringResource(R.string.quick_no_content), color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp)) }

            Spacer(Modifier.height(16.dp))
            Button(
                onClick = { withBt { showPrint = true } },
                enabled = mono != null && !rendering,
                modifier = Modifier.fillMaxWidth()
            ) { Text(stringResource(R.string.action_print)) }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (showPrint) mono?.let { image ->
        PrintSheet(image = image, initialMedia = MediaType.CONTINUOUS, onDismiss = { showPrint = false })
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun QuickAdjustmentPanel(value: QuickImageAdjustments, onChange: (QuickImageAdjustments) -> Unit) {
    Text(stringResource(R.string.quick_processing_title), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        listOf(
            DitherMode.OUTLINE to R.string.dither_outline,
            DitherMode.THRESHOLD to R.string.dither_threshold,
            DitherMode.FLOYD_STEINBERG to R.string.dither_fs,
            DitherMode.ATKINSON to R.string.dither_atkinson,
        ).forEach { (mode, label) ->
            FilterChip(
                selected = value.mode == mode,
                onClick = { onChange(value.copy(mode = mode)) },
                label = { Text(stringResource(label)) }
            )
        }
    }

    when (value.mode) {
        DitherMode.OUTLINE -> {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.quick_outline_detail, value.outlineSensitivity), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = value.outlineSensitivity.toFloat(),
                onValueChange = { onChange(value.copy(outlineSensitivity = it.roundToInt())) },
                valueRange = 0f..100f
            )
            Text(stringResource(R.string.quick_line_width, value.outlineThickness), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = value.outlineThickness.toFloat(),
                onValueChange = { onChange(value.copy(outlineThickness = it.roundToInt())) },
                valueRange = 1f..3f,
                steps = 1
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                FilterChip(
                    selected = value.outlineMethod == OutlineMethod.CANNY,
                    onClick = { onChange(value.copy(outlineMethod = OutlineMethod.CANNY)) },
                    label = { Text(stringResource(R.string.outline_canny)) }
                )
                FilterChip(
                    selected = value.outlineMethod == OutlineMethod.LINES,
                    onClick = { onChange(value.copy(outlineMethod = OutlineMethod.LINES)) },
                    label = { Text(stringResource(R.string.outline_lines)) }
                )
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.quick_smooth))
                    Switch(checked = value.outlineSmooth, onCheckedChange = { onChange(value.copy(outlineSmooth = it)) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.quick_invert))
                    Switch(checked = value.invert, onCheckedChange = { onChange(value.copy(invert = it)) })
                }
            }
        }
        DitherMode.THRESHOLD -> {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.quick_threshold, value.threshold), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = value.threshold.toFloat(),
                onValueChange = { onChange(value.copy(threshold = it.roundToInt())) },
                valueRange = 20f..235f
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.quick_invert))
                Switch(checked = value.invert, onCheckedChange = { onChange(value.copy(invert = it)) })
            }
        }
        DitherMode.FLOYD_STEINBERG, DitherMode.ATKINSON -> {
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.quick_contrast, value.contrast), style = MaterialTheme.typography.bodyMedium)
            Slider(
                value = value.contrast.toFloat(),
                onValueChange = { onChange(value.copy(contrast = it.roundToInt())) },
                valueRange = -100f..100f
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(stringResource(R.string.quick_invert))
                Switch(checked = value.invert, onCheckedChange = { onChange(value.copy(invert = it)) })
            }
        }
    }

    Spacer(Modifier.height(8.dp))
    Text(stringResource(R.string.quick_rotation), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        listOf(0, 90, 180, 270).forEach { deg ->
            FilterChip(
                selected = value.rotationDegrees == deg,
                onClick = { onChange(value.copy(rotationDegrees = deg)) },
                label = { Text("${deg}°") }
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Text(stringResource(R.string.quick_zoom, value.scalePercent), style = MaterialTheme.typography.bodyMedium)
    Slider(
        value = value.scalePercent.toFloat(),
        onValueChange = { onChange(value.copy(scalePercent = it.roundToInt())) },
        valueRange = 50f..180f
    )
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
