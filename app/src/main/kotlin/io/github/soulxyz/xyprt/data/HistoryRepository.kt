package io.github.soulxyz.xyprt.data

import io.github.soulxyz.xyprt.model.LabelElement
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.printer.MonoImage
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.ceil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.first
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class PrintHistoryEntry(
    val id: Long,
    val templateId: String?,
    val templateName: String,
    val spec: LabelSpec,
    val elements: List<LabelElement>,
    val copies: Int,
    val printedAt: Long,
    /** Total physical paper length for this print action, including all copies when known. */
    val printedLengthMm: Double? = null,
    /** Exact 1-bit quick-print snapshot. Normal template prints leave this null. */
    val rasterBase64: String? = null,
    val rasterHeight: Int = 0,
    val sourceType: String? = null,
    val sourceJson: String? = null,
)


@Serializable
data class QuickPrintHistorySource(
    val mode: String,
    val text: String = "",
    val todoTitle: String = "",
    val todoItems: String = "",
    /** New checklist layout preset. Missing in older history and therefore safely defaults to CLEAN. */
    val todoPreset: String = "CLEAN",
    val todoShowDate: Boolean = true,
    val todoDate: String = "",
    val todoCenterTitle: Boolean = true,
    val fontSizePx: Int = 30,
    val lineSpacingPercent: Int = 115,
    val font: String = "SANS",
    val align: String = "LEFT",
    val uris: List<String> = emptyList(),
    val ditherMode: String = "THRESHOLD",
    val paperPreset: String = "ORIGINAL",
    val threshold: Int = 170,
    val contrast: Int = 0,
    val invert: Boolean = false,
    val outlineSensitivity: Int = 88,
    val outlineThickness: Int = 1,
    val outlineMethod: String = "CANNY",
    val outlineSmooth: Boolean = false,
    val rotationDegrees: Int = 0,
    /** Physical print orientation. Kept separate from image correction rotation. */
    val landscapePrint: Boolean = false,
    val scalePercent: Int = 100,
    val removeRedInk: Boolean = false,
    val removeBlueInk: Boolean = false,
    val pdfAutoCrop: Boolean = true,
    val cameraQuad: List<Float> = emptyList(),
)
@Serializable
data class TodoHistorySource(
    val title: String,
    val items: String,
    val fontSizePx: Int = 30,
    val lineSpacingPercent: Int = 115,
    val font: String = "SANS",
    val align: String = "LEFT",
)

class HistoryRepository(private val dao: PrintHistoryDao, private val json: Json) {

    fun observeAll(): Flow<List<PrintHistoryEntry>> =
        dao.observeAll().map { list -> list.map { it.toDomain() } }

    suspend fun record(
        templateId: String?,
        templateName: String,
        spec: LabelSpec,
        resolvedElements: List<LabelElement>,
        copies: Int,
        printedLengthMm: Double? = null,
    ) {
        dao.insert(
            PrintHistoryEntity(
                templateId = templateId,
                templateName = templateName,
                tapeWidthMm = spec.tapeWidthMm,
                lengthMm = spec.lengthMm,
                media = spec.media.name,
                autoLength = spec.autoLength,
                elementsJson = json.encodeToString(resolvedElements),
                copies = copies,
                printedAt = System.currentTimeMillis(),
                printedLengthMm = printedLengthMm,
            )
        )
        dao.prune()
    }

    suspend fun recordRaster(
        title: String,
        image: MonoImage,
        copies: Int,
        sourceType: String? = null,
        sourceJson: String? = null,
        printedLengthMm: Double? = null,
    ) {
        val approxMm = ceil(image.height / 8.0).toInt().coerceAtLeast(1)
        dao.insert(
            PrintHistoryEntity(
                templateId = null,
                templateName = title,
                tapeWidthMm = 48,
                lengthMm = approxMm,
                media = MediaType.CONTINUOUS.name,
                autoLength = true,
                elementsJson = "[]",
                copies = copies,
                printedAt = System.currentTimeMillis(),
                printedLengthMm = printedLengthMm ?: (approxMm.toDouble() * copies.coerceAtLeast(1)),
                rasterBase64 = encodeRaster(image),
                rasterHeight = image.height,
                sourceType = sourceType,
                sourceJson = sourceJson,
            )
        )
        dao.prune()
    }

    suspend fun getAll(): List<PrintHistoryEntry> = dao.observeAll().map { list -> list.map { it.toDomain() } }.first()

    suspend fun importEntries(entries: List<PrintHistoryEntry>, replace: Boolean) {
        if (replace) dao.clear()
        entries.sortedBy { it.printedAt }.forEach { e ->
            dao.insert(
                PrintHistoryEntity(
                    id = if (replace) e.id else 0L,
                    templateId = e.templateId,
                    templateName = e.templateName,
                    tapeWidthMm = e.spec.tapeWidthMm,
                    lengthMm = e.spec.lengthMm,
                    media = e.spec.media.name,
                    autoLength = e.spec.autoLength,
                    elementsJson = json.encodeToString(e.elements),
                    copies = e.copies,
                    printedAt = e.printedAt,
                    printedLengthMm = e.printedLengthMm,
                    rasterBase64 = e.rasterBase64,
                    rasterHeight = e.rasterHeight,
                    sourceType = e.sourceType,
                    sourceJson = e.sourceJson,
                )
            )
        }
        dao.prune()
    }

    suspend fun delete(id: Long) = dao.delete(id)

    suspend fun clear() = dao.clear()

    private fun PrintHistoryEntity.toDomain() = PrintHistoryEntry(
        id = id,
        templateId = templateId,
        templateName = templateName,
        spec = LabelSpec(
            tapeWidthMm = tapeWidthMm,
            lengthMm = lengthMm,
            media = runCatching { MediaType.valueOf(media) }.getOrDefault(MediaType.CONTINUOUS),
            autoLength = autoLength,
        ),
        elements = runCatching { json.decodeFromString<List<LabelElement>>(elementsJson) }
            .getOrDefault(emptyList()),
        copies = copies,
        printedAt = printedAt,
        printedLengthMm = printedLengthMm,
        rasterBase64 = rasterBase64,
        rasterHeight = rasterHeight,
        sourceType = sourceType,
        sourceJson = sourceJson,
    )

    companion object {
        /** Pack the 384-dot MonoImage to 48 bytes/row and gzip it before Base64. */
        fun encodeRaster(image: MonoImage): String {
            val rowBytes = image.width / 8
            val packed = ByteArray(rowBytes * image.height)
            for (y in 0 until image.height) {
                val row = y * rowBytes
                for (x in 0 until image.width) {
                    if (image.isBlack(x, y)) {
                        packed[row + (x ushr 3)] = (packed[row + (x ushr 3)].toInt() or (0x80 ushr (x and 7))).toByte()
                    }
                }
            }
            val out = ByteArrayOutputStream()
            GZIPOutputStream(out).use { it.write(packed) }
            return Base64.getEncoder().encodeToString(out.toByteArray())
        }

        fun decodeRaster(entry: PrintHistoryEntry): MonoImage? {
            val encoded = entry.rasterBase64 ?: return null
            if (entry.rasterHeight <= 0) return null
            return runCatching {
                val compressed = Base64.getDecoder().decode(encoded)
                val packed = GZIPInputStream(ByteArrayInputStream(compressed)).use { it.readBytes() }
                val width = 384
                val rowBytes = width / 8
                require(packed.size >= rowBytes * entry.rasterHeight)
                val black = BooleanArray(width * entry.rasterHeight)
                for (y in 0 until entry.rasterHeight) {
                    val row = y * rowBytes
                    for (x in 0 until width) {
                        black[y * width + x] = (packed[row + (x ushr 3)].toInt() and (0x80 ushr (x and 7))) != 0
                    }
                }
                MonoImage(entry.rasterHeight, black)
            }.getOrNull()
        }
    }
}
