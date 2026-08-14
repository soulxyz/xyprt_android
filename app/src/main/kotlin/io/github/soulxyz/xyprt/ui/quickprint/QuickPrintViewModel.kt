package io.github.soulxyz.xyprt.ui.quickprint

import android.app.Application
import android.net.Uri
import io.github.soulxyz.xyprt.model.ImageElement
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.ui.editor.ImageImport
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlin.math.ceil
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.data.SavedDocument
import io.github.soulxyz.xyprt.data.TodoHistorySource
import io.github.soulxyz.xyprt.scanner.DocumentQuad
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import io.github.soulxyz.xyprt.printer.MonoImage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Records quick prints and owns the app-local PDF shelf. */
class QuickPrintViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as App).container
    private val history = container.historyRepository
    private val savedDocs = container.savedDocuments
    private val templates = container.templateRepository

    val documents: StateFlow<List<SavedDocument>> = savedDocs.documents

    fun recordPrinted(title: String, image: MonoImage, copies: Int, todo: TodoHistorySource? = null) {
        viewModelScope.launch {
            history.recordRaster(
                title = title.ifBlank { "快速打印" }, image = image, copies = copies,
                sourceType = if (todo != null) "todo" else null,
                sourceJson = todo?.let { container.json.encodeToString(it) },
            )
        }
    }

    suspend fun loadTodoSource(historyId: Long): TodoHistorySource? = history.getAll().firstOrNull { it.id == historyId && it.sourceType == "todo" }?.sourceJson?.let {
        runCatching { container.json.decodeFromString<TodoHistorySource>(it) }.getOrNull()
    }

    fun savePdf(uri: Uri, displayName: String?, onResult: (Result<SavedDocument>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { savedDocs.saveFromUri(uri, displayName) })
        }
    }


    /** Preserve an unfinished camera job as a normal free-layout document instead of discarding it. */
    fun saveCameraDraft(
        uri: Uri,
        quad: DocumentQuad,
        adjustments: QuickImageAdjustments,
        onResult: (Result<String>) -> Unit,
    ) {
        viewModelScope.launch {
            val result = runCatching {
                val context = getApplication<Application>()
                val source = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { QuickPrintRenderer.previewBitmap(context, uri, 2600) }
                val corrected = try { container.scanner.perspective(source, quad, cleanupEdges = true) } finally { source.recycle() }
                val bitmap = if (maxOf(corrected.width, corrected.height) > 1200) {
                    val scale = 1200f / maxOf(corrected.width, corrected.height)
                    android.graphics.Bitmap.createScaledBitmap(corrected, (corrected.width * scale).toInt().coerceAtLeast(1), (corrected.height * scale).toInt().coerceAtLeast(1), true).also { corrected.recycle() }
                } else corrected
                val loaded = try { ImageImport.fromBitmap(bitmap) } finally { bitmap.recycle() }
                val width = ((LabelSpec.PRINT_WIDTH_PX - 8) * adjustments.scalePercent / 100f)
                    .coerceIn(64f, LabelSpec.PRINT_WIDTH_PX * 1.8f)
                val displayH = if (loaded.width > 0) width * loaded.height / loaded.width else width
                val lengthMm = ceil((24f + displayH + 24f) / io.github.soulxyz.xyprt.printer.Protocol.DOTS_PER_MM)
                    .toInt().coerceIn(LabelSpec.MIN_LENGTH_MM, LabelSpec.MAX_LENGTH_MM)
                val element = ImageElement(
                    id = UUID.randomUUID().toString(),
                    x = ((LabelSpec.PRINT_WIDTH_PX - width) / 2f).coerceAtLeast(0f),
                    y = 24f,
                    rotation = adjustments.rotationDegrees,
                    pngBase64 = loaded.pngBase64,
                    srcWidth = loaded.width,
                    srcHeight = loaded.height,
                    widthPx = width,
                    dither = adjustments.mode,
                    invert = adjustments.invert,
                    threshold = adjustments.threshold,
                    contrast = adjustments.contrast,
                    outlineSensitivity = adjustments.outlineSensitivity,
                    outlineThickness = adjustments.outlineThickness,
                    outlineMethod = adjustments.outlineMethod,
                    outlineSmooth = adjustments.outlineSmooth,
                    removeRedInk = adjustments.removeRedInk,
                    removeBlueInk = adjustments.removeBlueInk,
                )
                val name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("拍照 yyyy-MM-dd HH:mm"))
                templates.createFrom(
                    name = name,
                    spec = LabelSpec(lengthMm = lengthMm, autoLength = false),
                    elements = listOf(element),
                    defaultName = "拍照文档",
                ).id
            }
            onResult(result)
        }
    }

    fun deleteDocument(id: String) { viewModelScope.launch { savedDocs.delete(id) } }
    fun uriFor(document: SavedDocument): Uri = savedDocs.uriFor(document)
}
