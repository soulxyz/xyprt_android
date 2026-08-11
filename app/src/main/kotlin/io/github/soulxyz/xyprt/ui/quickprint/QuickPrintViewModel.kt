package io.github.soulxyz.xyprt.ui.quickprint

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.printer.MonoImage
import kotlinx.coroutines.launch

/** Records successful quick prints into the same history repository used by document printing. */
class QuickPrintViewModel(app: Application) : AndroidViewModel(app) {
    private val history = (app as App).container.historyRepository

    fun recordPrinted(title: String, image: MonoImage, copies: Int) {
        viewModelScope.launch {
            history.recordRaster(
                title = title.ifBlank { "快速打印" },
                image = image,
                copies = copies,
            )
        }
    }
}
