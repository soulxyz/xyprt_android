package io.github.soulxyz.xyprt.ui.print

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.printer.MonoImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

class PrintViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as App).container
    private val manager = container.printerManager

    val printerState = manager.state
    val savedPrinter = container.settings.savedPrinter
    val feedBeforeDots = container.settings.printFeedBeforeDots
    val feedAfterDots = container.settings.printFeedAfterDots

    fun connect() {
        _error.value = null
        manager.connectSavedActive()
    }

    fun refreshPrinter() {
        _error.value = null
        manager.refreshSavedActive()
    }

    private val _working = MutableStateFlow(false)
    val working = _working.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    private val _done = MutableStateFlow(false)
    val done = _done.asStateFlow()

    fun print(image: MonoImage, media: MediaType, copies: Int, feedBeforeDots: Int, feedAfterDots: Int) {
        if (_working.value) return
        _working.value = true
        _error.value = null
        _done.value = false
        viewModelScope.launch {
            try {
                container.settings.savePrintSpacing(feedBeforeDots, feedAfterDots)
                manager.print(image, media, copies, feedBeforeDots, feedAfterDots)
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

    fun clearError() {
        _error.value = null
    }

    fun reset() {
        _error.value = null
        _done.value = false
    }
}
