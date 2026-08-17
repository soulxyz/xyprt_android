package io.github.soulxyz.xyprt.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.data.HistoryRepository
import io.github.soulxyz.xyprt.data.PrintHistoryEntry
import io.github.soulxyz.xyprt.data.QuickPrintHistorySource
import io.github.soulxyz.xyprt.data.TodoHistorySource
import io.github.soulxyz.xyprt.model.ImageElement
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.render.MonoConverter
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.printer.MonoImage
import io.github.soulxyz.xyprt.printer.estimatePrintedLengthMm
import io.github.soulxyz.xyprt.ui.editor.ImageImport
import java.util.UUID
import kotlin.math.ceil
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.decodeFromString

class HistoryViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as App).container
    private val repo = container.historyRepository
    private val templates = container.templateRepository

    val entries = repo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun delete(id: Long) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun clear() {
        viewModelScope.launch { repo.clear() }
    }

    fun recordReprint(
        entry: PrintHistoryEntry,
        image: MonoImage,
        copies: Int,
        media: MediaType,
        feedBeforeDots: Int,
        feedAfterDots: Int,
    ) {
        viewModelScope.launch {
            val totalLengthMm = estimatePrintedLengthMm(
                image = image,
                media = media,
                copies = copies,
                feedBeforeDots = feedBeforeDots,
                feedAfterDots = feedAfterDots,
            )
            if (entry.rasterBase64 != null) {
                repo.recordRaster(
                    title = entry.templateName,
                    image = image,
                    copies = copies,
                    sourceType = entry.sourceType,
                    sourceJson = entry.sourceJson,
                    printedLengthMm = totalLengthMm,
                )
            } else {
                repo.record(
                    templateId = entry.templateId,
                    templateName = entry.templateName,
                    spec = entry.spec.copy(media = media),
                    resolvedElements = entry.elements,
                    copies = copies,
                    printedLengthMm = totalLengthMm,
                )
            }
            container.printStats.recordSuccessfulPrint(copies, totalLengthMm)
        }
    }

    /**
     * Open a quick-print history item in the free-layout editor.
     * Text and todo history remain real editable elements; raster fallback is only used for image/PDF/scan
     * history or older records that do not contain an editable source snapshot.
     */
    fun convertQuickToTemplate(id: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val templateId = runCatching {
                val entry = repo.getAll().firstOrNull { it.id == id } ?: error("history missing")
                val source = editableSource(entry)
                if (source != null && (source.mode == "TEXT" || source.mode == "TODO")) {
                    createEditableTemplate(entry, source)
                } else {
                    createRasterTemplate(entry)
                }
            }.getOrNull()
            onDone(templateId)
        }
    }

    private fun editableSource(entry: PrintHistoryEntry): QuickPrintHistorySource? {
        val raw = entry.sourceJson ?: return null
        return when (entry.sourceType) {
            "quick" -> runCatching { container.json.decodeFromString<QuickPrintHistorySource>(raw) }.getOrNull()
            "todo" -> runCatching { container.json.decodeFromString<TodoHistorySource>(raw) }.getOrNull()?.let { old ->
                QuickPrintHistorySource(
                    mode = "TODO",
                    todoTitle = old.title,
                    todoItems = old.items,
                    fontSizePx = old.fontSizePx,
                    lineSpacingPercent = old.lineSpacingPercent,
                    font = old.font,
                    align = old.align,
                )
            }
            else -> null
        }
    }

    private suspend fun createEditableTemplate(entry: PrintHistoryEntry, source: QuickPrintHistorySource): String {
        val layout = QuickHistoryLayoutConverter.convert(source) ?: error("history is not editable")
        return templates.createFrom(
            name = "${entry.templateName} · 自由排版",
            spec = LabelSpec(lengthMm = layout.lengthMm, autoLength = false),
            elements = layout.elements,
            defaultName = layout.defaultName,
        ).id
    }

    private suspend fun createRasterTemplate(entry: PrintHistoryEntry): String {
        val mono = HistoryRepository.decodeRaster(entry) ?: error("not quick raster")
        val bitmap = MonoConverter.toBitmap(mono)
        val loaded = try { ImageImport.fromBitmap(bitmap) } finally { bitmap.recycle() }
        val width = (LabelSpec.PRINT_WIDTH_PX - 8).toFloat()
        val displayH = width * loaded.height / loaded.width.toFloat().coerceAtLeast(1f)
        val lengthMm = ceil((displayH + 32f) / io.github.soulxyz.xyprt.printer.Protocol.DOTS_PER_MM).toInt()
            .coerceIn(LabelSpec.MIN_LENGTH_MM, LabelSpec.MAX_LENGTH_MM)
        val element = ImageElement(
            id = UUID.randomUUID().toString(), x = 4f, y = 16f,
            pngBase64 = loaded.pngBase64, srcWidth = loaded.width, srcHeight = loaded.height,
            widthPx = width,
        )
        return templates.createFrom(
            name = "${entry.templateName} · 自由排版",
            spec = LabelSpec(lengthMm = lengthMm, autoLength = false),
            elements = listOf(element),
            defaultName = "历史打印",
        ).id
    }

}
