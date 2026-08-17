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
import io.github.soulxyz.xyprt.data.QuickPrintHistorySource
import io.github.soulxyz.xyprt.data.QuickPrintDraft
import io.github.soulxyz.xyprt.data.PrintHistoryEntry
import io.github.soulxyz.xyprt.scanner.DocumentQuad
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import io.github.soulxyz.xyprt.printer.MonoImage
import io.github.soulxyz.xyprt.render.MonoConverter
import androidx.core.content.FileProvider
import java.io.File
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Records quick prints and owns the app-local PDF shelf. */
class QuickPrintViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as App).container
    private val history = container.historyRepository
    private val savedDocs = container.savedDocuments
    private val templates = container.templateRepository

    val documents: StateFlow<List<SavedDocument>> = savedDocs.documents

    fun recordPrinted(title: String, image: MonoImage, copies: Int, source: QuickPrintHistorySource? = null) {
        viewModelScope.launch {
            history.recordRaster(
                title = title.ifBlank { "快速打印" }, image = image, copies = copies,
                sourceType = if (source != null) "quick" else null,
                sourceJson = source?.let { container.json.encodeToString(it) },
            )
        }
    }

    suspend fun loadQuickSource(historyId: Long): QuickPrintHistorySource? {
        val entry = history.getAll().firstOrNull { it.id == historyId } ?: return null
        val raw = entry.sourceJson ?: return null
        if (entry.sourceType == "quick") return runCatching { container.json.decodeFromString<QuickPrintHistorySource>(raw) }.getOrNull()
        if (entry.sourceType == "todo") {
            val old = runCatching { container.json.decodeFromString<TodoHistorySource>(raw) }.getOrNull() ?: return null
            return QuickPrintHistorySource(mode = "TODO", todoTitle = old.title, todoItems = old.items, fontSizePx = old.fontSizePx, lineSpacingPercent = old.lineSpacingPercent, font = old.font, align = old.align)
        }
        return null
    }

    suspend fun loadHistoryEntry(historyId: Long): PrintHistoryEntry? = history.getAll().firstOrNull { it.id == historyId }

    suspend fun historyRasterUri(historyId: Long): Uri? {
        val entry = loadHistoryEntry(historyId) ?: return null
        val mono = io.github.soulxyz.xyprt.data.HistoryRepository.decodeRaster(entry) ?: return null
        return kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching {
                val context = getApplication<Application>()
                val dir = File(context.cacheDir, "history_edit").apply { mkdirs() }
                val file = File(dir, "history_${historyId}.png")
                val bitmap = MonoConverter.toBitmap(mono)
                try { file.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } } finally { bitmap.recycle() }
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            }.getOrNull()
        }
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
                val name = LocalDateTime.now().format(DateTimeFormatter.ofPattern("扫描 yyyy-MM-dd HH:mm"))
                templates.createFrom(
                    name = name,
                    spec = LabelSpec(lengthMm = lengthMm, autoLength = false),
                    elements = listOf(element),
                    defaultName = "扫描文档",
                ).id
            }
            onResult(result)
        }
    }


    suspend fun loadDraft(): QuickPrintDraft? = withContext(Dispatchers.IO) {
        val dir = File(getApplication<Application>().filesDir, "quick_print_drafts/current")
        val file = File(dir, "draft.json")
        if (!file.isFile) return@withContext null
        runCatching { container.json.decodeFromString<QuickPrintDraft>(file.readText()) }.getOrNull()
    }

    /**
     * Save an unfinished quick-print session. File-backed sources are copied into app-private
     * storage so camera cache eviction, share URI revocation or gallery permission changes do not
     * silently destroy the draft.
     */
    suspend fun saveDraft(draft: QuickPrintDraft): Result<Unit> = withContext(Dispatchers.IO) {
        runCatching {
            val context = getApplication<Application>()
            val root = File(context.filesDir, "quick_print_drafts").apply { mkdirs() }
            val current = File(root, "current")
            val temp = File(root, "current.new").apply { deleteRecursively(); mkdirs() }
            val copied = draft.source.uris.mapIndexed { index, raw ->
                val uri = Uri.parse(raw)
                val mime = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
                val ext = when {
                    draft.source.mode == "PDF" -> "pdf"
                    mime.contains("pdf", true) -> "pdf"
                    mime.contains("png", true) -> "png"
                    mime.contains("webp", true) -> "webp"
                    else -> "jpg"
                }
                val out = File(temp, "source_${index}.$ext")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    out.outputStream().use { output -> input.copyTo(output) }
                } ?: error("无法读取草稿来源")
                out
            }
            current.deleteRecursively()
            check(temp.renameTo(current)) { "无法保存草稿目录" }
            val ownedUris = copied.map { old ->
                val finalFile = File(current, old.name)
                FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", finalFile).toString()
            }
            val stable = draft.copy(source = draft.source.copy(uris = ownedUris), savedAt = System.currentTimeMillis())
            File(current, "draft.json").writeText(container.json.encodeToString(stable))
        }
    }

    fun clearDraft() {
        viewModelScope.launch(Dispatchers.IO) {
            File(getApplication<Application>().filesDir, "quick_print_drafts").deleteRecursively()
        }
    }

    fun saveDraftAsync(draft: QuickPrintDraft, onResult: (Result<Unit>) -> Unit) {
        viewModelScope.launch { onResult(saveDraft(draft)) }
    }

    fun deleteDocument(id: String) { viewModelScope.launch { savedDocs.delete(id) } }
    fun uriFor(document: SavedDocument): Uri = savedDocs.uriFor(document)
}
