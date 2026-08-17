package io.github.soulxyz.xyprt.ui.history

import io.github.soulxyz.xyprt.data.QuickPrintHistorySource
import io.github.soulxyz.xyprt.model.FrameElement
import io.github.soulxyz.xyprt.model.LabelFont
import io.github.soulxyz.xyprt.model.LabelTextAlign
import io.github.soulxyz.xyprt.model.TextElement
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class QuickHistoryLayoutConverterTest {
    @Test
    fun `quick text stays editable in free layout`() {
        val layout = QuickHistoryLayoutConverter.convert(
            QuickPrintHistorySource(
                mode = "TEXT",
                text = "第一行\n第二行",
                fontSizePx = 36,
                lineSpacingPercent = 135,
                font = "SERIF",
                align = "CENTER",
            ),
        ) ?: error("missing layout")

        assertEquals(1, layout.elements.size)
        val text = layout.elements.single() as TextElement
        assertEquals("第一行\n第二行", text.text)
        assertEquals(36f, text.fontSizePx)
        assertEquals(135, text.lineSpacingPercent)
        assertEquals(LabelFont.SERIF, text.font)
        assertEquals(LabelTextAlign.CENTER, text.align)
        assertTrue(text.boxWidthPx != null)
    }

    @Test
    fun `todo becomes editable text and checkbox elements`() {
        val layout = QuickHistoryLayoutConverter.convert(
            QuickPrintHistorySource(mode = "TODO", todoTitle = "今天", todoItems = "买纸\n打印错题"),
        ) ?: error("missing layout")

        assertTrue(layout.elements.first() is TextElement)
        assertEquals(2, layout.elements.filterIsInstance<FrameElement>().size)
        assertEquals(listOf("今天", "买纸", "打印错题"), layout.elements.filterIsInstance<TextElement>().map { it.text })
    }
}
