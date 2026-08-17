package io.github.soulxyz.xyprt.document

import io.github.soulxyz.xyprt.model.FrameStyle
import io.github.soulxyz.xyprt.model.LabelFont
import io.github.soulxyz.xyprt.model.LabelTextAlign
import io.github.soulxyz.xyprt.model.QrPayloadType
import io.github.soulxyz.xyprt.model.Symbology
import io.github.soulxyz.xyprt.printer.MediaType
import io.github.soulxyz.xyprt.printer.dither.DitherMode
import io.github.soulxyz.xyprt.printer.dither.OutlineMethod
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/**
 * Device-independent print document. One unit is 0.01 mm (mm100), so persisted geometry describes
 * paper rather than one printer's dot grid. Rendering to dots happens at the device boundary.
 *
 * Schema v1 is deliberately declarative: remote documents contain data only, never executable code.
 */
@Serializable
data class PrintDocument(
    val schemaVersion: Int = SCHEMA_VERSION,
    val minRendererVersion: Int = RENDERER_VERSION,
    val id: String,
    val name: String,
    val page: DocumentPage,
    val nodes: List<DocumentNode> = emptyList(),
    val metadata: JsonObject? = null,
    /** Present only when adapted from the legacy editor; never required for native v1 documents. */
    val legacy: LegacyDocumentCompatibility? = null,
) {
    init {
        require(schemaVersion == SCHEMA_VERSION) { "Unsupported PrintDocument schema $schemaVersion" }
        require(page.widthMm100 > 0 && page.heightMm100 > 0) { "Invalid page size" }
    }

    companion object {
        const val SCHEMA_VERSION = 1
        const val RENDERER_VERSION = 1
        const val MM100_PER_MM = 100
    }
}

@Serializable
data class DocumentPage(
    val widthMm100: Int,
    val heightMm100: Int,
    val media: MediaType = MediaType.CONTINUOUS,
    val continuous: Boolean = true,
)

/** Exact legacy-template bookkeeping so adaptation never requires rewriting old JSON. */
@Serializable
data class LegacyDocumentCompatibility(
    val tapeWidthMm: Int = 48,
    val lengthMm: Int = 80,
    val autoLength: Boolean = true,
    val favorite: Boolean = false,
    val counterValue: Int = 1,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L,
)

@Serializable
sealed interface DocumentNode {
    val id: String
    val xMm100: Int
    val yMm100: Int
    val rotation: Int
}

@Serializable
@SerialName("text")
data class DocumentText(
    override val id: String,
    override val xMm100: Int,
    override val yMm100: Int,
    override val rotation: Int = 0,
    val text: String,
    val fontSizeMm100: Int,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val align: LabelTextAlign = LabelTextAlign.LEFT,
    val fallbackFont: LabelFont = LabelFont.SANS,
    /** Stable remote asset slug. Null means bundled/system fallback only. */
    val fontAssetId: String? = null,
    val boxWidthMm100: Int? = null,
    val lineSpacingPercent: Int = 100,
) : DocumentNode

@Serializable
@SerialName("icon")
data class DocumentIcon(
    override val id: String,
    override val xMm100: Int,
    override val yMm100: Int,
    override val rotation: Int = 0,
    val glyph: String,
    val sizeMm100: Int,
    val dither: DitherMode = DitherMode.OUTLINE,
    val contrast: Int = 0,
    val outlineSensitivity: Int = 88,
    val outlineThickness: Int = 1,
    val outlineMethod: OutlineMethod = OutlineMethod.LINES,
    val invert: Boolean = false,
    val outlineSmooth: Boolean = false,
) : DocumentNode

@Serializable
@SerialName("shape")
data class DocumentShape(
    override val id: String,
    override val xMm100: Int,
    override val yMm100: Int,
    override val rotation: Int = 0,
    val style: FrameStyle = FrameStyle.RECT,
    val widthMm100: Int,
    val heightMm100: Int,
    val strokeMm100: Int,
    val cornerRadiusMm100: Int = 0,
) : DocumentNode

@Serializable
@SerialName("grid")
data class DocumentGrid(
    override val id: String,
    override val xMm100: Int,
    override val yMm100: Int,
    override val rotation: Int = 0,
    val rows: Int,
    val columns: Int,
    val widthMm100: Int,
    val heightMm100: Int,
    val strokeMm100: Int,
) : DocumentNode

@Serializable
@SerialName("barcode")
data class DocumentBarcode(
    override val id: String,
    override val xMm100: Int,
    override val yMm100: Int,
    override val rotation: Int = 0,
    val symbology: Symbology = Symbology.QR_CODE,
    val data: String = "",
    val widthMm100: Int,
    val heightMm100: Int,
    val showText: Boolean = true,
    val payloadType: QrPayloadType = QrPayloadType.TEXT,
    val payload: Map<String, String> = emptyMap(),
) : DocumentNode

@Serializable
@SerialName("image")
data class DocumentImage(
    override val id: String,
    override val xMm100: Int,
    override val yMm100: Int,
    override val rotation: Int = 0,
    /** Legacy embedded image. New remote templates should prefer assetId instead. */
    val pngBase64: String = "",
    /** Content-addressed remote asset slug. */
    val assetId: String? = null,
    val sourceWidth: Int = 1,
    val sourceHeight: Int = 1,
    val widthMm100: Int,
    val dither: DitherMode = DitherMode.FLOYD_STEINBERG,
    val invert: Boolean = false,
    val threshold: Int = 128,
    val contrast: Int = 0,
    val outlineSensitivity: Int = 88,
    val outlineThickness: Int = 1,
    val outlineMethod: OutlineMethod = OutlineMethod.CANNY,
    val outlineSmooth: Boolean = false,
    val removeRedInk: Boolean = false,
    val removeBlueInk: Boolean = false,
) : DocumentNode

@Serializable
data class DocumentDrawPoint(val xMm100: Int, val yMm100: Int)

@Serializable
data class DocumentDrawStroke(
    val points: List<DocumentDrawPoint> = emptyList(),
    val widthMm100: Int,
)

@Serializable
@SerialName("drawing")
data class DocumentDrawing(
    override val id: String,
    override val xMm100: Int,
    override val yMm100: Int,
    override val rotation: Int = 0,
    val widthMm100: Int,
    val heightMm100: Int,
    val strokes: List<DocumentDrawStroke> = emptyList(),
) : DocumentNode
