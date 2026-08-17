package io.github.soulxyz.xyprt.document

import io.github.soulxyz.xyprt.model.BarcodeElement
import io.github.soulxyz.xyprt.model.DrawPoint
import io.github.soulxyz.xyprt.model.DrawStroke
import io.github.soulxyz.xyprt.model.DrawingElement
import io.github.soulxyz.xyprt.model.FrameElement
import io.github.soulxyz.xyprt.model.IconElement
import io.github.soulxyz.xyprt.model.ImageElement
import io.github.soulxyz.xyprt.model.LabelElement
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.model.LabelTemplate
import io.github.soulxyz.xyprt.model.TableElement
import io.github.soulxyz.xyprt.model.TextElement
import io.github.soulxyz.xyprt.printer.Protocol
import kotlin.math.roundToInt

/**
 * Compatibility boundary between the existing 384-dot editor and PrintDocument v1.
 *
 * This adapter is additive: legacy templates remain stored in their original schema and continue to
 * render through LabelRenderer. Converting to PrintDocument therefore cannot destroy user data.
 */
object LegacyLabelAdapter {
    private const val MM100_PER_MM = 100f

    fun fromLegacy(template: LabelTemplate): PrintDocument = PrintDocument(
        id = template.id,
        name = template.name,
        page = DocumentPage(
            widthMm100 = template.spec.tapeWidthMm * 100,
            heightMm100 = template.spec.workingLengthMm * 100,
            media = template.spec.media,
            continuous = template.spec.autoLength,
        ),
        nodes = template.elements.map(::fromLegacyNode),
        legacy = LegacyDocumentCompatibility(
            tapeWidthMm = template.spec.tapeWidthMm,
            lengthMm = template.spec.lengthMm,
            autoLength = template.spec.autoLength,
            favorite = template.favorite,
            counterValue = template.counterValue,
            createdAt = template.createdAt,
            updatedAt = template.updatedAt,
        ),
    )

    fun toLegacy(document: PrintDocument): LabelTemplate {
        require(document.schemaVersion == PrintDocument.SCHEMA_VERSION) { "不支持的文档协议版本" }
        require(document.minRendererVersion <= PrintDocument.RENDERER_VERSION) { "当前渲染器版本过低" }
        val compat = document.legacy
        val spec = LabelSpec(
            tapeWidthMm = compat?.tapeWidthMm ?: (document.page.widthMm100 / 100f).roundToInt(),
            lengthMm = compat?.lengthMm ?: (document.page.heightMm100 / 100f).roundToInt(),
            media = document.page.media,
            autoLength = compat?.autoLength ?: document.page.continuous,
        )
        return LabelTemplate(
            id = document.id,
            name = document.name,
            spec = spec,
            elements = document.nodes.map(::toLegacyNode),
            favorite = compat?.favorite ?: false,
            counterValue = compat?.counterValue ?: 1,
            createdAt = compat?.createdAt ?: 0L,
            updatedAt = compat?.updatedAt ?: 0L,
        )
    }

    private fun fromLegacyNode(node: LabelElement): DocumentNode = when (node) {
        is TextElement -> DocumentText(
            id = node.id,
            xMm100 = mm100(node.x),
            yMm100 = mm100(node.y),
            rotation = node.rotation,
            text = node.text,
            fontSizeMm100 = mm100(node.fontSizePx),
            bold = node.bold,
            italic = node.italic,
            underline = node.underline,
            align = node.align,
            fallbackFont = node.font,
            fontAssetId = node.fontAssetId,
            boxWidthMm100 = node.boxWidthPx?.let(::mm100),
            lineSpacingPercent = node.lineSpacingPercent,
        )
        is IconElement -> DocumentIcon(
            node.id, mm100(node.x), mm100(node.y), node.rotation, node.glyph, mm100(node.sizePx),
            node.dither, node.contrast, node.outlineSensitivity, node.outlineThickness, node.outlineMethod,
            node.invert, node.outlineSmooth,
        )
        is FrameElement -> DocumentShape(
            node.id, mm100(node.x), mm100(node.y), node.rotation, node.style, mm100(node.widthPx),
            mm100(node.heightPx), mm100(node.strokePx), mm100(node.cornerRadiusPx),
        )
        is TableElement -> DocumentGrid(
            node.id, mm100(node.x), mm100(node.y), node.rotation, node.rows, node.columns,
            mm100(node.widthPx), mm100(node.heightPx), mm100(node.strokePx),
        )
        is BarcodeElement -> DocumentBarcode(
            node.id, mm100(node.x), mm100(node.y), node.rotation, node.symbology, node.data,
            mm100(node.widthPx), mm100(node.heightPx), node.showText, node.payloadType, node.payload,
        )
        is ImageElement -> DocumentImage(
            node.id, mm100(node.x), mm100(node.y), node.rotation, node.pngBase64, null,
            node.srcWidth, node.srcHeight, mm100(node.widthPx), node.dither, node.invert, node.threshold,
            node.contrast, node.outlineSensitivity, node.outlineThickness, node.outlineMethod,
            node.outlineSmooth, node.removeRedInk, node.removeBlueInk,
        )
        is DrawingElement -> DocumentDrawing(
            node.id, mm100(node.x), mm100(node.y), node.rotation, mm100(node.widthPx), mm100(node.heightPx),
            node.strokes.map { stroke ->
                DocumentDrawStroke(stroke.points.map { DocumentDrawPoint(mm100(it.x), mm100(it.y)) }, mm100(stroke.widthPx))
            },
        )
    }

    private fun toLegacyNode(node: DocumentNode): LabelElement = when (node) {
        is DocumentText -> TextElement(
            id = node.id,
            x = dots(node.xMm100),
            y = dots(node.yMm100),
            rotation = node.rotation,
            text = node.text,
            fontSizePx = dots(node.fontSizeMm100),
            bold = node.bold,
            italic = node.italic,
            underline = node.underline,
            align = node.align,
            font = node.fallbackFont,
            boxWidthPx = node.boxWidthMm100?.let(::dots),
            fontAssetId = node.fontAssetId,
            lineSpacingPercent = node.lineSpacingPercent,
        )
        is DocumentIcon -> IconElement(
            node.id, dots(node.xMm100), dots(node.yMm100), node.rotation, node.glyph, dots(node.sizeMm100),
            node.dither, node.contrast, node.outlineSensitivity, node.outlineThickness, node.outlineMethod,
            node.invert, node.outlineSmooth,
        )
        is DocumentShape -> FrameElement(
            node.id, dots(node.xMm100), dots(node.yMm100), node.rotation, node.style,
            dots(node.widthMm100), dots(node.heightMm100), dots(node.strokeMm100), dots(node.cornerRadiusMm100),
        )
        is DocumentGrid -> TableElement(
            node.id, dots(node.xMm100), dots(node.yMm100), node.rotation, node.rows, node.columns,
            dots(node.widthMm100), dots(node.heightMm100), dots(node.strokeMm100),
        )
        is DocumentBarcode -> BarcodeElement(
            node.id, dots(node.xMm100), dots(node.yMm100), node.rotation, node.symbology, node.data,
            dots(node.widthMm100), dots(node.heightMm100), node.showText, node.payloadType, node.payload,
        )
        is DocumentImage -> {
            require(node.assetId == null || node.pngBase64.isNotEmpty()) { "远程图片节点不能无损降级到旧编辑器" }
            ImageElement(
                node.id, dots(node.xMm100), dots(node.yMm100), node.rotation, node.pngBase64,
                node.sourceWidth, node.sourceHeight, dots(node.widthMm100), node.dither, node.invert,
                node.threshold, node.contrast, node.outlineSensitivity, node.outlineThickness,
                node.outlineMethod, node.outlineSmooth, node.removeRedInk, node.removeBlueInk,
            )
        }
        is DocumentDrawing -> DrawingElement(
            node.id, dots(node.xMm100), dots(node.yMm100), node.rotation, dots(node.widthMm100),
            dots(node.heightMm100), node.strokes.map { stroke ->
                DrawStroke(stroke.points.map { DrawPoint(dots(it.xMm100), dots(it.yMm100)) }, dots(stroke.widthMm100))
            },
        )
    }

    private fun mm100(dots: Float): Int = (dots * MM100_PER_MM / Protocol.DOTS_PER_MM).roundToInt()
    private fun dots(mm100: Int): Float = mm100 * Protocol.DOTS_PER_MM / MM100_PER_MM
}
