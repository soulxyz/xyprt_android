package io.github.soulxyz.xyprt.ui.quickprint

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.data.SavedDocument
import io.github.soulxyz.xyprt.printer.MonoImage
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/** Records quick prints and owns the app-local PDF shelf. */
class QuickPrintViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as App).container
    private val history = container.historyRepository
    private val savedDocs = container.savedDocuments

    val documents: StateFlow<List<SavedDocument>> = savedDocs.documents

    fun recordPrinted(title: String, image: MonoImage, copies: Int) {
        viewModelScope.launch {
            history.recordRaster(title = title.ifBlank { "快速打印" }, image = image, copies = copies)
        }
    }

    fun savePdf(uri: Uri, displayName: String?, onResult: (Result<SavedDocument>) -> Unit) {
        viewModelScope.launch {
            onResult(runCatching { savedDocs.saveFromUri(uri, displayName) })
        }
    }

    fun deleteDocument(id: String) { viewModelScope.launch { savedDocs.delete(id) } }
    fun uriFor(document: SavedDocument): Uri = savedDocs.uriFor(document)
}
