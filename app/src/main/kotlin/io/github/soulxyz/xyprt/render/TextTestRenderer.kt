package io.github.soulxyz.xyprt.render

import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.model.LabelTextAlign
import io.github.soulxyz.xyprt.model.TextElement
import io.github.soulxyz.xyprt.printer.MonoImage

/** Simple portrait quick-text renderer kept for internal diagnostics. */
object TextTestRenderer {
    fun render(text: String, spec: LabelSpec = LabelSpec(lengthMm = 60, autoLength = false)): MonoImage {
        val element = TextElement(
            id = "quicktext",
            x = 12f,
            y = 16f,
            text = text.ifBlank { "错题小印" },
            fontSizePx = 34f,
            bold = true,
            align = LabelTextAlign.LEFT,
            boxWidthPx = (LabelSpec.PRINT_WIDTH_PX - 24).toFloat(),
        )
        return LabelRenderer.renderMono(spec, listOf(element)).trimTrailingWhite()
    }
}
