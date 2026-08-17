package io.github.soulxyz.xyprt.ui.history

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.soulxyz.xyprt.App
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import io.github.soulxyz.xyprt.data.HistoryRepository
import io.github.soulxyz.xyprt.model.ImageElement
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.ui.editor.ImageImport
import io.github.soulxyz.xyprt.render.MonoConverter
import java.util.UUID
import kotlin.math.ceil

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

    fun convertQuickToTemplate(id: Long, onDone: (String?) -> Unit) {
        viewModelScope.launch {
            val templateId = runCatching {
                val entry = repo.getAll().firstOrNull { it.id == id } ?: error("history missing")
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
                templates.createFrom(
                    name = "${entry.templateName} · 自由排版",
                    spec = LabelSpec(lengthMm = lengthMm, autoLength = false),
                    elements = listOf(element),
                    defaultName = "历史打印",
                ).id
            }.getOrNull()
            onDone(templateId)
        }
    }
}
