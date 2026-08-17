package io.github.soulxyz.xyprt.ui.print

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.model.LabelTemplate
import io.github.soulxyz.xyprt.model.Placeholders
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.printer.estimatePrintedLengthMm
import io.github.soulxyz.xyprt.render.LabelRenderer
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class TemplatePrintViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as App).container
    private val manager = container.printerManager
    private val templateRepo = container.templateRepository
    private val historyRepo = container.historyRepository

    val printerState = manager.state
    val savedPrinter = container.settings.savedPrinter
    val feedBeforeDots = container.settings.printFeedBeforeDots
    val feedAfterDots = container.settings.printFeedAfterDots

    fun connect() = manager.connectSavedActive()

    fun cancelConnect() = manager.cancelConnect()

    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done = _done.asStateFlow()

    fun print(
        template: LabelTemplate,
        media: MediaType,
        copies: Int,
        answers: Map<String, String>,
        feedBeforeDots: Int,
        feedAfterDots: Int,
    ) {
        if (_working.value) return
        _working.value = true
        _error.value = null
        _done.value = false
        viewModelScope.launch {
            try {
                val hasCounter = Placeholders.containsCounter(template.elements)
                val now = Date()
                val dateText = SimpleDateFormat("yyyy-MM-dd", Locale.SIMPLIFIED_CHINESE).format(now)
                val timeText = SimpleDateFormat("HH:mm", Locale.SIMPLIFIED_CHINESE).format(now)

                val resolvedPerCopy = List(copies) { index ->
                    Placeholders.resolve(
                        template.elements,
                        Placeholders.Context(
                            dateText = dateText,
                            timeText = timeText,
                            counter = template.counterValue + index,
                            answers = answers,
                        )
                    )
                }
                val reanchored = resolvedPerCopy.map { LabelRenderer.reanchor(template.elements, it) }
                val images = reanchored.map { LabelRenderer.renderMono(template.spec, it) }

                container.settings.savePrintSpacing(feedBeforeDots, feedAfterDots)
                manager.printJobs(images, media, feedBeforeDots, feedAfterDots)

                if (hasCounter) {
                    templateRepo.setCounter(template.id, template.counterValue + copies)
                }
                val totalLengthMm = images.sumOf { image ->
                    estimatePrintedLengthMm(
                        image = image,
                        media = media,
                        feedBeforeDots = feedBeforeDots,
                        feedAfterDots = feedAfterDots,
                    )
                }
                historyRepo.record(
                    templateId = template.id,
                    templateName = template.name,
                    spec = template.spec.copy(media = media),
                    resolvedElements = reanchored.first(),
                    copies = copies,
                    printedLengthMm = totalLengthMm,
                )
                container.printStats.recordSuccessfulPrint(copies, totalLengthMm)
                _done.value = true
            } catch (c: CancellationException) {
                throw c
            } catch (t: Throwable) {
                _error.value = t.message ?: "Print failed"
            } finally {
                _working.value = false
            }
        }
    }

    fun reset() {
        _error.value = null
        _done.value = false
    }
}
