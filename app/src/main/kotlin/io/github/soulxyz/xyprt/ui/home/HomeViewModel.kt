package io.github.soulxyz.xyprt.ui.home

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.soulxyz.xyprt.App
import io.github.soulxyz.xyprt.R
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.data.UpdateState
import io.github.soulxyz.xyprt.model.LabelTemplate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class HomeViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as App).container
    private val repo = container.templateRepository
    private val historyRepo = container.historyRepository

    val printerState = container.printerManager.state
    val savedPrinter = container.settings.savedPrinter
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val updateState = container.updates.state
    val coCreatorState = container.coCreator.state
    val updateUnseen = combine(container.updates.state, container.settings.lastSeenUpdateCode) { state, seen ->
        val info = (state as? UpdateState.Available)?.info
        info != null && info.versionCode > seen
    }.stateIn(viewModelScope, SharingStarted.Eagerly, false)

    init { container.updates.check() }

    fun markCurrentUpdateSeen() {
        val info = (container.updates.state.value as? UpdateState.Available)?.info ?: return
        viewModelScope.launch { container.settings.markUpdateSeen(info.versionCode) }
    }

    private val _query = MutableStateFlow("")
    val query = _query.asStateFlow()

    val templates = combine(repo.observeAll(), _query) { list, q ->
        if (q.isBlank()) list
        else list.filter { it.name.contains(q.trim(), ignoreCase = true) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val recentHistory = historyRepo.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val printStats = container.printStats.stats

    fun setQuery(value: String) {
        _query.value = value
    }

    fun create(name: String, spec: LabelSpec, defaultName: String, onCreated: (String) -> Unit) {
        viewModelScope.launch {
            val template = repo.create(name, spec, defaultName)
            onCreated(template.id)
        }
    }

    fun duplicate(id: String, newName: String) {
        viewModelScope.launch { repo.duplicate(id, newName) }
    }

    fun delete(id: String) {
        viewModelScope.launch { repo.delete(id) }
    }

    fun rename(id: String, name: String) {
        viewModelScope.launch { repo.rename(id, name) }
    }

    /** Updates the name and dimensions of an existing template (elements are kept). */
    fun updateMeta(id: String, name: String, spec: LabelSpec) {
        viewModelScope.launch {
            val current = repo.get(id) ?: return@launch
            repo.save(current.copy(name = name.ifBlank { current.name }, spec = spec))
        }
    }

    /** Active connection attempt to the remembered printer (tap on the status chip). */
    fun connectSaved() = container.printerManager.connectSavedActive()

    fun toggleFavorite(template: LabelTemplate) {
        viewModelScope.launch { repo.setFavorite(template.id, !template.favorite) }
    }

    /** Writes a portable .xyprt document package, including embedded image assets. */
    fun exportTo(uri: Uri, template: LabelTemplate, onResult: (String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val error = runCatching {
                val payload = container.backup.exportTemplatePackage(template)
                app.contentResolver.openOutputStream(uri)
                    ?.use { it.write(payload) }
                    ?: error("output stream unavailable")
            }.fold(onSuccess = { null }, onFailure = { app.getString(R.string.err_file_not_writable) })
            withContext(Dispatchers.Main) { onResult(error) }
        }
    }

    /** Reads a portable .xyprt package. Old JSON template exports remain supported. */
    fun importFrom(uri: Uri, defaultName: String, onResult: (error: String?, newId: String?) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val app = getApplication<Application>()
            val raw = runCatching {
                app.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            }.getOrNull()
            val newId = raw?.let { bytes ->
                runCatching {
                    val export = container.backup.importTemplatePackage(bytes)
                    repo.createFrom(export.name, export.spec, export.elements, defaultName).id
                }.getOrNull()
            }
            withContext(Dispatchers.Main) {
                when {
                    raw == null -> onResult(app.getString(R.string.err_file_not_readable), null)
                    newId == null -> onResult(app.getString(R.string.err_file_invalid), null)
                    else -> onResult(null, newId)
                }
            }
        }
    }

}
