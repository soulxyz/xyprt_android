package io.github.soulxyz.xyprt.ui.quickprint

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.BackHandler
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
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
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.scanner.DocumentQuad
import io.github.soulxyz.xyprt.scanner.ScanEngine
import io.github.soulxyz.xyprt.scanner.QuadPoint
import io.github.soulxyz.xyprt.data.SavedDocument
import io.github.soulxyz.xyprt.data.QuickPrintDraft
import io.github.soulxyz.xyprt.data.QuickPrintHistorySource
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.printer.MonoImage
import io.github.soulxyz.xyprt.printer.dither.DitherMode
import io.github.soulxyz.xyprt.ui.components.MonoPaperPreview
import io.github.soulxyz.xyprt.ui.components.RasterAdjustmentDetails
import io.github.soulxyz.xyprt.ui.components.RasterModeSelector
import io.github.soulxyz.xyprt.ui.components.rememberBlePermissionRunner
import io.github.soulxyz.xyprt.ui.print.PrintSheet
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.roundToInt

private enum class SourceMode { TEXT, IMAGE, PDF, CAMERA, TODO }

private fun defaultAdjustments(mode: SourceMode) = when (mode) {
    SourceMode.IMAGE -> QuickImageAdjustments(mode = DitherMode.FLOYD_STEINBERG, threshold = 155)
    SourceMode.CAMERA -> QuickImageAdjustments(mode = DitherMode.THRESHOLD, paperPreset = PaperPreset.DOCUMENT, threshold = 188, contrast = 8)
    SourceMode.PDF -> QuickImageAdjustments(mode = DitherMode.THRESHOLD, threshold = 190, contrast = 10)
    SourceMode.TEXT, SourceMode.TODO -> QuickImageAdjustments(mode = DitherMode.THRESHOLD, threshold = 170)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QuickPrintScreen(
    mode: String,
    onBack: () -> Unit,
    externalIntent: Intent? = null,
    historyId: Long? = null,
    onOpenPrinterSettings: () -> Unit = {},
    vm: QuickPrintViewModel = viewModel(),
) {
    val context = LocalContext.current
    val scanner = remember { (context.applicationContext as App).container.scanner }
    val savedDocuments by vm.documents.collectAsState()
    val inferred = remember(mode, externalIntent) { inferMode(mode, externalIntent) }
    var sourceMode by remember { mutableStateOf(inferred) }
    var text by remember { mutableStateOf(externalText(context, externalIntent).orEmpty()) }
    var todoTitle by remember { mutableStateOf("今日待办") }
    var todoItems by remember { mutableStateOf("") }
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
    var showSavedDocuments by remember { mutableStateOf(false) }
    var savingDocument by remember { mutableStateOf(false) }
    var pickerOpened by rememberSaveable { mutableStateOf(false) }
    var pendingCameraUriText by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraQuadDraft by remember { mutableStateOf(DocumentQuad()) }
    var cameraQuadApplied by remember { mutableStateOf(DocumentQuad()) }
    var cameraScanLabel by remember { mutableStateOf("标准识别（开源内置）") }
    var cameraScanning by remember { mutableStateOf(false) }
    var scanGeneration by remember { mutableIntStateOf(0) }
    var imageSuggestionGeneration by remember { mutableIntStateOf(0) }
    var imageSuggestionQuad by remember { mutableStateOf<DocumentQuad?>(null) }
    var imageSuggestionScanning by remember { mutableStateOf(false) }
    // Ordinary gallery images enter as images. Cropping/perspective is an explicit document action.
    var showCropEditor by remember { mutableStateOf(false) }
    var imageCorrectionApplied by remember { mutableStateOf(false) }
    var savingDraft by remember { mutableStateOf(false) }
    var draftCandidate by remember { mutableStateOf<QuickPrintDraft?>(null) }
    var draftChecked by remember { mutableStateOf(false) }
    val preparedCache = remember { PreparedBitmapCache() }
    DisposableEffect(Unit) { onDispose { preparedCache.clear() } }
    val withBt = rememberBlePermissionRunner()

    fun switchMode(next: SourceMode) {
        sourceMode = next
        adjustments = defaultAdjustments(next)
        if (next == SourceMode.TEXT || next == SourceMode.TODO) uris = emptyList()
    }

    fun sourceSnapshot(includeDraftQuad: Boolean = false): QuickPrintHistorySource {
        val quad = if (showCropEditor) cameraQuadDraft else cameraQuadApplied
        val keepQuad = uris.size == 1 && (sourceMode == SourceMode.CAMERA ||
            (sourceMode == SourceMode.IMAGE && (imageCorrectionApplied || includeDraftQuad)))
        return QuickPrintHistorySource(
            mode = sourceMode.name,
            text = text,
            todoTitle = todoTitle,
            todoItems = todoItems,
            fontSizePx = textStyle.fontSizePx,
            lineSpacingPercent = textStyle.lineSpacingPercent,
            font = textStyle.font.name,
            align = textStyle.align.name,
            uris = uris.map(Uri::toString),
            ditherMode = adjustments.mode.name,
            paperPreset = adjustments.paperPreset.name,
            threshold = adjustments.threshold,
            contrast = adjustments.contrast,
            invert = adjustments.invert,
            outlineSensitivity = adjustments.outlineSensitivity,
            outlineThickness = adjustments.outlineThickness,
            outlineMethod = adjustments.outlineMethod.name,
            outlineSmooth = adjustments.outlineSmooth,
            rotationDegrees = adjustments.rotationDegrees,
            landscapePrint = adjustments.landscapePrint,
            scalePercent = adjustments.scalePercent,
            removeRedInk = adjustments.removeRedInk,
            removeBlueInk = adjustments.removeBlueInk,
            pdfAutoCrop = pdfAutoCrop,
            cameraQuad = if (keepQuad) quad.points().flatMap { listOf(it.x, it.y) } else emptyList(),
        )
    }

    fun applySource(source: QuickPrintHistorySource, draft: QuickPrintDraft? = null) {
        sourceMode = runCatching { SourceMode.valueOf(source.mode) }.getOrDefault(SourceMode.IMAGE)
        text = source.text
        todoTitle = source.todoTitle.ifBlank { "今日待办" }
        todoItems = source.todoItems
        textStyle = QuickTextStyle(
            fontSizePx = source.fontSizePx,
            lineSpacingPercent = source.lineSpacingPercent,
            font = runCatching { QuickTextFont.valueOf(source.font) }.getOrDefault(QuickTextFont.SANS),
            align = runCatching { QuickTextAlign.valueOf(source.align) }.getOrDefault(QuickTextAlign.LEFT),
        )
        uris = source.uris.mapNotNull { raw ->
            runCatching { Uri.parse(raw) }.getOrNull()?.takeIf { uri ->
                runCatching { context.contentResolver.openFileDescriptor(uri, "r")?.use { true } ?: false }.getOrDefault(false)
            }
        }
        adjustments = QuickImageAdjustments(
            mode = runCatching { DitherMode.valueOf(source.ditherMode) }.getOrDefault(DitherMode.THRESHOLD),
            paperPreset = runCatching { PaperPreset.valueOf(source.paperPreset) }.getOrDefault(PaperPreset.ORIGINAL),
            threshold = source.threshold, contrast = source.contrast, invert = source.invert,
            outlineSensitivity = source.outlineSensitivity, outlineThickness = source.outlineThickness,
            outlineMethod = runCatching { io.github.soulxyz.xyprt.printer.dither.OutlineMethod.valueOf(source.outlineMethod) }.getOrDefault(io.github.soulxyz.xyprt.printer.dither.OutlineMethod.CANNY),
            outlineSmooth = source.outlineSmooth, rotationDegrees = source.rotationDegrees,
            landscapePrint = source.landscapePrint, scalePercent = source.scalePercent,
            removeRedInk = source.removeRedInk, removeBlueInk = source.removeBlueInk,
        )
        pdfAutoCrop = source.pdfAutoCrop
        if (source.cameraQuad.size == 8) {
            val q = DocumentQuad(
                QuadPoint(source.cameraQuad[0], source.cameraQuad[1]),
                QuadPoint(source.cameraQuad[2], source.cameraQuad[3]),
                QuadPoint(source.cameraQuad[4], source.cameraQuad[5]),
                QuadPoint(source.cameraQuad[6], source.cameraQuad[7]),
            )
            cameraQuadApplied = q
            cameraQuadDraft = q
        } else {
            cameraQuadApplied = DocumentQuad()
            cameraQuadDraft = DocumentQuad()
        }
        imageCorrectionApplied = draft?.imageCorrectionApplied ?: (sourceMode == SourceMode.IMAGE && source.cameraQuad.size == 8)
        imageSuggestionQuad = null
        showCropEditor = draft?.showCropEditor ?: false
        if (sourceMode == SourceMode.CAMERA && source.cameraQuad.size == 8 && draft == null) showCropEditor = false
    }

    fun hasMeaningfulDraft(): Boolean = when (sourceMode) {
        SourceMode.TEXT -> text.isNotBlank()
        SourceMode.TODO -> todoTitle.isNotBlank() || todoItems.isNotBlank()
        SourceMode.IMAGE, SourceMode.PDF, SourceMode.CAMERA -> uris.isNotEmpty()
    }

    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { picked ->
        if (picked.isNotEmpty()) {
            picked.forEach { uri -> runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            switchMode(SourceMode.IMAGE)
            uris = picked
            cameraQuadDraft = DocumentQuad()
            cameraQuadApplied = DocumentQuad()
            imageCorrectionApplied = false
            imageSuggestionQuad = null
            showCropEditor = false
            if (picked.size == 1) imageSuggestionGeneration++
            error = null
        }
    }

    val scanImagePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            sourceMode = SourceMode.CAMERA
            adjustments = defaultAdjustments(SourceMode.CAMERA)
            uris = listOf(picked)
            cameraQuadDraft = DocumentQuad()
            cameraQuadApplied = DocumentQuad()
            imageCorrectionApplied = false
            imageSuggestionQuad = null
            cameraScanLabel = "正在识别纸张边缘…"
            showCropEditor = true
            scanGeneration++
            error = null
        }
    }
    val pdfPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { picked ->
        if (picked != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(picked, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            uris = listOf(picked)
            showCropEditor = false
            switchMode(SourceMode.PDF)
        }
    }
    val cameraLauncher = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val uri = pendingCameraUriText?.let(Uri::parse)
        val readable = uri?.let { cameraOutputReadable(context, it) } == true
        pendingCameraUriText = null
        if ((ok || readable) && uri != null) {
            sourceMode = SourceMode.CAMERA
            adjustments = defaultAdjustments(SourceMode.CAMERA)
            uris = listOf(uri)
            cameraQuadDraft = DocumentQuad()
            cameraQuadApplied = DocumentQuad()
            imageSuggestionQuad = null
            cameraScanLabel = "正在识别纸张边缘…"
            scanGeneration++
            showCropEditor = true
            error = null
        } else {
            error = "没有获取到照片，请重新拍摄"
        }
    }
    fun launchCamera() {
        pickerOpened = true
        val uri = runCatching { newCameraUri(context) }.getOrElse {
            error = "无法打开相机"
            return
        }
        pendingCameraUriText = uri.toString()
        sourceMode = SourceMode.CAMERA
        runCatching { cameraLauncher.launch(uri) }.onFailure {
            pendingCameraUriText = null
            error = "无法打开相机"
        }
    }

    fun openImageDocumentCorrection() {
        if (sourceMode != SourceMode.IMAGE || uris.size != 1) return
        cameraQuadDraft = imageSuggestionQuad ?: if (imageCorrectionApplied) cameraQuadApplied else DocumentQuad()
        cameraScanLabel = if (imageSuggestionQuad != null) "检测到纸张 · 可手动微调" else "正在识别纸张边缘…"
        showCropEditor = true
        scanGeneration++
    }

    fun restoreOriginalImage() {
        imageCorrectionApplied = false
        cameraQuadDraft = DocumentQuad()
        cameraQuadApplied = DocumentQuad()
        showCropEditor = false
        preparedCache.clear()
        if (uris.size == 1) imageSuggestionGeneration++
    }

    fun chooseImageIntent() {
        // A photo captured for scanning is still a perfectly valid ordinary image. Reinterpret the
        // same source instead of forcing the user back through a picker just to change intent.
        if (sourceMode == SourceMode.CAMERA && uris.size == 1) {
            sourceMode = SourceMode.IMAGE
            adjustments = defaultAdjustments(SourceMode.IMAGE)
            imageCorrectionApplied = false
            imageSuggestionQuad = null
            cameraQuadDraft = DocumentQuad()
            cameraQuadApplied = DocumentQuad()
            showCropEditor = false
            preparedCache.clear()
            imageSuggestionGeneration++
            error = null
        } else if (sourceMode != SourceMode.IMAGE || uris.isEmpty()) {
            imagePicker.launch(arrayOf("image/*"))
        }
    }

    fun chooseScanIntent() {
        // If the user already has one gallery image open, "扫描" means "treat this same image as
        // paper". This keeps source acquisition separate from document treatment and avoids a
        // surprising camera launch. Multi-image/PDF/text sources start from the scan landing card.
        if (sourceMode == SourceMode.IMAGE && uris.size == 1) {
            sourceMode = SourceMode.CAMERA
            adjustments = defaultAdjustments(SourceMode.CAMERA)
            cameraQuadDraft = imageSuggestionQuad ?: DocumentQuad()
            cameraQuadApplied = DocumentQuad()
            imageSuggestionQuad = null
            imageCorrectionApplied = false
            cameraScanLabel = "正在识别纸张边缘…"
            showCropEditor = true
            preparedCache.clear()
            scanGeneration++
            error = null
        } else if (sourceMode != SourceMode.CAMERA) {
            sourceMode = SourceMode.CAMERA
            adjustments = defaultAdjustments(SourceMode.CAMERA)
            uris = emptyList()
            imageSuggestionQuad = null
            imageCorrectionApplied = false
            cameraQuadDraft = DocumentQuad()
            cameraQuadApplied = DocumentQuad()
            showCropEditor = false
            preparedCache.clear()
            error = null
        }
    }

    LaunchedEffect(historyId) {
        if (historyId != null) {
            val restored = vm.loadQuickSource(historyId)
            if (restored != null) {
                applySource(restored)
                if (uris.isEmpty() && sourceMode != SourceMode.TEXT && sourceMode != SourceMode.TODO) {
                    vm.historyRasterUri(historyId)?.let { fallback ->
                        uris = listOf(fallback)
                        sourceMode = SourceMode.IMAGE
                        adjustments = defaultAdjustments(SourceMode.IMAGE)
                        imageCorrectionApplied = false
                    }
                }
            } else {
                vm.historyRasterUri(historyId)?.let { fallback ->
                    sourceMode = SourceMode.IMAGE
                    uris = listOf(fallback)
                    adjustments = defaultAdjustments(SourceMode.IMAGE)
                    imageCorrectionApplied = false
                }
            }
        }
    }

    LaunchedEffect(Unit) {
        var loadedDraft = draftCandidate
        if (externalIntent == null && historyId == null && !draftChecked) {
            loadedDraft = vm.loadDraft()
            draftCandidate = loadedDraft
            draftChecked = true
        }
        if (externalIntent == null && historyId == null && loadedDraft == null && !pickerOpened) {
            pickerOpened = true
            when (sourceMode) {
                SourceMode.IMAGE -> imagePicker.launch(arrayOf("image/*"))
                SourceMode.PDF -> pdfPicker.launch(arrayOf("application/pdf"))
                SourceMode.CAMERA -> launchCamera()
                SourceMode.TEXT, SourceMode.TODO -> Unit
            }
        }
    }

    // Gallery images remain full-frame by default. A low-priority standard-detector pass only
    // decides whether to offer a "document correction" shortcut; it never opens the crop UI itself.
    LaunchedEffect(imageSuggestionGeneration) {
        val uri = uris.singleOrNull() ?: return@LaunchedEffect
        if (sourceMode != SourceMode.IMAGE || imageCorrectionApplied || showCropEditor) return@LaunchedEffect
        imageSuggestionScanning = true
        val result = runCatching {
            withContext(Dispatchers.IO) { QuickPrintRenderer.previewBitmap(context, uri, 1400) }.let { bmp ->
                try { scanner.detect(bmp, preferEnhanced = false) } finally { bmp.recycle() }
            }
        }.getOrNull()
        imageSuggestionQuad = result?.takeIf(::shouldSuggestDocumentCorrection)?.quad
        imageSuggestionScanning = false
    }

    // Automatic detection is asynchronous. The UI stays interactive and the user can always move the four corners.
    LaunchedEffect(sourceMode, uris, scanGeneration) {
        val uri = uris.firstOrNull() ?: return@LaunchedEffect
        if ((sourceMode != SourceMode.CAMERA && sourceMode != SourceMode.IMAGE) || !showCropEditor || uris.size != 1) return@LaunchedEffect
        cameraScanning = true
        val result = runCatching {
            withContext(Dispatchers.IO) { QuickPrintRenderer.previewBitmap(context, uri, 1800) }.let { bmp ->
                try { scanner.detect(bmp, preferEnhanced = true) } finally { bmp.recycle() }
            }
        }.getOrNull()
        if (result != null) {
            cameraQuadDraft = result.quad
            cameraScanLabel = when {
                result.confidence < .50f -> "没有可靠识别到纸张 · 请手动调整四角"
                result.engine == ScanEngine.ENHANCED -> "增强识别 · 可手动微调"
                else -> "标准识别（开源内置）· 可手动微调"
            }
        } else {
            cameraScanLabel = "标准框选 · 请手动调整四角"
        }
        cameraScanning = false
    }

    LaunchedEffect(sourceMode, text, todoTitle, todoItems, textStyle, uris, adjustments, pdfAutoCrop, cameraQuadApplied, imageCorrectionApplied, showCropEditor) {
        if ((sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.PDF || sourceMode == SourceMode.CAMERA) && uris.isEmpty()) {
            mono = null
            return@LaunchedEffect
        }
        if (sourceMode == SourceMode.TEXT && text.isBlank()) {
            mono = null
            return@LaunchedEffect
        }
        if (sourceMode == SourceMode.TODO && todoItems.isBlank()) {
            mono = null
            return@LaunchedEffect
        }
        if ((sourceMode == SourceMode.CAMERA || sourceMode == SourceMode.IMAGE) && showCropEditor) {
            mono = null
            return@LaunchedEffect
        }
        // Keep the current preview visible while a slider is moving. Only the 1-bit conversion is
        // repeated; single-image decode/perspective/fit is cached until source geometry changes.
        delay(90)
        rendering = true
        error = null
        val rendered = runCatching {
            withContext(Dispatchers.IO) {
                val outputRotation = adjustments.outputRotationDegrees()
                when (sourceMode) {
                    SourceMode.TEXT -> {
                        val base = QuickPrintRenderer.text(text, textStyle)
                        val prepared = try { QuickPrintRenderer.preparedImage(base, outputRotation, adjustments.scalePercent) } finally { base.recycle() }
                        try { QuickPrintRenderer.toMono(prepared, adjustments) } finally { prepared.recycle() }
                    }
                    SourceMode.TODO -> {
                        val base = QuickPrintRenderer.todo(todoTitle, todoItems, textStyle)
                        val prepared = try { QuickPrintRenderer.preparedImage(base, outputRotation, adjustments.scalePercent) } finally { base.recycle() }
                        try { QuickPrintRenderer.toMono(prepared, adjustments) } finally { prepared.recycle() }
                    }
                    SourceMode.IMAGE -> {
                        if (uris.size == 1) {
                            val quadKey = cameraQuadApplied.points().joinToString(";") { "${it.x},${it.y}" }
                            val key = "image|${uris.first()}|$imageCorrectionApplied|$quadKey|$outputRotation|${adjustments.scalePercent}"
                            val prepared = preparedCache.getOrCreate(key) {
                                if (imageCorrectionApplied) {
                                    val src = QuickPrintRenderer.previewBitmap(context, uris.first(), 2600)
                                    val corrected = try { scanner.perspective(src, cameraQuadApplied, cleanupEdges = true) } finally { src.recycle() }
                                    try { QuickPrintRenderer.preparedImage(corrected, outputRotation, adjustments.scalePercent) } finally { corrected.recycle() }
                                } else {
                                    QuickPrintRenderer.image(context, uris.first(), outputRotation, adjustments.scalePercent)
                                }
                            }
                            QuickPrintRenderer.toMono(prepared, adjustments)
                        } else {
                            val prepared = QuickPrintRenderer.images(context, uris, outputRotation, adjustments.scalePercent)
                            try { QuickPrintRenderer.toMono(prepared, adjustments) } finally { prepared.recycle() }
                        }
                    }
                    SourceMode.PDF -> {
                        val prepared = QuickPrintRenderer.pdf(
                            context,
                            uris.first(),
                            autoCropWhiteMargins = pdfAutoCrop,
                            rotationDegrees = outputRotation,
                            scalePercent = adjustments.scalePercent,
                        )
                        try { QuickPrintRenderer.toMono(prepared, adjustments) } finally { prepared.recycle() }
                    }
                    SourceMode.CAMERA -> {
                        val quadKey = cameraQuadApplied.points().joinToString(";") { "${it.x},${it.y}" }
                        val key = "camera|${uris.first()}|$quadKey|$outputRotation|${adjustments.scalePercent}"
                        val prepared = preparedCache.getOrCreate(key) {
                            val src = QuickPrintRenderer.previewBitmap(context, uris.first(), 2600)
                            val corrected = try { scanner.perspective(src, cameraQuadApplied, cleanupEdges = true) } finally { src.recycle() }
                            try { QuickPrintRenderer.preparedImage(corrected, outputRotation, adjustments.scalePercent) } finally { corrected.recycle() }
                        }
                        QuickPrintRenderer.toMono(prepared, adjustments)
                    }
                }
            }
        }
        rendered.onSuccess { mono = it }.onFailure { error = it.message ?: "内容处理失败" }
        rendering = false
    }

    fun leaveScreen() {
        if (savingDraft) return
        if (!hasMeaningfulDraft()) {
            onBack()
            return
        }
        savingDraft = true
        val draft = QuickPrintDraft(
            source = sourceSnapshot(includeDraftQuad = true),
            showCropEditor = showCropEditor,
            imageCorrectionApplied = imageCorrectionApplied,
        )
        vm.saveDraftAsync(draft) { result ->
            savingDraft = false
            android.widget.Toast.makeText(
                context,
                if (result.isSuccess) "编辑进度已保存，下次可以继续" else "草稿保存失败，本次内容尚未保存",
                android.widget.Toast.LENGTH_SHORT,
            ).show()
            onBack()
        }
    }

    BackHandler { leaveScreen() }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            TopAppBar(
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    scrolledContainerColor = MaterialTheme.colorScheme.surfaceContainer,
                ),
                title = { Text(when (sourceMode) {
                    SourceMode.CAMERA -> "扫描打印"
                    SourceMode.IMAGE -> "图片打印"
                    SourceMode.TODO -> "待办打印"
                    else -> "快速打印"
                }, fontWeight = FontWeight.SemiBold) },
                navigationIcon = {
                    IconButton(onClick = { leaveScreen() }, enabled = !savingDraft) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.cd_back))
                    }
                },
            )
        },
        bottomBar = {
            if ((sourceMode == SourceMode.CAMERA || sourceMode == SourceMode.IMAGE) && showCropEditor) {
                Surface(color = MaterialTheme.colorScheme.surface, tonalElevation = 3.dp) {
                    Row(
                        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp).navigationBarsPadding(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        if (sourceMode == SourceMode.IMAGE) {
                            OutlinedButton(
                                onClick = {
                                    cameraQuadDraft = if (imageCorrectionApplied) cameraQuadApplied else DocumentQuad()
                                    showCropEditor = false
                                },
                                modifier = Modifier.weight(1f),
                            ) { Text("取消") }
                        } else {
                            OutlinedButton(onClick = { scanGeneration++ }, enabled = !cameraScanning, modifier = Modifier.weight(1f)) {
                                Text(if (cameraScanning) "识别中" else "重新识别")
                            }
                        }
                        Button(
                            onClick = {
                                cameraQuadApplied = cameraQuadDraft.clamped()
                                if (sourceMode == SourceMode.IMAGE) {
                                    imageCorrectionApplied = !cameraQuadDraft.isEffectivelyFullImage()
                                    imageSuggestionQuad = null
                                }
                                showCropEditor = false
                            },
                            enabled = cameraQuadDraft.isReasonable(),
                            modifier = Modifier.weight(1.5f),
                        ) { Text(if (sourceMode == SourceMode.IMAGE) "应用校正" else "使用此区域") }
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
            if (!showCropEditor) {
                SourceSelector(
                    sourceMode = sourceMode,
                    onText = { switchMode(SourceMode.TEXT) },
                    onImage = { chooseImageIntent() },
                    onPdf = {
                        if (sourceMode == SourceMode.PDF && uris.isNotEmpty()) switchMode(SourceMode.PDF)
                        else pdfPicker.launch(arrayOf("application/pdf"))
                    },
                    onCamera = { chooseScanIntent() },
                    onTodo = { switchMode(SourceMode.TODO) },
                )
                draftCandidate?.let { draft ->
                    Spacer(Modifier.height(8.dp))
                    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                        Row(
                            Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Column(Modifier.weight(1f)) {
                                Text("有未完成的编辑", fontWeight = FontWeight.SemiBold)
                                Text("上次中途退出的内容已经保存，可以接着调，不用重新选图或拍照。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            TextButton(onClick = {
                                applySource(draft.source, draft)
                                draftCandidate = null
                                pickerOpened = true
                            }) { Text("继续") }
                            TextButton(onClick = {
                                vm.clearDraft()
                                draftCandidate = null
                            }) { Text("放弃") }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text("方向", style = MaterialTheme.typography.labelLarge)
                    FilterChip(selected = !adjustments.landscapePrint, onClick = { adjustments = adjustments.copy(landscapePrint = false) }, label = { Text("竖向") })
                    FilterChip(selected = adjustments.landscapePrint, onClick = { adjustments = adjustments.copy(landscapePrint = true) }, label = { Text("横向") })
                    Spacer(Modifier.weight(1f))
                }
                Spacer(Modifier.height(8.dp))

            } else {
                Text(cameraScanLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                Text(
                    if (sourceMode == SourceMode.IMAGE) "这是可选的文档校正。拖动四角确认纸张范围；取消不会改变原图。"
                    else "拖动四角或边中点修正范围；自动识别只是起点。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
            }
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
                        SourceMode.TODO -> {
                            OutlinedTextField(
                                value = todoTitle,
                                onValueChange = { todoTitle = it },
                                modifier = Modifier.fillMaxWidth(),
                                singleLine = true,
                                label = { Text("清单标题") },
                                placeholder = { Text("今日待办") },
                                shape = MaterialTheme.shapes.large,
                            )
                            Spacer(Modifier.height(8.dp))
                            OutlinedTextField(
                                value = todoItems,
                                onValueChange = { todoItems = it },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 6,
                                maxLines = 14,
                                label = { Text("待办事项") },
                                placeholder = { Text("每行一项，例如：\n整理错题\n背 30 个单词\n晚上跑步") },
                                shape = MaterialTheme.shapes.large,
                            )
                            Spacer(Modifier.height(6.dp))
                            TextStyleSummary(textStyle, title = "字体与排版", onClick = { showTextSettings = true })
                            Spacer(Modifier.height(4.dp))
                            Text("字号、字体、行距和对齐都可以调整；打印历史会保留可编辑源内容。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        SourceMode.IMAGE -> {
                            if (uris.isEmpty()) {
                                SelectedSourceCard(
                                    title = "选择图片",
                                    subtitle = "照片、截图、插画按原图进入；不会擅自裁边",
                                    iconRes = R.drawable.ic_quick_image,
                                    action = "选择",
                                    onAction = { imagePicker.launch(arrayOf("image/*")) },
                                )
                            } else if (showCropEditor && uris.size == 1) {
                                Text("文档校正", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(5.dp))
                                Text(cameraScanLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                Text("只有点“应用校正”后才会改变图片；普通图片可以直接取消。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(8.dp))
                                PhotoCropEditor(uri = uris.first(), quad = cameraQuadDraft, onQuadChange = { cameraQuadDraft = it })
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { cameraQuadDraft = fullImageQuad() }) { Text("整张图片") }
                                    TextButton(onClick = { scanGeneration++ }, enabled = !cameraScanning) { Text(if (cameraScanning) "识别中" else "重新识别") }
                                }
                            } else {
                                SelectedSourceCard(
                                    title = if (uris.size > 1) "已选择 ${uris.size} 张图片" else displayName(context, uris.firstOrNull()) ?: "图片",
                                    subtitle = when {
                                        uris.size > 1 -> "多图会按顺序连续打印，每张都保持原图边界"
                                        imageCorrectionApplied -> "已应用纸张校正；随时可以恢复原图"
                                        else -> "按原图打印，不会自动裁边或改变构图"
                                    },
                                    iconRes = R.drawable.ic_quick_image,
                                    action = "更换",
                                    onAction = { imagePicker.launch(arrayOf("image/*")) },
                                )
                                if (uris.size == 1) {
                                    Spacer(Modifier.height(2.dp))
                                    if (!imageCorrectionApplied && imageSuggestionQuad != null) {
                                        OutlinedCard(
                                            modifier = Modifier.fillMaxWidth(),
                                            shape = RoundedCornerShape(16.dp),
                                            colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
                                        ) {
                                            Row(
                                                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 9.dp),
                                                verticalAlignment = Alignment.CenterVertically,
                                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                            ) {
                                                Column(Modifier.weight(1f)) {
                                                    Text("看起来像拍摄的纸张", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium)
                                                    Text("需要的话可以拉正透视；不处理也完全没关系。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                                TextButton(onClick = { openImageDocumentCorrection() }) { Text("校正") }
                                            }
                                        }
                                    }
                                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                                        if (imageCorrectionApplied) {
                                            TextButton(onClick = { openImageDocumentCorrection() }) { Text("重新校正") }
                                            TextButton(onClick = { restoreOriginalImage() }) { Text("恢复原图") }
                                        } else {
                                            TextButton(onClick = { openImageDocumentCorrection() }) { Text("文档校正") }
                                            if (imageSuggestionScanning) Text("正在判断是否需要校正…", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                    }
                                }
                            }
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
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                if (uris.isNotEmpty()) {
                                    TextButton(
                                        enabled = !savingDocument,
                                        onClick = {
                                            val uri = uris.first()
                                            savingDocument = true
                                            vm.savePdf(uri, displayName(context, uri)) { result ->
                                                savingDocument = false
                                                android.widget.Toast.makeText(
                                                    context,
                                                    if (result.isSuccess) "已保存到应用" else "保存失败",
                                                    android.widget.Toast.LENGTH_SHORT,
                                                ).show()
                                            }
                                        },
                                    ) { Text(if (savingDocument) "保存中…" else "保存到应用") }
                                }
                                TextButton(onClick = { showSavedDocuments = true }) {
                                    Text(if (savedDocuments.isEmpty()) "已保存" else "已保存 ${savedDocuments.size}")
                                }
                            }
                        }
                        SourceMode.CAMERA -> {
                            if (uris.isEmpty()) {
                                SelectedSourceCard(
                                    title = "扫描纸张",
                                    subtitle = "试卷、小票、讲义、手写纸张：自动找边后再由你确认",
                                    iconRes = R.drawable.ic_camera,
                                    action = "拍照",
                                    onAction = { launchCamera() },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                                    OutlinedButton(
                                        onClick = { scanImagePicker.launch(arrayOf("image/*")) },
                                        modifier = Modifier.fillMaxWidth(),
                                    ) { Text("从相册选择纸张") }
                                }
                            } else if (showCropEditor) {
                                Text("确认纸张四角", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                                Spacer(Modifier.height(4.dp))
                                Text(cameraScanLabel, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
                                Text("识别不必完美：拖动四个圆点即可修正，最后以你看到的范围为准。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(Modifier.height(10.dp))
                                PhotoCropEditor(
                                    uri = uris.first(),
                                    quad = cameraQuadDraft,
                                    onQuadChange = { cameraQuadDraft = it },
                                )
                                Spacer(Modifier.height(8.dp))
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    TextButton(onClick = { cameraQuadDraft = DocumentQuad() }) { Text("恢复默认") }
                                    TextButton(onClick = { launchCamera() }) { Text("重新拍摄") }
                                    TextButton(onClick = { scanImagePicker.launch(arrayOf("image/*")) }) { Text("换相册图片") }
                                }
                                TextButton(onClick = { chooseImageIntent() }) { Text("不是文档？按原图打印") }
                            } else {
                                SelectedSourceCard(
                                    title = "纸张已校正",
                                    subtitle = "已经拉正透视，下方就是接近实际热敏打印的效果",
                                    iconRes = R.drawable.ic_camera,
                                    action = "重拍",
                                    onAction = { launchCamera() },
                                )
                                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                                    TextButton(onClick = { cameraQuadDraft = cameraQuadApplied; showCropEditor = true }) { Text("调整四角") }
                                    TextButton(onClick = { scanGeneration++; cameraQuadDraft = cameraQuadApplied; showCropEditor = true }) { Text("重新识别") }
                                    TextButton(onClick = { scanImagePicker.launch(arrayOf("image/*")) }) { Text("换图片") }
                                }
                                TextButton(onClick = { chooseImageIntent() }) { Text("改为原图打印") }
                            }
                        }

                    }
                }
            }

            if ((sourceMode == SourceMode.CAMERA || sourceMode == SourceMode.IMAGE) && !showCropEditor && uris.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(if (sourceMode == SourceMode.CAMERA) "扫描效果" else "图片效果", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Spacer(Modifier.weight(1f))
                    if (rendering) CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    else mono?.let { Text("约 ${(it.height + 7) / 8} mm", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    mono != null -> MonoPaperPreview(image = mono!!, minViewportHeight = 300.dp, maxViewportHeight = 620.dp)
                    rendering -> PreviewPlaceholder()
                    else -> EmptyPreviewCard(sourceMode)
                }
                error?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                }
                Spacer(Modifier.height(10.dp))
                Text("快速优化", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    listOf(
                        PaperPreset.ORIGINAL to "原图",
                        PaperPreset.BRIGHTEN to "净化",
                        PaperPreset.SHARPEN to "清晰",
                        PaperPreset.DOCUMENT to "黑白文档",
                        PaperPreset.GRAYSCALE to "灰度",
                    ).forEach { (preset, label) ->
                        FilterChip(
                            selected = adjustments.paperPreset == preset,
                            onClick = { adjustments = adjustments.copy(paperPreset = preset) },
                            label = { Text(label, maxLines = 1) },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    OutlinedButton(
                        onClick = { adjustments = adjustments.copy(rotationDegrees = (adjustments.rotationDegrees + 90) % 360) },
                        modifier = Modifier.weight(1f),
                    ) { Text("旋转 90°") }
                    OutlinedButton(onClick = { showAdjustments = true }, modifier = Modifier.weight(1f)) { Text("更多调整") }
                }
                Text(
                    "先选最接近的效果即可；阈值、对比度、去红蓝笔等细项放在“更多调整”里。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            if (sourceMode == SourceMode.PDF && !showCropEditor && uris.isNotEmpty()) {
                Spacer(Modifier.height(8.dp))
                RasterModeSelector(
                    mode = adjustments.mode,
                    onMode = { adjustments = adjustments.copy(mode = it) },
                )
            }

            if (sourceMode != SourceMode.CAMERA && sourceMode != SourceMode.IMAGE) {
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
                    mono != null -> MonoPaperPreview(image = mono!!, minViewportHeight = 260.dp, maxViewportHeight = 560.dp)
                    rendering -> PreviewPlaceholder()
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

    if (showAdjustments && (sourceMode == SourceMode.PDF || sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.CAMERA)) {
        ModalBottomSheet(onDismissRequest = { showAdjustments = false }) {
            Column(
                Modifier.padding(horizontal = 20.dp).navigationBarsPadding().verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(if (sourceMode == SourceMode.PDF) "打印调整" else "图片调整", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                if (sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.CAMERA) {
                    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf(
                            PaperPreset.ORIGINAL to "原图", PaperPreset.BRIGHTEN to "净化",
                            PaperPreset.SHARPEN to "清晰", PaperPreset.DOCUMENT to "黑白文档", PaperPreset.GRAYSCALE to "灰度",
                        ).forEach { (preset, label) ->
                            FilterChip(selected = adjustments.paperPreset == preset, onClick = { adjustments = adjustments.copy(paperPreset = preset) }, label = { Text(label) })
                        }
                    }
                }
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
                    removeRedInk = if (sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.CAMERA) adjustments.removeRedInk else null,
                    removeBlueInk = if (sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.CAMERA) adjustments.removeBlueInk else null,
                    onRemoveRedInk = if (sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.CAMERA) ({ v -> adjustments = adjustments.copy(removeRedInk = v) }) else null,
                    onRemoveBlueInk = if (sourceMode == SourceMode.IMAGE || sourceMode == SourceMode.CAMERA) ({ v -> adjustments = adjustments.copy(removeBlueInk = v) }) else null,
                )
                Button(onClick = { showAdjustments = false }, modifier = Modifier.fillMaxWidth()) { Text("完成") }
                Spacer(Modifier.height(12.dp))
            }
        }
    }

    if (showSavedDocuments) {
        ModalBottomSheet(onDismissRequest = { showSavedDocuments = false }) {
            Column(
                Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 20.dp).padding(bottom = 20.dp),
            ) {
                Text("已保存的文档", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
                Text("保存后可随时再次打开。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(10.dp))
                if (savedDocuments.isEmpty()) {
                    Text("还没有保存文档", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(vertical = 24.dp))
                } else {
                    LazyColumn(modifier = Modifier.heightIn(max = 420.dp)) {
                        items(savedDocuments, key = { it.id }) { doc ->
                            SavedDocumentRow(
                                document = doc,
                                onOpen = {
                                    uris = listOf(vm.uriFor(doc))
                                    sourceMode = SourceMode.PDF
                                    adjustments = defaultAdjustments(SourceMode.PDF)
                                    showSavedDocuments = false
                                },
                                onDelete = { vm.deleteDocument(doc.id) },
                            )
                        }
                    }
                }
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
                vm.recordPrinted(
                    title = if (sourceMode == SourceMode.TODO) todoTitle.ifBlank { "待办清单" } else quickHistoryTitle(context, sourceMode, text, uris),
                    image = image, copies = copies,
                    source = sourceSnapshot(),
                )
                vm.clearDraft()
                draftCandidate = null
            },
            onOpenPrinterSettings = {
                showPrint = false
                onOpenPrinterSettings()
            },
        )
    }
}

@Composable
private fun SavedDocumentRow(document: SavedDocument, onOpen: () -> Unit, onDelete: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(document.name, maxLines = 1, overflow = TextOverflow.Ellipsis, fontWeight = FontWeight.Medium)
            Text("${(document.sizeBytes / 1024).coerceAtLeast(1)} KB", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        TextButton(onClick = onOpen) { Text("打开") }
        TextButton(onClick = onDelete) { Text("删除") }
    }
}

@Composable
private fun SourceSelector(
    sourceMode: SourceMode,
    onText: () -> Unit,
    onImage: () -> Unit,
    onPdf: () -> Unit,
    onCamera: () -> Unit,
    onTodo: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterChip(selected = sourceMode == SourceMode.IMAGE, onClick = onImage, label = { Text("图片") }, modifier = Modifier.weight(1f))
        FilterChip(selected = sourceMode == SourceMode.PDF, onClick = onPdf, label = { Text("PDF") }, modifier = Modifier.weight(1f))
        FilterChip(selected = sourceMode == SourceMode.TEXT, onClick = onText, label = { Text("文字") }, modifier = Modifier.weight(1f))
        FilterChip(selected = sourceMode == SourceMode.CAMERA, onClick = onCamera, label = { Text("扫描") }, modifier = Modifier.weight(1f))
        FilterChip(selected = sourceMode == SourceMode.TODO, onClick = onTodo, label = { Text("待办") }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TextStyleSummary(style: QuickTextStyle, title: String = "文字排版", onClick: () -> Unit) {
    OutlinedCard(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
                Text(
                    "字号 ${style.fontSizePx} · ${fontLabel(style.font)} · 行距 ${style.lineSpacingPercent}% · ${alignLabel(style.align)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text("调整", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
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
                    SourceMode.CAMERA -> "扫描纸张后预览校正与打印效果"
                    SourceMode.TODO -> "输入待办事项后，这里会生成勾选清单"
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
                    } else if (sourceMode == SourceMode.TODO) {
                        Text("${fontLabel(textStyle.font)} · 字号 ${textStyle.fontSizePx} · ${alignLabel(textStyle.align)}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                        Text("行距 ${textStyle.lineSpacingPercent}%", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    } else {
                        Text("${modeLabel(adjustments.mode)} · ${adjustmentSummary(adjustments)}", style = MaterialTheme.typography.labelLarge, maxLines = 1)
                    }
                }
                if (sourceMode != SourceMode.CAMERA && sourceMode != SourceMode.IMAGE) {
                    OutlinedButton(onClick = if (sourceMode == SourceMode.TEXT || sourceMode == SourceMode.TODO) onTextAdjust else onAdjust, enabled = enabled) {
                        Text(if (sourceMode == SourceMode.TEXT || sourceMode == SourceMode.TODO) "字体" else "调整")
                    }
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
    SourceMode.CAMERA -> "扫描打印"
    SourceMode.TODO -> "待办清单"
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
        mode.equals("todo", true) -> SourceMode.TODO
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

private fun cameraOutputReadable(context: Context, uri: Uri): Boolean = runCatching {
    context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
        pfd.statSize != 0L
    } == true
}.getOrDefault(false)

private fun newCameraUri(context: Context): Uri {
    val dir = File(context.cacheDir, "camera").apply { mkdirs() }
    val file = File(dir, "capture_${System.currentTimeMillis()}.jpg")
    return FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
}

/** Small in-memory cache so slider changes do not decode and perspective-warp the same photo again. */
private class PreparedBitmapCache {
    private var key: String? = null
    private var bitmap: Bitmap? = null

    suspend fun getOrCreate(newKey: String, loader: suspend () -> Bitmap): Bitmap {
        bitmap?.takeIf { key == newKey && !it.isRecycled }?.let { return it }
        val created = loader()
        bitmap?.takeIf { it !== created && !it.isRecycled }?.recycle()
        key = newKey
        bitmap = created
        return created
    }

    @Synchronized
    fun clear() {
        bitmap?.takeIf { !it.isRecycled }?.recycle()
        bitmap = null
        key = null
    }
}
