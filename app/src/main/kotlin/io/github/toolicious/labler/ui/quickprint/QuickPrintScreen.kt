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
import androidx.compose.material3.TextButton
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
import io.github.toolicious.labler.ui.components.MonoPaperPreview
import io.github.toolicious.labler.ui.components.RasterEffectControls
import io.github.toolicious.labler.ui.components.rememberBlePermissionRunner
import io.github.toolicious.labler.ui.print.PrintSheet
import androidx.lifecycle.viewmodel.compose.viewModel
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                FilterChip(selected = sourceMode == SourceMode.TEXT, onClick = { switchMode(SourceMode.TEXT) }, label = { Text("文字") })
                FilterChip(selected = sourceMode == SourceMode.IMAGE, onClick = { imagePicker.launch(arrayOf("image/*")) }, label = { Text("图片") })
                FilterChip(selected = sourceMode == SourceMode.PDF, onClick = { pdfPicker.launch(arrayOf("application/pdf")) }, label = { Text("PDF") })
                Spacer(Modifier.weight(1f))
                when (sourceMode) {
                    SourceMode.IMAGE -> TextButton(onClick = { imagePicker.launch(arrayOf("image/*")) }) {
                        Text(if (uris.isEmpty()) "选图片" else "换图片")
                    }
                    SourceMode.PDF -> TextButton(onClick = { pdfPicker.launch(arrayOf("application/pdf")) }) {
                        Text(if (uris.isEmpty()) "选 PDF" else "换 PDF")
                    }
                    SourceMode.TEXT -> Unit
                }
            }
            Spacer(Modifier.height(8.dp))

            if (sourceMode == SourceMode.TEXT) {
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    modifier = Modifier.fillMaxWidth(),
                    minLines = 5,
                    label = { Text(stringResource(R.string.quick_text_hint)) }
                )
            }

            if (sourceMode == SourceMode.PDF) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(stringResource(R.string.quick_pdf_layout), style = MaterialTheme.typography.labelLarge)
                    FilterChip(
                        selected = pdfAutoCrop,
                        onClick = { pdfAutoCrop = true },
                        label = { Text("去白边") }
                    )
                    FilterChip(
                        selected = !pdfAutoCrop,
                        onClick = { pdfAutoCrop = false },
                        label = { Text("整页") }
                    )
                }
            }

            if (sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.PDF) {
                Spacer(Modifier.height(6.dp))
                RasterEffectControls(
                    mode = adjustments.mode,
                    threshold = adjustments.threshold,
                    contrast = adjustments.contrast,
                    invert = adjustments.invert,
                    outlineSensitivity = adjustments.outlineSensitivity,
                    outlineThickness = adjustments.outlineThickness,
                    outlineMethod = adjustments.outlineMethod,
                    outlineSmooth = adjustments.outlineSmooth,
                    onMode = { adjustments = adjustments.copy(mode = it) },
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
            }

            Spacer(Modifier.height(8.dp))
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

/* Legacy local adjustment panel removed: quick image/PDF and editor image properties now use one shared component. */
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
