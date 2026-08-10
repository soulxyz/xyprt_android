package io.github.toolicious.labler.data

import io.github.toolicious.labler.model.LabelElement
import io.github.toolicious.labler.model.LabelSpec
import io.github.toolicious.labler.printer.MediaType
import io.github.toolicious.labler.printer.MonoImage
import java.util.Base64
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.math.ceil
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class PrintHistoryEntry(
    val id: Long,
    val templateId: String?,
    val templateName: String,
    val spec: LabelSpec,
    val elements: List<LabelElement>,
    val copies: Int,
    val printedAt: Long,
    /** Exact 1-bit quick-print snapshot. Normal template prints leave this null. */
    val rasterBase64: String? = null,
    val rasterHeight: Int = 0,
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
            )
        )
        dao.prune()
    }

    suspend fun recordRaster(
        title: String,
        image: MonoImage,
        copies: Int,
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
                rasterBase64 = encodeRaster(image),
                rasterHeight = image.height,
            )
        )
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
        rasterBase64 = rasterBase64,
        rasterHeight = rasterHeight,
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
