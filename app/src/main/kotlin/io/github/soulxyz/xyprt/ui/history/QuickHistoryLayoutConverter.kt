package io.github.soulxyz.xyprt.ui.history

import io.github.soulxyz.xyprt.data.QuickPrintHistorySource
import io.github.soulxyz.xyprt.model.FrameElement
import io.github.soulxyz.xyprt.model.FrameStyle
import io.github.soulxyz.xyprt.model.LabelElement
import io.github.soulxyz.xyprt.model.LabelFont
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.model.LabelTextAlign
import io.github.soulxyz.xyprt.model.TextElement
import io.github.soulxyz.xyprt.printer.Protocol
import java.util.UUID
import kotlin.math.ceil

internal data class EditableHistoryLayout(
    val elements: List<LabelElement>,
    val lengthMm: Int,
    val defaultName: String,
)

/** Converts structured quick-print history back into real editor elements instead of a flattened bitmap. */
internal object QuickHistoryLayoutConverter {
    fun convert(source: QuickPrintHistorySource): EditableHistoryLayout? = when (source.mode) {
        "TEXT" -> text(source)
        "TODO" -> todo(source)
        else -> null
    }

    private fun text(source: QuickPrintHistorySource): EditableHistoryLayout {
        val element = TextElement(
            id = UUID.randomUUID().toString(),
            x = 8f,
            y = 16f,
            text = source.text,
            fontSizePx = source.fontSizePx.toFloat(),
            align = source.labelAlign(),
            font = source.labelFont(),
            boxWidthPx = (LabelSpec.PRINT_WIDTH_PX - 16).toFloat(),
            lineSpacingPercent = source.lineSpacingPercent,
        )
        val height = estimateTextHeight(
            text = element.text,
            fontSizePx = element.fontSizePx,
            boxWidthPx = element.boxWidthPx ?: (LabelSpec.PRINT_WIDTH_PX - 16).toFloat(),
            lineSpacingPercent = element.lineSpacingPercent,
        )
        return EditableHistoryLayout(
            elements = listOf(element),
            lengthMm = lengthMm(element.y + height + 16f),
            defaultName = "历史文字",
        )
    }

    private fun todo(source: QuickPrintHistorySource): EditableHistoryLayout {
        val elements = mutableListOf<LabelElement>()
        var y = 14f
        val title = TextElement(
            id = UUID.randomUUID().toString(),
            x = 8f,
            y = y,
            text = source.todoTitle.trim().ifBlank { "今日待办" },
            fontSizePx = 34f,
            bold = true,
            boxWidthPx = (LabelSpec.PRINT_WIDTH_PX - 16).toFloat(),
        )
        elements += title
        y += estimateTextHeight(title.text, title.fontSizePx, title.boxWidthPx ?: 368f, title.lineSpacingPercent) + 16f

        source.todoItems.lineSequence()
            .map { it.trim().removePrefix("- ").removePrefix("• ") }
            .filter { it.isNotBlank() }
            .take(40)
            .forEach { item ->
                val text = TextElement(
                    id = UUID.randomUUID().toString(),
                    x = 42f,
                    y = y,
                    text = item,
                    fontSizePx = source.fontSizePx.toFloat(),
                    align = source.labelAlign(),
                    font = source.labelFont(),
                    boxWidthPx = (LabelSpec.PRINT_WIDTH_PX - 52).toFloat(),
                    lineSpacingPercent = source.lineSpacingPercent,
                )
                val textHeight = estimateTextHeight(
                    text.text,
                    text.fontSizePx,
                    text.boxWidthPx ?: 332f,
                    text.lineSpacingPercent,
                ).coerceAtLeast(26f)
                elements += FrameElement(
                    id = UUID.randomUUID().toString(),
                    x = 8f,
                    y = y + 3f,
                    style = FrameStyle.RECT,
                    widthPx = 22f,
                    heightPx = 22f,
                    strokePx = 2f,
                )
                elements += text
                y += textHeight + 12f
            }

        return EditableHistoryLayout(
            elements = elements,
            lengthMm = lengthMm(y + 12f),
            defaultName = "历史待办",
        )
    }

    private fun lengthMm(dots: Float): Int = ceil(dots / Protocol.DOTS_PER_MM)
        .toInt().coerceIn(LabelSpec.MIN_LENGTH_MM, LabelSpec.MAX_LENGTH_MM)

    /** Conservative, Android-free estimate used only to choose the initial editor canvas length. */
    private fun estimateTextHeight(
        text: String,
        fontSizePx: Float,
        boxWidthPx: Float,
        lineSpacingPercent: Int,
    ): Float {
        val averageGlyphWidth = (fontSizePx * 0.82f).coerceAtLeast(1f)
        val charsPerLine = (boxWidthPx / averageGlyphWidth).toInt().coerceAtLeast(1)
        val visualLines = text.lineSequence().sumOf { line ->
            val glyphs = line.codePointCount(0, line.length).coerceAtLeast(1)
            ceil(glyphs / charsPerLine.toDouble()).toInt().coerceAtLeast(1)
        }.coerceAtLeast(1)
        val lineHeight = fontSizePx * 1.18f * lineSpacingPercent.coerceIn(80, 200) / 100f
        return visualLines * lineHeight + 6f
    }

    private fun QuickPrintHistorySource.labelFont(): LabelFont = when (font) {
        "SERIF" -> LabelFont.SERIF
        "MONO" -> LabelFont.MONO
        else -> LabelFont.SANS
    }

    private fun QuickPrintHistorySource.labelAlign(): LabelTextAlign = when (align) {
        "CENTER" -> LabelTextAlign.CENTER
        "RIGHT" -> LabelTextAlign.RIGHT
        else -> LabelTextAlign.LEFT
    }
}
