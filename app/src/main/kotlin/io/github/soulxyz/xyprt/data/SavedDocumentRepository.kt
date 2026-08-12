package io.github.soulxyz.xyprt.data

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Serializable
data class SavedDocument(
    val id: String,
    val name: String,
    val fileName: String,
    val mimeType: String = "application/pdf",
    val sizeBytes: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * Small app-local document shelf. Files are copied into internal storage so a PDF received from
 * WeChat / Files keeps working after the original temporary Uri permission disappears.
 */
class SavedDocumentRepository(
    private val context: Context,
    private val json: Json,
) {
    private val root = File(context.filesDir, "saved_documents").apply { mkdirs() }
    private val index = File(root, "index.json")
    private val mutex = Mutex()
    private val _documents = MutableStateFlow(readIndex())
    val documents: StateFlow<List<SavedDocument>> = _documents

    suspend fun saveFromUri(uri: Uri, displayName: String? = null): SavedDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = UUID.randomUUID().toString()
            val safeOriginal = sanitize(displayName ?: queryDisplayName(uri) ?: "document.pdf")
            val ext = safeOriginal.substringAfterLast('.', "pdf").takeIf { it.length in 1..8 } ?: "pdf"
            val fileName = "$id.$ext"
            val target = File(root, fileName)
            try {
                context.contentResolver.openInputStream(uri)?.use { input ->
                    target.outputStream().buffered().use { output -> input.copyTo(output) }
                } ?: error("无法读取文档")
                val item = SavedDocument(
                    id = id,
                    name = safeOriginal,
                    fileName = fileName,
                    mimeType = context.contentResolver.getType(uri) ?: "application/pdf",
                    sizeBytes = target.length(),
                )
                val next = listOf(item) + _documents.value.filterNot { it.id == item.id }
                writeIndex(next)
                _documents.value = next
                item
            } catch (t: Throwable) {
                target.delete()
                throw t
            }
        }
    }

    fun uriFor(document: SavedDocument): Uri = FileProvider.getUriForFile(
        context,
        "${context.packageName}.fileprovider",
        File(root, document.fileName),
    )

    fun fileFor(document: SavedDocument): File = File(root, document.fileName)

    suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        mutex.withLock {
            val item = _documents.value.firstOrNull { it.id == id }
            item?.let { File(root, it.fileName).delete() }
            val next = _documents.value.filterNot { it.id == id }
            writeIndex(next)
            _documents.value = next
        }
    }

    suspend fun deleteAll() = withContext(Dispatchers.IO) {
        mutex.withLock {
            _documents.value.forEach { File(root, it.fileName).delete() }
            writeIndex(emptyList())
            _documents.value = emptyList()
        }
    }

    suspend fun replaceAll(items: List<Pair<SavedDocument, ByteArray>>) = withContext(Dispatchers.IO) {
        mutex.withLock {
            root.listFiles()?.filter { it != index }?.forEach { it.delete() }
            val saved = items.map { (meta, bytes) ->
                val target = File(root, meta.fileName)
                target.parentFile?.mkdirs()
                target.writeBytes(bytes)
                meta.copy(sizeBytes = target.length())
            }
            writeIndex(saved)
            _documents.value = saved
        }
    }

    suspend fun addImported(meta: SavedDocument, bytes: ByteArray): SavedDocument = withContext(Dispatchers.IO) {
        mutex.withLock {
            val id = if (_documents.value.any { it.id == meta.id }) UUID.randomUUID().toString() else meta.id
            val ext = meta.fileName.substringAfterLast('.', "pdf")
            val fileName = "$id.$ext"
            val target = File(root, fileName)
            target.writeBytes(bytes)
            val item = meta.copy(id = id, fileName = fileName, sizeBytes = bytes.size.toLong())
            val next = listOf(item) + _documents.value
            writeIndex(next)
            _documents.value = next
            item
        }
    }

    private fun readIndex(): List<SavedDocument> = runCatching {
        if (!index.exists()) emptyList()
        else json.decodeFromString(ListSerializer(SavedDocument.serializer()), index.readText(Charsets.UTF_8))
            .filter { File(root, it.fileName).exists() }
    }.getOrDefault(emptyList())

    private fun writeIndex(items: List<SavedDocument>) {
        val temp = File(root, "index.tmp")
        temp.writeText(json.encodeToString(ListSerializer(SavedDocument.serializer()), items), Charsets.UTF_8)
        if (!temp.renameTo(index)) {
            index.writeBytes(temp.readBytes())
            temp.delete()
        }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
    }.getOrNull()

    private fun sanitize(name: String): String = name
        .replace(Regex("[\\\\/:*?\"<>|\\u0000-\\u001F]"), "_")
        .trim()
        .take(120)
        .ifBlank { "document.pdf" }
}
