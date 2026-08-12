package io.github.soulxyz.xyprt.render

import io.github.soulxyz.xyprt.model.FrameElement
import io.github.soulxyz.xyprt.model.FrameStyle
import io.github.soulxyz.xyprt.model.LabelSpec
import io.github.soulxyz.xyprt.model.LabelTextAlign
import io.github.soulxyz.xyprt.model.TextElement
import io.github.soulxyz.xyprt.printer.MonoImage

/** Short, friendly page used after connecting a printer. */
object PrinterTestPage {
    fun render(): MonoImage {
        val spec = LabelSpec(lengthMm = 58, autoLength = false)
        val elements = listOf(
            TextElement(
                id = "title", x = 12f, y = 14f, text = "错题小印", fontSizePx = 38f,
                bold = true, align = LabelTextAlign.CENTER, boxWidthPx = 360f,
            ),
            TextElement(
                id = "hello", x = 12f, y = 67f, text = "连接成功啦 (≧∇≦)ﾉ", fontSizePx = 24f,
                align = LabelTextAlign.CENTER, boxWidthPx = 360f,
            ),
            FrameElement(id = "line", x = 32f, y = 108f, style = FrameStyle.LINE_H, widthPx = 320f, heightPx = 4f, strokePx = 2f),
            TextElement(
                id = "sample", x = 12f, y = 127f, text = "中文  ABC  123  ✓", fontSizePx = 23f,
                align = LabelTextAlign.CENTER, boxWidthPx = 360f,
            ),
            FrameElement(id = "box1", x = 74f, y = 169f, style = FrameStyle.RECT, widthPx = 38f, heightPx = 38f, strokePx = 3f),
            FrameElement(id = "box2", x = 172f, y = 169f, style = FrameStyle.ROUND_RECT, widthPx = 42f, heightPx = 38f, strokePx = 3f, cornerRadiusPx = 10f),
            FrameElement(id = "box3", x = 272f, y = 169f, style = FrameStyle.RECT, widthPx = 38f, heightPx = 38f, strokePx = 1f),
        )
        return LabelRenderer.renderMono(spec, elements).trimTrailingWhite()
    }
}
