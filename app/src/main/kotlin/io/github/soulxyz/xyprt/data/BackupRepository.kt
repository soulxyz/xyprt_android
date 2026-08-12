package io.github.soulxyz.xyprt.data

import io.github.soulxyz.xyprt.model.ImageElement
import io.github.soulxyz.xyprt.model.LabelElement
import io.github.soulxyz.xyprt.model.LabelTemplate
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class BackupSettings(
    val defaultTapeWidthMm: Int? = null,
    val defaultLengthMm: Int? = null,
    val defaultDieCut: Boolean? = null,
    val printerAddress: String? = null,
    val printerName: String? = null,
    val printerTransport: String? = null,
    val feedBeforeDots: Int? = null,
    val feedAfterDots: Int? = null,
    val language: String? = null,
)

/** Legacy 1.1.x JSON backup. Kept so old exports remain importable. */
@Serializable
data class BackupFile(
    val formatVersion: Int = 1,
    val templates: List<LabelTemplate> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
)

@Serializable
private data class PortableBackup(
    val format: String = "xyprt",
    val formatVersion: Int = 2,
    val appVersion: String = "1.2.0",
    val createdAt: Long = System.currentTimeMillis(),
    val templates: List<LabelTemplate> = emptyList(),
    val documents: List<SavedDocument> = emptyList(),
    val history: List<PrintHistoryEntry> = emptyList(),
    val settings: BackupSettings = BackupSettings(),
)

/**
 * Portable .xyprt package.
 *
 * Version 2 is a ZIP container: manifest.json + assets/ + documents/. Image elements refer to
 * @asset paths instead of embedding Base64 into one giant JSON document. This makes exports much
 * safer to move across devices and leaves room for future asset types.
 */
class BackupRepository(
    private val templates: TemplateRepository,
    private val history: HistoryRepository,
    private val settings: SettingsRepository,
    private val documents: SavedDocumentRepository,
    private val json: Json,
) {
    companion object {
        private const val MANIFEST = "manifest.json"
        private const val ASSET_PREFIX = "@asset:"
    }

    suspend fun hasTemplates(): Boolean = templates.getAll().isNotEmpty()
    suspend fun hasContent(): Boolean = templates.getAll().isNotEmpty() || documents.documents.value.isNotEmpty()

    suspend fun exportPackage(languageTag: String?): ByteArray {
        val assets = linkedMapOf<String, ByteArray>()
        val preparedTemplates = templates.getAll().map { externalizeImages(it, assets) }
        val preparedHistory = history.getAll().map { externalizeHistory(it, assets) }
        val printer = settings.savedPrinter.first()
        val packageFile = PortableBackup(
            templates = preparedTemplates,
            documents = documents.documents.value,
            history = preparedHistory,
            settings = BackupSettings(
                defaultTapeWidthMm = settings.defaultTapeWidthMm.first(),
                defaultLengthMm = settings.defaultLengthMm.first(),
                defaultDieCut = settings.defaultDieCut.first(),
                printerAddress = printer?.address,
                printerName = printer?.name,
                printerTransport = printer?.transport,
                feedBeforeDots = settings.printFeedBeforeDots.first(),
                feedAfterDots = settings.printFeedAfterDots.first(),
                language = languageTag,
            ),
        )
        val extra = linkedMapOf<String, ByteArray>()
        assets.forEach { (k, v) -> extra[k] = v }
        packageFile.documents.forEach { doc ->
            val file = documents.fileFor(doc)
            if (file.exists()) extra["documents/${doc.fileName}"] = file.readBytes()
        }
        return zip(json.encodeToString(packageFile).encodeToByteArray(), extra)
    }

    suspend fun importPackage(bytes: ByteArray, replace: Boolean) {
        if (!looksLikeZip(bytes)) {
            // 1.1.x raw JSON compatibility.
            import(bytes.decodeToString(), replace)
            return
        }
        val entries = unzip(bytes)
        val manifest = entries[MANIFEST] ?: error("缺少备份清单")
        val backup = json.decodeFromString<PortableBackup>(manifest.decodeToString())
        require(backup.format == "xyprt" && backup.formatVersion >= 2) { "不支持的备份格式" }

        if (replace) {
            templates.deleteAll()
            documents.deleteAll()
        }
        val now = System.currentTimeMillis()
        backup.templates.forEachIndexed { i, raw ->
            val hydrated = hydrateImages(raw, entries)
            val template = if (replace) {
                hydrated.copy(updatedAt = now + i)
            } else {
                hydrated.copy(id = UUID.randomUUID().toString(), createdAt = now, updatedAt = now + i)
            }
            templates.insert(template)
        }
        backup.documents.forEach { meta ->
            entries["documents/${meta.fileName}"]?.let { documents.addImported(meta, it) }
        }
        val hydratedHistory = backup.history.map { hydrateHistory(it, entries) }
        if (hydratedHistory.isNotEmpty() || replace) history.importEntries(hydratedHistory, replace)
        applySettings(backup.settings)
    }

    fun peekLanguage(bytes: ByteArray): String? = runCatching {
        if (looksLikeZip(bytes)) {
            val manifest = unzip(bytes)[MANIFEST] ?: return@runCatching null
            json.decodeFromString<PortableBackup>(manifest.decodeToString()).settings.language
        } else {
            peekLanguage(bytes.decodeToString())
        }
    }.getOrNull()

    suspend fun exportTemplatePackage(template: LabelTemplate): ByteArray {
        val assets = linkedMapOf<String, ByteArray>()
        val portable = externalizeImages(template, assets)
        val manifest = PortableBackup(templates = listOf(portable))
        return zip(json.encodeToString(manifest).encodeToByteArray(), assets)
    }

    fun importTemplatePackage(bytes: ByteArray): LabelTemplate {
        if (!looksLikeZip(bytes)) {
            // Old individual template JSON.
            return runCatching { json.decodeFromString<LabelTemplate>(bytes.decodeToString()) }
                .getOrElse { throw IllegalArgumentException("无法读取文档") }
        }
        val entries = unzip(bytes)
        val manifest = entries[MANIFEST] ?: error("缺少文档清单")
        val backup = json.decodeFromString<PortableBackup>(manifest.decodeToString())
        val template = backup.templates.firstOrNull() ?: error("备份中没有文档")
        return hydrateImages(template, entries)
    }

    // ---- Legacy JSON API -------------------------------------------------

    suspend fun export(languageTag: String?): String {
        val printer = settings.savedPrinter.first()
        val backup = BackupFile(
            templates = templates.getAll(),
            settings = BackupSettings(
                defaultTapeWidthMm = settings.defaultTapeWidthMm.first(),
                defaultLengthMm = settings.defaultLengthMm.first(),
                defaultDieCut = settings.defaultDieCut.first(),
                printerAddress = printer?.address,
                printerName = printer?.name,
                printerTransport = printer?.transport,
                feedBeforeDots = settings.printFeedBeforeDots.first(),
                feedAfterDots = settings.printFeedAfterDots.first(),
                language = languageTag,
            ),
        )
        return json.encodeToString(backup)
    }

    fun peekLanguage(raw: String): String? =
        runCatching { json.decodeFromString<BackupFile>(raw).settings.language }.getOrNull()

    suspend fun import(raw: String, replace: Boolean) {
        val backup = json.decodeFromString<BackupFile>(raw)
        require(backup.formatVersion >= 1) { "Unknown format" }
        if (replace) templates.deleteAll()
        val now = System.currentTimeMillis()
        backup.templates.forEachIndexed { i, t ->
            val tpl = if (replace) t.copy(updatedAt = now + i)
            else t.copy(id = UUID.randomUUID().toString(), createdAt = now, updatedAt = now + i)
            templates.insert(tpl)
        }
        applySettings(backup.settings)
    }

    private suspend fun applySettings(s: BackupSettings) {
        if (s.defaultTapeWidthMm != null && s.defaultLengthMm != null && s.defaultDieCut != null) {
            settings.saveDefaultLabel(s.defaultTapeWidthMm, s.defaultLengthMm, s.defaultDieCut)
        }
        if (s.printerAddress != null) {
            settings.savePrinter(s.printerAddress, s.printerName ?: s.printerAddress, s.printerTransport)
        }
        if (s.feedBeforeDots != null || s.feedAfterDots != null) {
            settings.savePrintSpacing(
                s.feedBeforeDots ?: settings.printFeedBeforeDots.first(),
                s.feedAfterDots ?: settings.printFeedAfterDots.first(),
            )
        }
    }

    private fun externalizeImages(template: LabelTemplate, assets: MutableMap<String, ByteArray>): LabelTemplate {
        val elements = template.elements.map { element ->
            if (element !is ImageElement || element.pngBase64.isBlank() || element.pngBase64.startsWith(ASSET_PREFIX)) return@map element
            val bytes = runCatching { Base64.getDecoder().decode(element.pngBase64) }.getOrNull() ?: return@map element
            val path = "assets/${template.id}/${element.id}.png"
            assets[path] = bytes
            element.copy(pngBase64 = "$ASSET_PREFIX$path")
        }
        return template.copy(elements = elements)
    }

    private fun hydrateImages(template: LabelTemplate, entries: Map<String, ByteArray>): LabelTemplate {
        val elements: List<LabelElement> = template.elements.map { element ->
            if (element is ImageElement && element.pngBase64.startsWith(ASSET_PREFIX)) {
                val path = element.pngBase64.removePrefix(ASSET_PREFIX)
                val bytes = entries[path] ?: error("缺少图片资源：$path")
                element.copy(pngBase64 = Base64.getEncoder().encodeToString(bytes))
            } else element
        }
        return template.copy(elements = elements)
    }

    private fun externalizeHistory(entry: PrintHistoryEntry, assets: MutableMap<String, ByteArray>): PrintHistoryEntry {
        val elements = entry.elements.map { element ->
            if (element !is ImageElement || element.pngBase64.isBlank() || element.pngBase64.startsWith(ASSET_PREFIX)) return@map element
            val bytes = runCatching { Base64.getDecoder().decode(element.pngBase64) }.getOrNull() ?: return@map element
            val path = "assets/history/${entry.id}/${element.id}.png"
            assets[path] = bytes
            element.copy(pngBase64 = "$ASSET_PREFIX$path")
        }
        val raster = entry.rasterBase64?.let { encoded ->
            if (encoded.startsWith(ASSET_PREFIX)) encoded else {
                val bytes = runCatching { Base64.getDecoder().decode(encoded) }.getOrNull()
                if (bytes == null) encoded else {
                    val path = "assets/history/${entry.id}/raster.gz"
                    assets[path] = bytes
                    "$ASSET_PREFIX$path"
                }
            }
        }
        return entry.copy(elements = elements, rasterBase64 = raster)
    }

    private fun hydrateHistory(entry: PrintHistoryEntry, assets: Map<String, ByteArray>): PrintHistoryEntry {
        val elements = entry.elements.map { element ->
            if (element is ImageElement && element.pngBase64.startsWith(ASSET_PREFIX)) {
                val path = element.pngBase64.removePrefix(ASSET_PREFIX)
                val bytes = assets[path] ?: error("缺少历史图片：$path")
                element.copy(pngBase64 = Base64.getEncoder().encodeToString(bytes))
            } else element
        }
        val raster = entry.rasterBase64?.let { encoded ->
            if (encoded.startsWith(ASSET_PREFIX)) {
                val path = encoded.removePrefix(ASSET_PREFIX)
                Base64.getEncoder().encodeToString(assets[path] ?: error("缺少历史快照：$path"))
            } else encoded
        }
        return entry.copy(elements = elements, rasterBase64 = raster)
    }

    private fun zip(manifest: ByteArray, entries: Map<String, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            fun put(path: String, bytes: ByteArray) {
                zip.putNextEntry(ZipEntry(path))
                zip.write(bytes)
                zip.closeEntry()
            }
            put(MANIFEST, manifest)
            entries.forEach { (path, data) -> put(path, data) }
        }
        return out.toByteArray()
    }

    private fun unzip(bytes: ByteArray): Map<String, ByteArray> {
        val result = linkedMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = entry.name.replace('\\', '/')
                require(!name.startsWith('/') && ".." !in name.split('/')) { "非法备份路径" }
                if (!entry.isDirectory) result[name] = zip.readBytes()
                zip.closeEntry()
            }
        }
        return result
    }

    private fun looksLikeZip(bytes: ByteArray): Boolean =
        bytes.size >= 4 && bytes[0] == 0x50.toByte() && bytes[1] == 0x4B.toByte() &&
            bytes[2] == 0x03.toByte() && bytes[3] == 0x04.toByte()
}
