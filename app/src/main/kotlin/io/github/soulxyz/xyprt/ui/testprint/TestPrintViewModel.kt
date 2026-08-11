package io.github.soulxyz.xyprt.ui.testprint

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import io.github.soulxyz.xyprt.App

/** Provides only the printer status for the status chip; printing runs through the PrintSheet. */
class TestPrintViewModel(app: Application) : AndroidViewModel(app) {
    val printerState = (app as App).container.printerManager.state
}
