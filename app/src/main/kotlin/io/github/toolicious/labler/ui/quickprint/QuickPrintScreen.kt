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
import io.github.toolicious.labler.ui.components.rememberBlePermissionRunner
import io.github.toolicious.labler.ui.print.PrintSheet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private enum class SourceMode { TEXT, IMAGE, PDF }

@OptIn(ExperimentalMaterial3Api::class)
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
    var dither by remember { mutableStateOf(if (sourceMode == SourceMode.IMAGE) DitherMode.FLOYD_STEINBERG else DitherMode.THRESHOLD) }
    var mono by remember { mutableStateOf<MonoImage?>(null) }
    var rendering by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showPrint by remember { mutableStateOf(false) }
    var pickerOpened by remember { mutableStateOf(false) }
    val withBt = rememberBlePermissionRunner()

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) { uris = picked; sourceMode = SourceMode.IMAGE }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) { uris = listOf(picked); sourceMode = SourceMode.PDF }
    }

    // Internal quick actions open the right picker immediately. External share/view already has content.
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

    LaunchedEffect(sourceMode, text, uris, dither) {
        if (sourceMode != SourceMode.TEXT && uris.isEmpty()) { mono = null; return@LaunchedEffect }
        if (sourceMode == SourceMode.TEXT && text.isBlank()) { mono = null; return@LaunchedEffect }
        rendering = true
        error = null
        mono = runCatching {
            withContext(Dispatchers.IO) {
                val bitmap = when (sourceMode) {
                    SourceMode.TEXT -> QuickPrintRenderer.text(text)
                    SourceMode.IMAGE -> if (uris.size == 1) QuickPrintRenderer.image(context, uris.first()) else QuickPrintRenderer.images(context, uris)
                    SourceMode.PDF -> QuickPrintRenderer.pdf(context, uris.first())
                }
                QuickPrintRenderer.toMono(bitmap, if (sourceMode == SourceMode.TEXT) DitherMode.THRESHOLD else dither)
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
                FilterChip(selected = sourceMode == SourceMode.TEXT, onClick = { sourceMode = SourceMode.TEXT; uris = emptyList() }, label = { Text("文字") })
                FilterChip(selected = sourceMode == SourceMode.IMAGE, onClick = { sourceMode = SourceMode.IMAGE; imagePicker.launch(arrayOf("image/*")) }, label = { Text("图片") })
                FilterChip(selected = sourceMode == SourceMode.PDF, onClick = { sourceMode = SourceMode.PDF; pdfPicker.launch(arrayOf("application/pdf")) }, label = { Text("PDF") })
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

            if (sourceMode == SourceMode.IMAGE) {
                Spacer(Modifier.height(10.dp))
                Text(stringResource(R.string.quick_image_mode), style = MaterialTheme.typography.labelLarge)
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    FilterChip(selected = dither == DitherMode.FLOYD_STEINBERG, onClick = { dither = DitherMode.FLOYD_STEINBERG }, label = { Text("照片") })
                    FilterChip(selected = dither == DitherMode.THRESHOLD, onClick = { dither = DitherMode.THRESHOLD }, label = { Text("黑白文字") })
                    FilterChip(selected = dither == DitherMode.ATKINSON, onClick = { dither = DitherMode.ATKINSON }, label = { Text("高对比") })
                }
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
                        "按纸张实际宽度预览 · 384 点宽 · 预计长度约 ${(mono!!.height + 7) / 8} mm · 长内容可在预览中上下滑动",
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
